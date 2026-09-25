package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks which critters have turned up on the current Safari instance.
 *
 * <p>Learned from chat: your own capture, and the loot-share line when a teammate catches one. Both
 * mean the critter was actually secured, so nobody needs to keep hunting it. Throwing a capsule
 * doesn't count — critters can break out, so a throw is an attempt rather than a catch.
 *
 * <p><b>Found by name, not by sentence.</b> Hypixel words these lines several ways and varies the
 * shard quantity within each ("gained a Shard", "gained 2x Shard", "it gave you 3x Shard"). Matching
 * the sentence kept missing a variant, so the line is only checked for the CAPTURE/LOOT SHARE marker
 * and the critter is then located by its own name — the one part that never changes.
 *
 * <p>Everything is scoped to the instance and cleared on a server hop, because a fresh instance
 * re-rolls which critters spawn. Nothing is written to disk for the same reason.
 */
public final class CritterTracker {

    /**
     * The two lines that mean a critter was actually secured: your own capture, and the loot share
     * from a teammate's. Only the marker is matched here — the critter is then found by name,
     * because the wording around it varies ("You caught a X and gained 2x...", "You found the X,
     * and as a reward it gave you 3x...") and every attempt to pin down the sentence has missed a
     * variant.
     *
     * <p>Throwing a capsule is deliberately NOT included: critters can break out, so a throw is an
     * attempt rather than a catch.
     */
    private static final Pattern SECURED = Pattern.compile(
            "^(?:CAPTURE!|LOOT SHARE!)", Pattern.CASE_INSENSITIVE);

    /**
     * Somebody asking the party what's still outstanding, optionally for one biome —
     * {@code !missing}, {@code !missing haunted}, or the short forms {@code !m} and {@code !m h}.
     */
    private static final Pattern MISSING_REQUEST = Pattern.compile(
            "^!m(?:issing)?(?:\\s+(\\S+))?$", Pattern.CASE_INSENSITIVE);

    /** Longest single chat line worth attempting; past this Hypixel would cut it off mid-name. */
    private static final int MAX_LINE_LENGTH = 240;

    /** Canonical critter names seen this instance, in the order they turned up. */
    private static final Set<String> FOUND = new LinkedHashSet<>();

    /** Biomes already called out as complete, so it's said once per instance. */
    private static final Set<String> ANNOUNCED_CLEAR = new LinkedHashSet<>();

    private static ClientLevel lastLevel;

