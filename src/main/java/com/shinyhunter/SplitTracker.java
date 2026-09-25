package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-biome splits, measured from the start of the run, plus the bests they're compared against.
 *
 * <p>A split is the elapsed time at the moment a biome's last critter is secured — cumulative from
 * the start of the run, not the time spent in that biome, which is how the times are quoted and
 * compared. They're taken by watching {@link CritterTracker}'s tally each tick rather than by
 * hooking the catch, so a biome that completes through a teammate's loot share is caught the same
 * way as one you finish yourself.
 *
 * <p><b>Forest without Macaw.</b> Macaw is the one Forest critter that regularly refuses to show up,
 * so the split that describes how the run actually went is the one taken when everything else in
 * the Forest is done. It's tracked alongside the real Forest split, never instead of it.
 *
 * <p><b>Whose best is it.</b> Every split is offered to the party best, which is the whole party's
 * time regardless of who was where. A split is offered to your <em>personal</em> best when you did
 * the work in that biome: you personally caught at least {@code ownBiomeMinCatches} of its critters.
 *
 * <p>That's a threshold rather than a contest, because more than one biome can genuinely be yours —
 * clearing two of them in a run is a normal thing to do, and an earlier version that awarded the
 * single biome you'd caught most in gave you nothing at all when two tied on nine apiece. Where
 * nothing reaches the threshold there's a fallback to the biome you caught most in, so a run cut
 * short still credits somewhere, but only when one biome is a clear winner.
 *
 * <p>Committed when the run ends, because how much you caught where isn't settled until then.
 */
public final class SplitTracker {

    /** Your own catch. Loot share means a teammate made it, which says nothing about where you were. */
    private static final Pattern OWN_CAPTURE = Pattern.compile("^CAPTURE!", Pattern.CASE_INSENSITIVE);

    /** The Forest critter the alternate split leaves out. */
    public static final String MACAW = "Macaw";

    /** Split key for the Forest-minus-Macaw time. Not a biome, so it's never treated as one. */
    public static final String FOREST_NO_MACAW = "Forest w/o Macaw";

    /** This run's splits, in the order they were set. */
    private static final Map<String, Long> SPLITS = new LinkedHashMap<>();

    /** Personal catches per biome this run, duplicates included — this is a tally, not a checklist. */
    private static final Map<String, Integer> OWN_CATCHES = new LinkedHashMap<>();

    /**
     * Whether this run's splits have already been written into the bests.
     *
     * <p>Commit is attempted from more than one place — the run ending, and the server hop that
     * usually accompanies it — because relying on a single trigger is what lost a whole run's bests
     * once already: the run-ended path is gated on reading the sidebar zone, and when that read
     * failed the splits were taken, shown in chat, and then silently dropped. Two triggers and a
     * flag means whichever fires first records them and the other is a no-op.
     */
    private static boolean committed;

