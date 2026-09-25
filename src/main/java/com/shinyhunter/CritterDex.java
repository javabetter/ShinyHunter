package com.shinyhunter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full roster of Safari critters, by biome.
 *
 * <p>Fixed data rather than anything learned at runtime: the point of the tracker is knowing what's
 * still <i>missing</i>, which can only be answered against a known-complete list.
 *
 * <p>Lookups are case-insensitive on the exact name. Substring matching would be wrong here —
 * "Hideyho" and "Hideonwall" are separate critters in separate biomes, and a loose match would
 * credit the wrong one.
 */
public final class CritterDex {

    /** Biome order is fixed so the HUD panels and chat replies never reshuffle. */
    private static final Map<String, List<String>> BY_BIOME = new LinkedHashMap<>();

    static {
        BY_BIOME.put("Cavern", List.of(
                "Cavernfish", "Chuckwalla", "Driftling", "Gemzie", "Rockmite",
                "Scrappy", "Shyworm", "Snoozle", "Flitter"));
        BY_BIOME.put("Forest", List.of(
                "Bluebird", "Fluffling", "Foxtrot", "Hideonfloor", "Honeybug",
                "Macaw", "Parakeet", "Treefrog", "Woodchucker"));
        BY_BIOME.put("Haunted", List.of(
                "Areita", "Bloodbat", "Doomspiral", "Duplico", "Gazer",
                "Gimmiegold", "Hideonwall", "Hideyho", "Litterbug", "Solsnatcher"));
        BY_BIOME.put("Icy", List.of(
                "Billygoat", "Mantis Shrimp", "Nozzlenose", "Polaris", "Shuddersquid",
                "Strongarm", "Tepid", "Troodon", "Wumpa"));
    }

    private CritterDex() {
    }

    public static List<String> biomes() {
        return List.copyOf(BY_BIOME.keySet());
    }

    public static List<String> critters(String biome) {
        return BY_BIOME.getOrDefault(biome, List.of());
    }

    /** Every critter across every biome. */
    public static int total() {
        int total = 0;
        for (List<String> critters : BY_BIOME.values()) {
            total += critters.size();
        }
        return total;
    }

    /**
     * The canonical critter name matching the given text, or null if it isn't one of ours.
     * Returning the canonical spelling means the tracker stores one form regardless of how the
     * server capitalised it.
     */
    public static String canonical(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        for (List<String> critters : BY_BIOME.values()) {
            for (String critter : critters) {
                if (critter.equalsIgnoreCase(trimmed)) {
                    return critter;
                }
            }
        }
        return null;
    }

    /**
     * The critter named anywhere in the given line, or null if none is.
     *
     * <p>Used instead of matching the sentence around the name, because Hypixel words its capture
     * lines several ways ("You caught a X and gained...", "You found the X, and as a reward...").
     * The critter's name is the one stable part, so that's what's looked for.
     *
     * <p>Matched on word boundaries, longest name first. No current critter name contains another,
     * but ordering by length keeps that from mattering if one ever does.
     */
    public static String findIn(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String upper = text.toUpperCase();
        String best = null;
        for (List<String> critters : BY_BIOME.values()) {
            for (String critter : critters) {
                if (best != null && critter.length() <= best.length()) {
                    continue;
                }
                if (containsWord(upper, critter.toUpperCase())) {
                    best = critter;
                }
            }
        }
        return best;
    }

    /** Substring search that won't match inside a longer word. */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int after = at + needle.length();
            boolean endOk = after >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(after));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /** The biome a critter belongs to, or null when unknown. */
    public static String biomeOf(String critter) {
        String canonical = canonical(critter);
        if (canonical == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : BY_BIOME.entrySet()) {
            if (entry.getValue().contains(canonical)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