    private CritterTracker() {
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
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackCritters) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }

        if (handleMissingRequest(config, text)) {
            return;
        }

        // A relayed copy of somebody's capture line isn't the server telling us anything — only the
        // server's own lines count, or a teammate quoting one would register a catch twice.
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        if (SECURED.matcher(text).find()) {
            record(CritterDex.findIn(text), config);
        }
    }

    /**
     * Replies with what this player is still missing when the party asks.
     *
     * <p>Party chat only, and only from inside the Safari: the tally describes the current instance,
     * so answering from the hub — or answering a guild-wide "!missing" that had nothing to do with
     * the run — would be noise at best and wrong at worst.
     */
    private static boolean handleMissingRequest(ShinyConfig config, String text) {
        if (!config.respondToMissing || !isPartyMessage(text)) {
            return false;
        }
        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!relayed.find()) {
            return false;
        }
        Matcher request = MISSING_REQUEST.matcher(relayed.group(2).trim());
        if (!request.matches()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        if (!SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            ShinyHunterClient.LOGGER.info("!missing asked outside {} — not answering", config.huntLocation);
            return true; // recognised and deliberately ignored
        }

        // "!missing haunted" answers for that biome alone — far shorter than the full summary, and
        // usually what's wanted once the party is working one biome at a time.
        String requested = request.group(1);
        if (requested != null) {
            String biome = matchBiome(requested);
            if (biome == null) {
                ShinyHunterClient.LOGGER.info("!missing named an unknown biome '{}'", requested);
                return true;
            }
            PartyChat.send(config.critterChannel, biome + ": " + describe(biome, "Done"));
            return true;
        }

        String combined = missingSummary();
        if (combined.length() <= MAX_LINE_LENGTH) {
            PartyChat.send(config.critterChannel, combined);
        } else {
            // Early in a run almost everything is outstanding and one line would overrun the chat
            // limit, losing the tail silently. Split rather than send something truncated.
            ShinyHunterClient.LOGGER.info("Missing summary is {} chars — splitting per biome",
                    combined.length());
            for (String biome : CritterDex.biomes()) {
                if (!missing(biome).isEmpty()) {
                    PartyChat.send(config.critterChannel, biome + ": " + describe(biome, "Done"));
                }
            }
        }
        return true;
    }

    /** The known biome whose name matches the given word, or null. */
    static String matchBiome(String word) {
        String w = word.trim().toLowerCase();
        for (String biome : CritterDex.biomes()) {
            if (biome.equalsIgnoreCase(w)) {
                return biome;
            }
        }
        // The short and colloquial names people actually type mid-run, down to single letters —
        // "!m i" is what gets typed when a critter is escaping and the other hand is on the mouse.
        return switch (w) {
            case "i", "ice", "frozen", "cold" -> "Icy";
            case "h", "haunt", "spooky", "ghost" -> "Haunted";
            case "f", "fore", "wood", "woods" -> "Forest";
            case "c", "cav", "cave", "caves", "desert", "sand" -> "Cavern";
            default -> null;
        };
    }

    /** Only party chat counts — guild and co-op channels are somebody else's conversation. */
    private static boolean isPartyMessage(String text) {
        return text.contains("Party >");
    }

    /**
     * Every biome's outstanding critters on one line, divided by pipes and in the fixed biome order,
     * with a tick for any that's complete — e.g. {@code "Snoozle, Flitter | Bloodbat | Gazer | ✔"},
     * and all four ticks once the instance is finished.
     *
     * <p>The tick is configurable because Hypixel filters chat: a glyph it doesn't allow is dropped
     * silently, leaving an empty slot that reads as "no data" rather than "done". If that happens,
     * change the mark to a word rather than losing the information.
     */
    public static String missingSummary() {
        String mark = ShinyConfig.get().clearMark;
        List<String> parts = new ArrayList<>();
        for (String biome : CritterDex.biomes()) {
            parts.add(describe(biome, mark));
        }
        return String.join(" | ", parts);
    }

    /** One biome's state for chat: {@code done} when complete, the missing names otherwise. */
    static String describe(String biome, String done) {
        List<String> missing = missing(biome);
        return missing.isEmpty() ? done : String.join(", ", missing);
    }

    /**
     * Whether the Forest is down to just the Macaw. Macaw fails to spawn on most runs, so this is
     * the point where the Forest is usually as done as it's going to get — worth its own call-out
     * ahead of "Forest clear!", which may never come.
     */
    private static boolean onlyMacawLeft(String biome) {
        List<String> missing = missing(biome);
        return missing.size() == 1 && SplitTracker.MACAW.equals(missing.get(0))
                && CritterDex.biomeOf(SplitTracker.MACAW) != null
                && CritterDex.biomeOf(SplitTracker.MACAW).equals(biome);
    }

    private static void record(String critter, ShinyConfig config) {
        if (critter == null) {
            return; // a capture line that didn't name anything in the dex
        }
        if (!FOUND.add(critter)) {
            return; // already seen this instance
        }

        String biome = CritterDex.biomeOf(critter);
        ShinyHunterClient.LOGGER.info("Critter found: {} ({}) — {}/{} this instance",
                critter, biome, FOUND.size(), CritterDex.total());
        Debug.log("secured:", critter, "(" + biome + ")", FOUND.size() + "/" + CritterDex.total(),
                "- " + biome + " still missing", missing(biome));

        if (biome != null && config.announceBiomeClear
                && missing(biome).isEmpty() && ANNOUNCED_CLEAR.add(biome)) {
            PartyChat.send(config.critterChannel, biome + " clear!");
        }

        // "Forest 8/9, missing Macaw": only with the setting on, only when Macaw is the last one.
        if (biome != null && config.announceMacawOnly && onlyMacawLeft(biome)
                && ANNOUNCED_CLEAR.add(biome + ":macaw")) {
            int total = CritterDex.critters(biome).size();
            PartyChat.send(config.critterChannel, biome + " " + (total - 1) + "/" + total
                    + ", missing " + SplitTracker.MACAW);
        }

        // Announced once per instance, keyed in the same set as the per-biome calls so it can't
        // repeat if a later loot share re-enters this path.
        if (config.announceAllUniques && FOUND.size() >= CritterDex.total()
                && ANNOUNCED_CLEAR.add("*all*")) {
            PartyChat.send(config.critterChannel, "All uniques caught!");
        }
    }

    // ------------------------------------------------------------------ state

    /**
     * Critters of this biome not yet seen on the current instance.
     *
     * <p>Sparklings the whole party already has are left out when that option is on — the party
     * agreed to skip them, so they shouldn't sit on the list, hold a biome open, or delay a split.
     */
    public static List<String> missing(String biome) {
        List<String> missing = new ArrayList<>();
        for (String critter : CritterDex.critters(biome)) {
            if (!FOUND.contains(critter) && !PartyDex.isShared(critter)) {
                missing.add(critter);
            }
        }
        return missing;
    }

    /** True when this critter has already been secured on the current instance. */
    public static boolean isFound(String critter) {
        return critter != null && FOUND.contains(critter);
    }

    public static int foundCount() {
        return FOUND.size();
    }

    /** Clears on a server hop — a new instance re-rolls which critters spawn. */
    public static void onClientTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
    }

    public static void reset() {
        if (!FOUND.isEmpty()) {
            ShinyHunterClient.LOGGER.info("Critter tracker reset ({} had been found)", FOUND.size());
        }
        FOUND.clear();
        ANNOUNCED_CLEAR.clear();
    }
}
