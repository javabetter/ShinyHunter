package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks whether the current Miria's Contest has been completed, and warns while it hasn't.
 *
 * <p>Contests run on a fixed 20-minute cadence: one starts exactly at :15, :35 and :55 past each
 * hour and ends 30 seconds before the next one starts. "Complete" means reaching Uncommon tier or
 * better inside that window — the sidebar names the tier outright, so that's what is read.
 *
 * <p>So there are two clocks here: the wall clock decides which window we're in, when it ends,
 * and when to reset; the sidebar says whether this window's contest has reached the mark.
 *
 * <p><b>The status is remembered, not re-read.</b> The sidebar only shows the contest rows while
 * you're somewhere the contest applies. Walk out and they vanish, but the contest is still complete
 * for the rest of its window — so once complete, the status holds until the window ends whatever
 * the sidebar currently shows.
 *
 * <p><b>Warnings, not fanfare.</b> The sound is for the case that matters: the contest is
 * <em>not</em> done and time is running out. It rings at each configured minutes-left mark, once
 * per mark per window, and never once the contest is complete.
 */
public final class ContestTracker {

    /**
     * The tier row: {@code COMMON with 68}, {@code RARE with 1.1k}, {@code EPIC with 3,400}.
     *
     * <p>The points are captured as whatever token follows "with" and parsed leniently, because
     * Hypixel abbreviates past a thousand ("1.1k") and an earlier digits-only pattern stopped
     * matching the row at all once a player passed 1000 — which meant the tier was never read and
     * the contest never registered as complete for anyone who scored well.
     */
    private static final Pattern TIER_ROW = Pattern.compile(
            "^\\s*([A-Za-z]+)\\s+with\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /**
     * Every tier that counts as complete. Anything not listed — Common, or a tier Hypixel adds
     * later — is treated as not yet there, which fails safe: a missed warning is better than a
     * false "Complete".
     */
    private static final Set<String> COMPLETE_TIERS = Set.of(
            "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL");

    private static boolean isTierName(String word) {
        String upper = word.toUpperCase();
        return upper.equals("COMMON") || COMPLETE_TIERS.contains(upper);
    }

    /** Minutes past the hour at which contests begin; the first is also the window offset. */
    private static final int FIRST_START_MINUTE = 15;

    /** Contest windows are this long, start to start. */
    private static final int WINDOW_SECONDS = 20 * 60;

    /** A contest ends this long before the next one starts. */
    private static final int END_GAP_SECONDS = 30;

    /**
     * Right after a reset the sidebar may still show the contest that just ended. A window that
     * has only just opened cannot already be Uncommon, so readings this early are ignored.
     */
    private static final int STALE_GUARD_SECONDS = 10;

    private static final int RESCAN_INTERVAL_TICKS = 10;

    /** Enough copies to be unmistakable; past this the mix just clips. */
    private static final int MAX_SOUND_COPIES = 20;

    private static int tickCounter;

    /** Identity of the window the status belongs to; changes at every :15/:35/:55. */
    private static long windowKey = -1;

    /** Seconds into the current window, from the wall clock. */
    private static int secondsIntoWindow;

    private static boolean complete;

    /** Minute marks already rung this window, so each rings once. */
    private static final Set<Integer> WARNED = new LinkedHashSet<>();

    /** Last sidebar reading, for the completion test only. -1 when the sidebar isn't showing it. */
    private static int points = -1;
    private static String tier = "";

    private ContestTracker() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackContest || client.player == null || client.level == null) {
            return;
        }
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        rollWindow();
        readSidebar(client, config);
        maybeWarn(client, config);
    }

    // ------------------------------------------------------------------ the wall clock

    /**
     * Moves to a new window when the clock crosses a start minute, resetting the status. The key
     * is the number of whole windows since midnight, offset so that :15 is a boundary.
     */
    private static void rollWindow() {
        LocalTime now = LocalTime.now();
        int shifted = Math.floorMod(now.toSecondOfDay() - FIRST_START_MINUTE * 60, 24 * 3600);
        long key = shifted / WINDOW_SECONDS;
        secondsIntoWindow = shifted % WINDOW_SECONDS;

        if (key != windowKey) {
            boolean first = windowKey == -1;
            windowKey = key;
            complete = false;
            WARNED.clear();
            if (!first) {
                ShinyHunterClient.LOGGER.info("Contest window rolled at {} — status reset", now);
                Debug.log("contest window started at", now.withNano(0), "- Incomplete");
            }
        }
    }

    /** Seconds until this window's contest ends, or 0 once it has. */
    public static int secondsToEnd() {
        return Math.max(0, WINDOW_SECONDS - END_GAP_SECONDS - secondsIntoWindow);
    }

    /** Seconds until the next contest starts. */
    public static int secondsToNextStart() {
        return WINDOW_SECONDS - secondsIntoWindow;
    }

    /** True in the 30-second gap between one contest ending and the next starting. */
    public static boolean betweenContests() {
        return secondsToEnd() == 0;
    }

    // ------------------------------------------------------------------ the sidebar

    private static void readSidebar(Minecraft client, ShinyConfig config) {
        int foundPoints = -1;
        String foundTier = "";
        for (String line : SkyblockSidebar.lines(client)) {
            Matcher tierMatch = TIER_ROW.matcher(line);
            // Only a row whose first word is an actual tier: "Playing with 3" must not shadow it.
            if (tierMatch.find() && isTierName(tierMatch.group(1))) {
                foundTier = tierMatch.group(1);
                foundPoints = parseInt(tierMatch.group(2));
                break;
            }
        }
        points = foundPoints;
        tier = foundTier;

        // The tier alone decides. Points are parsed only for the log — a row that reads as a tier
        // but whose number is unparseable must still count.
        if (tier.isEmpty() || complete || secondsIntoWindow < STALE_GUARD_SECONDS) {
            return;
        }
        if (COMPLETE_TIERS.contains(tier.toUpperCase())) {
            complete = true;
            ShinyHunterClient.LOGGER.info("Contest complete — {} with {}", tier, points);
            Debug.log("contest complete:", tier, "with", points);
        }
    }

    // ------------------------------------------------------------------ warnings

    /**
     * Rings at each configured minutes-left mark while the contest is still incomplete. A mark
     * fires the first time the remaining time is at or under it, so a client that joins with two
     * minutes left gets the 5- and 3-minute marks at once rather than never.
     */
    private static void maybeWarn(Minecraft client, ShinyConfig config) {
        if (complete || !config.contestWarnEnabled || betweenContests()) {
            return;
        }
        // Only meaningful somewhere the contest exists — ringing on your island would be noise.
        if (!SkyblockSidebar.inSkyblock(client)) {
            return;
        }

        int remaining = secondsToEnd();
        boolean rang = false;
        for (int minutes : warnMinutes(config)) {
            if (remaining <= minutes * 60 && WARNED.add(minutes)) {
                rang = true;
                ShinyHunterClient.LOGGER.info("Contest warning — {}m mark, contest not complete", minutes);
                client.player.sendSystemMessage(Component.literal(
                        "§b[Shiny Hunter] §c§lContest not complete§r — " + formatRemaining(remaining)
                                + " left."));
            }
        }
        if (rang) {
            playSound(client, config);
            if (config.contestWarnTitle) {
                showTitle(client, remaining);
            }
        }
    }

    /** The configured marks, largest first. Anything unparseable is ignored. */
    static List<Integer> warnMinutes(ShinyConfig config) {
        List<Integer> marks = new ArrayList<>();
        for (String part : config.contestWarnMinutes.split("[,\\s]+")) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value > 0 && !marks.contains(value)) {
                    marks.add(value);
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        marks.sort((a, b) -> Integer.compare(b, a));
        return marks;
    }

    /**
     * Rings the warning at the configured loudness.
     *
     * <p><b>Why copies rather than a volume.</b> The sound engine clamps every instance's gain to
     * 1.0 — a "volume" above that only makes a positional sound carry further, and does nothing at
     * all to one played at the listener. So 10000% sounded exactly like 100%. What does get louder
     * is several identical sounds started on the same tick, which sum. The percentage is therefore
     * turned into a number of simultaneous copies: 100% is one, 300% is three, and so on, with the
     * last copy carrying the fractional remainder. Played as a UI sound so distance and the
     * "players" category can't quiet it either.
     */
    private static void playSound(Minecraft client, ShinyConfig config) {
        Identifier id = Identifier.tryParse(config.contestSound.trim());
        Optional<SoundEvent> sound = id == null
                ? Optional.empty()
                : BuiltInRegistries.SOUND_EVENT.getOptional(id);
        if (sound.isEmpty()) {
            ShinyHunterClient.LOGGER.warn("Contest sound '{}' is not a known sound — nothing played",
                    config.contestSound);
            return;
        }

        float wanted = Math.max(0f, config.contestSoundVolume / 100.0f);
        int copies = Math.min(MAX_SOUND_COPIES, (int) Math.ceil(wanted));
        for (int i = 0; i < copies; i++) {
            float gain = Math.min(1.0f, wanted - i);
            client.getSoundManager().play(SimpleSoundInstance.forUI(sound.get(), 1.0f, gain));
        }
    }

    /** The on-screen card, in the style of the SPARKLING alert. */
    private static void showTitle(Minecraft client, int remaining) {
        client.gui.setTimes(5, 60, 15);
        client.gui.setTitle(Component.literal("§c§lCONTEST INCOMPLETE!"));
        client.gui.setSubtitle(Component.literal("§e" + formatRemaining(remaining) + " left to reach Uncommon"));
    }

    // ------------------------------------------------------------------ formatting

    /** {@code 12:34} — minutes and zero-padded seconds. */
    public static String formatRemaining(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** "68", "3,400", "1.1k", "2.5m" — whatever the sidebar abbreviates to. -1 if unreadable. */
    static int parseInt(String token) {
        String t = token.trim().toLowerCase().replace(",", "");
        double scale = 1;
        if (t.endsWith("k")) {
            scale = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            scale = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return (int) Math.round(Double.parseDouble(t) * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ state

    public static boolean isComplete() {
        return complete;
    }
}
