package com.shinyhunter;

import com.shinyhunter.api.PlayerDex;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a Safari run is going for, after Sparkling Mutuals' modes. It decides what counts as still
 * outstanding (the missing panels, {@code !m}, biome-clear calls and splits all ask
 * {@link CritterTracker#missing}) and which critters get outlined.
 *
 * <ul>
 *   <li><b>Unique</b>: one of each critter. Outstanding until one is secured; outlined until then.</li>
 *   <li><b>Full clear</b>: every critter that spawns. Outstanding until the party has secured as
 *       many as always spawn (Sparkling Mutuals' minimums); every live critter is outlined, since
 *       all of them count. Critters that spawn a varying number of times or not at all (birds,
 *       Rockmite, Snoozle) never hold a biome open.</li>
 *   <li><b>Sparkling</b>: the sparklings someone in the party still lacks (your dex and each
 *       member's, from the API). Those critters are outstanding until one is secured, and every live
 *       one of them is outlined, since any of them could be the sparkling.</li>
 * </ul>
 *
 * <p>Split bests are kept per mode: Unique keeps the plain keys it always had, the others are
 * saved under their own prefix, so a full clear never beats or spoils a unique PB.
 */
public enum SafariMode {
    UNIQUE("Unique"), FULL_CLEAR("Full clear"), SPARKLING("Sparkling");

    public final String label;

    SafariMode(String label) {
        this.label = label;
    }

    /** How many of each critter always spawn: a full clear needs at least this many. 0 varies. */
    private static final Map<String, Integer> QUOTAS = Map.ofEntries(
            Map.entry("Foxtrot", 6), Map.entry("Bluebird", 0), Map.entry("Honeybug", 3), Map.entry("Treefrog", 3),
            Map.entry("Woodchucker", 3), Map.entry("Fluffling", 1), Map.entry("Hideonfloor", 1), Map.entry("Parakeet", 0),
            Map.entry("Macaw", 0), Map.entry("Cavernfish", 4), Map.entry("Flitter", 6), Map.entry("Shyworm", 4),
            Map.entry("Driftling", 3), Map.entry("Chuckwalla", 2), Map.entry("Rockmite", 0), Map.entry("Scrappy", 3),
            Map.entry("Snoozle", 0), Map.entry("Gemzie", 3), Map.entry("Strongarm", 4), Map.entry("Tepid", 6),
            Map.entry("Polaris", 2), Map.entry("Shuddersquid", 3), Map.entry("Billygoat", 2), Map.entry("Mantis Shrimp", 3),
            Map.entry("Nozzlenose", 2), Map.entry("Troodon", 3), Map.entry("Wumpa", 1), Map.entry("Areita", 3),
            Map.entry("Bloodbat", 3), Map.entry("Duplico", 2), Map.entry("Gazer", 4), Map.entry("Litterbug", 4),
            Map.entry("Solsnatcher", 4), Map.entry("Gimmiegold", 3), Map.entry("Hideonwall", 2), Map.entry("Hideyho", 1),
            Map.entry("Doomspiral", 1));

    /** Sparklings someone in the party still lacks; refreshed every second. */
    private static Set<String> needed = Set.of();
    private static int tickCounter;

    public static SafariMode current() {
        return parse(ShinyConfig.get().safariMode);
    }

    public static SafariMode parse(String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.startsWith("f")) {
            return FULL_CLEAR;
        }
        if (t.startsWith("s")) {
            return SPARKLING;
        }
        return UNIQUE;
    }

    /** The value saved in the config for this mode. */
    public String id() {
        return switch (this) {
            case UNIQUE -> "unique";
            case FULL_CLEAR -> "fullclear";
            case SPARKLING -> "sparkling";
        };
    }

    public SafariMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static int quota(String critter) {
        return QUOTAS.getOrDefault(critter, 1);
    }

    // ------------------------------------------------------------------ the rules

    /** Whether this critter is still outstanding on the current run. */
    public static boolean outstanding(String critter) {
        return switch (current()) {
            case UNIQUE -> !CritterTracker.isFound(critter) && !PartyDex.isShared(critter);
            case FULL_CLEAR -> quota(critter) > 0 && CritterTracker.count(critter) < quota(critter);
            case SPARKLING -> needed.contains(critter) && !CritterTracker.isFound(critter);
        };
    }

    /** Whether a live critter of this kind should be outlined. */
    public static boolean highlight(String critter) {
        return switch (current()) {
            case UNIQUE -> outstanding(critter);
            case FULL_CLEAR -> true;
            case SPARKLING -> needed.contains(critter);
        };
    }

    /** The critters of a biome the current mode counts at all, for "x/y" progress. */
    public static List<String> counted(String biome) {
        List<String> out = new ArrayList<>();
        SafariMode mode = current();
        for (String critter : CritterDex.critters(biome)) {
            boolean counts = switch (mode) {
                case UNIQUE -> !PartyDex.isShared(critter);
                case FULL_CLEAR -> quota(critter) > 0;
                case SPARKLING -> needed.contains(critter);
            };
            if (counts) {
                out.add(critter);
            }
        }
        return out;
    }

    /** A missing-panel row: the name, and in a full clear how many of the quota are secured. */
    public static String row(String critter) {
        if (current() == FULL_CLEAR) {
            return critter + " §7" + CritterTracker.count(critter) + "/" + quota(critter);
        }
        return critter;
    }

    /** The key a split's best is saved under in this mode. */
    public static String bestKey(String split) {
        SafariMode mode = current();
        return mode == UNIQUE ? split : mode.label + ": " + split;
    }

    // ------------------------------------------------------------------ party sparklings

    public static void onClientTick(Minecraft client) {
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        if (current() != SPARKLING || client.player == null) {
            needed = Set.of();
            return;
        }
        Set<String> mine = Achievements.ownDex();
        Set<String> out = new HashSet<>();
        for (String biome : CritterDex.biomes()) {
            for (String critter : CritterDex.critters(biome)) {
                if (!mine.contains(critter) || memberLacks(critter)) {
                    out.add(critter);
                }
            }
        }
        needed = out;
    }

    /** A party member without this sparkling, or whose dex hasn't been looked up yet. */
    private static boolean memberLacks(String critter) {
        for (String member : PartyDex.members()) {
            PlayerDex dex = null;
            for (Map.Entry<String, PlayerDex> known : PartyDex.dexes().entrySet()) {
                if (known.getKey().equalsIgnoreCase(member)) {
                    dex = known.getValue();
                }
            }
            if (dex == null || !dex.caught().contains(critter)) {
                return true;
            }
        }
        return false;
    }

    /** {@code /shiny mode [unique|fullclear|sparkling]}: switch, or cycle with no argument. */
    public static SafariMode select(SafariMode mode) {
        ShinyConfig config = ShinyConfig.get();
        config.safariMode = mode.id();
        config.save();
        tickCounter = 20; // recompute the sparkling targets straight away
        return mode;
    }
}