    private SplitTracker() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> handle(message));
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        if (!ShinyConfig.get().trackSplits) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || HotspotAnnouncer.isRelayed(text)) {
            return;
        }
        if (!OWN_CAPTURE.matcher(text).find()) {
            return;
        }

        String critter = CritterDex.findIn(text);
        String biome = CritterDex.biomeOf(critter);
        if (biome != null) {
            int count = OWN_CATCHES.merge(biome, 1, Integer::sum);
            Debug.log("own catch:", critter, "->", biome, "=", count);
        } else {
            Debug.log("own capture line matched no critter:", text);
        }
    }

    // ------------------------------------------------------------------ tick

    /**
     * Takes any split that has just come due. Polled rather than pushed so that both routes to a
     * complete biome — your capture and a teammate's loot share — land here identically.
     */
    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackSplits || !SafariTimer.isRunning()) {
            return;
        }

        for (String biome : CritterDex.biomes()) {
            if (CritterTracker.missing(biome).isEmpty()) {
                take(biome, config);
            }
        }

        // The alternate Forest split: everything but Macaw accounted for.
        List<String> forestLeft = CritterTracker.missing("Forest");
        if (forestLeft.isEmpty() || (forestLeft.size() == 1 && forestLeft.contains(MACAW))) {
            take(FOREST_NO_MACAW, config);
        }
    }

    private static void take(String key, ShinyConfig config) {
        if (SPLITS.containsKey(key)) {
            return;
        }
        long elapsed = SafariTimer.elapsedMillis();
        SPLITS.put(key, elapsed);
        ShinyHunterClient.LOGGER.info("Split {} at {}", key, SafariTimer.format(elapsed));
        Debug.log("split taken:", key, "at", SafariTimer.format(elapsed),
                "- bests commit when the run ends");

        if (!config.announceSplits) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §f" + key + " §7split §b" + SafariTimer.format(elapsed)
                            + comparison(key, elapsed)));
        }
    }

    /** " §7(-2.10s PB)" style tail, or empty when there's nothing to compare against. */
    private static String comparison(String key, long elapsed) {
        Long best = ShinyConfig.get().personalBests.get(key);
        if (best == null) {
            return " §8(first)";
        }
        long delta = elapsed - best;
        return delta < 0
                ? " §a(-" + SafariTimer.format(-delta) + ")"
                : " §c(+" + SafariTimer.format(delta) + ")";
    }

    // ------------------------------------------------------------------ run end

    /**
     * Writes this run's splits into the saved bests.
     *
     * <p>Deferred to the end of the run because the personal best depends on which biome you were
     * working, and that isn't settled until the last catch is counted.
     */
    public static void commitBests() {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackSplits) {
            Debug.log("commit skipped: split tracking is off");
            return;
        }
        if (committed) {
            Debug.log("commit skipped: already committed this run");
            return;
        }
        if (SPLITS.isEmpty()) {
            Debug.log("commit skipped: no splits were taken this run");
            return;
        }
        committed = true;

        Set<String> mine = ownBiomes();
        boolean changed = false;
        ShinyHunterClient.LOGGER.info("Run over — own catches {}, counting as yours: {}",
                OWN_CATCHES, mine.isEmpty() ? "nothing" : mine);
        Debug.log("committing — own catches", OWN_CATCHES,
                "threshold", config.ownBiomeMinCatches, "yours:", mine.isEmpty() ? "none" : mine);

        for (Map.Entry<String, Long> split : SPLITS.entrySet()) {
            String key = split.getKey();
            long time = split.getValue();

            // The whole party ran every split, so every one of them counts for the party best.
            Long partyBest = config.partyBests.get(key);
            if (partyBest == null || time < partyBest) {
                config.partyBests.put(key, time);
                changed = true;
                ShinyHunterClient.LOGGER.info("New party best for {}: {}", key, SafariTimer.format(time));
                Debug.log("party best:", key, SafariTimer.format(time));
            } else {
                Debug.log("no party best:", key, SafariTimer.format(time),
                        "is not under", SafariTimer.format(partyBest));
            }

            if (!isOwn(key, mine)) {
                Debug.log("not yours:", key, "- you caught",
                        OWN_CATCHES.getOrDefault(key, 0), "of", config.ownBiomeMinCatches, "needed");
                continue;
            }
            Long personalBest = config.personalBests.get(key);
            if (personalBest == null || time < personalBest) {
                config.personalBests.put(key, time);
                changed = true;
                ShinyHunterClient.LOGGER.info("New personal best for {}: {}", key, SafariTimer.format(time));
                Debug.log("PERSONAL best:", key, SafariTimer.format(time));
            } else {
                Debug.log("no personal best:", key, SafariTimer.format(time),
                        "is not under", SafariTimer.format(personalBest));
            }
        }

        if (changed) {
            config.save();
        }
    }

    /** True when a split belongs to one of the biomes this player worked. */
    private static boolean isOwn(String key, Set<String> mine) {
        // The Forest-minus-Macaw split is a Forest split, and rides on the same judgement.
        return mine.contains(key) || (FOREST_NO_MACAW.equals(key) && mine.contains("Forest"));
    }

    /**
     * Every biome this player worked: those they personally caught at least the configured number
     * in. More than one can qualify, because clearing two biomes in a run is ordinary.
     *
     * <p>When nothing reaches the threshold — a short run, or a run spent helping everywhere — it
     * falls back to the single biome with the most catches, and only when that's an outright win.
     * A tie there means the evidence doesn't say where you were, and guessing would file a time
     * under a best that isn't yours.
     */
    public static Set<String> ownBiomes() {
        int threshold = ShinyConfig.get().ownBiomeMinCatches;
        Set<String> qualifying = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : OWN_CATCHES.entrySet()) {
            if (entry.getValue() >= threshold) {
                qualifying.add(entry.getKey());
            }
        }
        if (!qualifying.isEmpty()) {
            return qualifying;
        }

        String best = null;
        int bestCount = 0;
        boolean tied = false;
        for (Map.Entry<String, Integer> entry : OWN_CATCHES.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                best = entry.getKey();
                bestCount = count;
                tied = false;
            } else if (count == bestCount) {
                tied = true;
            }
        }
        return best == null || tied ? Set.of() : Set.of(best);
    }

    // ------------------------------------------------------------------ state

    /** This run's splits, biome order first and the Forest variant last. */
    public static Map<String, Long> splits() {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String biome : CritterDex.biomes()) {
            Long time = SPLITS.get(biome);
            if (time != null) {
                ordered.put(biome, time);
            }
        }
        Long forestNoMacaw = SPLITS.get(FOREST_NO_MACAW);
        if (forestNoMacaw != null) {
            ordered.put(FOREST_NO_MACAW, forestNoMacaw);
        }
        return ordered;
    }

    /** Every split key that could appear, in display order. */
    public static List<String> keys() {
        List<String> keys = new ArrayList<>(CritterDex.biomes());
        keys.add(FOREST_NO_MACAW);
        return keys;
    }

    public static Long split(String key) {
        return SPLITS.get(key);
    }

    public static Map<String, Integer> ownCatches() {
        return OWN_CATCHES;
    }

    public static void reset() {
        SPLITS.clear();
        OWN_CATCHES.clear();
        committed = false;
    }

    /** Wipes the saved bests. Both sets go together — half a comparison is worse than none. */
    public static void clearBests() {
        ShinyConfig config = ShinyConfig.get();
        config.personalBests.clear();
        config.partyBests.clear();
        config.save();
    }
}
