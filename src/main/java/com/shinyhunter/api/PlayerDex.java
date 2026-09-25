package com.shinyhunter.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.shinyhunter.CritterDex;
import com.shinyhunter.ShinyConfig;
import com.shinyhunter.ShinyHunterClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's Hunting level and Sparkling Critterdex, read out of a {@code /skyblock/profiles}
 * response.
 *
 * <p><b>The dex field is discovered, not hard-coded.</b> Critter Safari is new enough that nothing
 * documents where the Sparkling Critterdex lives in the profile JSON. Rather than guess a path and
 * silently show 0/37 forever when the guess is wrong, the parser walks the member object looking
 * for any subtree under a key containing "sparkl" whose keys or values are critter names, and
 * takes the one that matches the most. Every candidate it considered is kept for
 * {@code /shiny dex}, so the real path can be read off one lookup and pinned in the config —
 * after which the walk is skipped.
 *
 * @param name            account name as looked up
 * @param huntingLevel    from Hunting skill XP, or -1 when the profile has no Hunting data
 * @param caught          canonical critter names whose sparkling has been caught
 * @param dexPath         where the dex was read from, for diagnostics
 * @param candidates      every path considered, with how many critter names it matched
 * @param totalCaptured   sparklings caught in total, duplicates included; -1 when absent
 * @param safariEssence   Safari Essence in the purse; -1 when absent
 */
public record PlayerDex(String name, int huntingLevel, double huntingLevelExact, Set<String> caught,
                        String dexPath, List<String> candidates, Map<String, Integer> tickets,
                        Map<String, Integer> biomeCaptures, int totalCaptured, int safariEssence) {

    /**
     * Hypixel's own colour for each Safari biome, read off its milestone messages
     * ("You have reached Milestone 1 for the §5Haunted Biome").
     */
    private static final Map<String, String> BIOME_COLOURS = Map.of(
            "forest", "\u00a72", "cavern", "\u00a76", "icy", "\u00a79", "haunted", "\u00a75");

    /**
     * Skyblock's skill XP per level, 1 to 60 — the table every mainline skill shares (SkyCrypt's
     * {@code LEVELING_XP}). Hunting is assumed to share it too.
     */
    private static final long[] PER_LEVEL_XP = {
            50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500,
            5000, 7500, 10000, 15000, 20000, 30000, 50000, 75000, 100000, 200000,
            300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000,
            1300000, 1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000,
            2300000, 2400000, 2500000, 2600000, 2750000, 2900000, 3100000, 3400000, 3700000, 4000000,
            4300000, 4600000, 4900000, 5200000, 5500000, 5800000, 6100000, 6400000, 6700000, 7000000};

    /** Ticket tiers in the API, in display order, and the rarity colour each is shown in. */
    public static final String[] TICKET_TIERS = {"basic", "economy", "premium", "first_class"};
    public static final String[] TICKET_COLOURS = {"\u00a7a", "\u00a79", "\u00a75", "\u00a76"};

    public int total() {
        return CritterDex.total();
    }

    /** Critters whose sparkling this player has NOT caught. */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        for (String biome : CritterDex.biomes()) {
            for (String critter : CritterDex.critters(biome)) {
                if (!caught.contains(critter)) {
                    out.add(critter);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Reads the selected profile's member entry for the given UUID-less name. The member is found
     * by matching the looked-up UUID, which the caller doesn't have here — so the selected profile's
     * members are scanned for the one whose Hunting data exists, falling back to the first.
     */
    public static PlayerDex from(String name, String uuid, JsonObject profiles) {
        JsonObject member = selectedMember(profiles, uuid);
        if (member == null) {
            ShinyHunterClient.LOGGER.info("No profile member found for {}", name);
            return new PlayerDex(name, -1, -1, Set.of(), "", List.of(), Map.of(), Map.of(), -1, -1);
        }

        double exact = huntingLevelExact(member);
        int level = exact < 0 ? -1 : (int) Math.floor(exact);
        Map<String, Integer> tickets = intMap(navigate(member, "safari.tickets"));
        Map<String, Integer> biomes = intMap(navigate(member, "safari.biome_captures"));
        JsonElement essenceNode = navigate(member, "currencies.essence.SAFARI.current");
        int safariEssence = essenceNode != null && essenceNode.isJsonPrimitive()
                && essenceNode.getAsJsonPrimitive().isNumber() ? essenceNode.getAsInt() : -1;
        JsonElement totalNode = navigate(member, "safari.total_captured_sparkling_critters");
        int totalCaptured = totalNode != null && totalNode.isJsonPrimitive()
                && totalNode.getAsJsonPrimitive().isNumber() ? totalNode.getAsInt() : -1;

        String override = ShinyConfig.get().sparklingDexPath;
        List<String> candidates = new ArrayList<>();
        Set<String> caught;
        String path;
        JsonElement pinned = override == null || override.isBlank() ? null : navigate(member, override.trim());
        if (pinned != null) {
            caught = critterNamesIn(pinned);
            path = override.trim();
            candidates.add(path + " (pinned) -> " + caught.size());
        } else {
            // The pinned path is missing from this profile — fall back to searching for it, so a
            // renamed field degrades to "found it somewhere else" rather than "0/37 for everyone".
            Found best = discover(member, "", candidates);
            caught = best == null ? Set.of() : best.names;
            path = best == null ? "" : best.path;
        }

        ShinyHunterClient.LOGGER.info("{}: Hunting {}, sparklings {}/{} via '{}'",
                name, level, caught.size(), CritterDex.total(), path.isEmpty() ? "nothing found" : path);
        return new PlayerDex(name, level, exact, caught, path, candidates, tickets, biomes,
                totalCaptured, safariEssence);
    }

    /** An object of numbers as a map; empty for anything else. */
    private static Map<String, Integer> intMap(JsonElement node) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (node == null || !node.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.put(entry.getKey(), value.getAsInt());
            }
        }
        return out;
    }

    public int ticketsTotal() {
        int total = 0;
        for (int n : tickets.values()) {
            total += n;
        }
        return total;
    }

    /** {@code Tickets: 3 | 1 | 0 | 2 | (6)}, each tier in its rarity colour. */
    public String ticketsLine() {
        StringBuilder line = new StringBuilder("\u00a77Tickets: ");
        for (int i = 0; i < TICKET_TIERS.length; i++) {
            if (i > 0) {
                line.append(" \u00a78| ");
            }
            line.append(TICKET_COLOURS[i]).append(tickets.getOrDefault(TICKET_TIERS[i], 0));
        }
        line.append(" \u00a78| \u00a7b(").append(ticketsTotal()).append(")");
        return line.toString();
    }

    /** {@code Captures: Forest 120 · Icy 98 · Cavern 77 · Haunted 64} in dex biome order. */
    public String capturesLine() {
        StringBuilder line = new StringBuilder("\u00a77Captures: ");
        boolean first = true;
        for (String biome : CritterDex.biomes()) {
            Integer n = biomeCaptures.get(biome.toLowerCase());
            if (n == null) {
                continue;
            }
            if (!first) {
                line.append(" \u00a78\u00b7 ");
            }
            first = false;
            line.append(BIOME_COLOURS.getOrDefault(biome.toLowerCase(), "\u00a7f"))
                    .append(biome).append(" \u00a7e").append(n);
        }
        return first ? "" : line.toString();
    }

    /** {@code Safari Essence: 12,212}. Empty when the profile doesn't carry it. */
    public String essenceLine() {
        return safariEssence < 0 ? ""
                : "§7Safari Essence: §d" + String.format("%,d", safariEssence);
    }

    /** Sparklings caught in total, and how many of those were repeats. Empty when unknown. */
    public String totalsLine() {
        if (totalCaptured < 0) {
            return "";
        }
        int duplicates = Math.max(0, totalCaptured - caught.size());
        return "\u00a77Total sparklings: \u00a7f" + totalCaptured
                + " \u00a78(" + duplicates + " duplicate" + (duplicates == 1 ? "" : "s") + ")";
    }

    /** "50" or "80.16" — two decimals only once past the table's end, where fractions matter. */
    public String levelText() {
        if (huntingLevelExact < 0) {
            return "?";
        }
        return huntingLevelExact > PER_LEVEL_XP.length
                ? String.format("%.2f", huntingLevelExact)
                : Integer.toString(huntingLevel);
    }

    private static JsonObject selectedMember(JsonObject profiles, String uuid) {
        JsonElement list = profiles.get("profiles");
        if (list == null || !list.isJsonArray()) {
            return null;
        }
        JsonObject selected = null;
        for (JsonElement element : list.getAsJsonArray()) {
            JsonObject profile = element.getAsJsonObject();
            JsonElement flag = profile.get("selected");
            if (flag != null && flag.isJsonPrimitive() && flag.getAsBoolean()) {
                selected = profile;
                break;
            }
            if (selected == null) {
                selected = profile;
            }
        }
        if (selected == null) {
            return null;
        }
        JsonElement members = selected.get("members");
        if (members == null || !members.isJsonObject()) {
            return null;
        }
        JsonElement mine = members.getAsJsonObject().get(uuid);
        return mine != null && mine.isJsonObject() ? mine.getAsJsonObject() : null;
    }

    private static double huntingLevelExact(JsonObject member) {
        JsonElement xp = navigate(member, "player_data.experience.SKILL_HUNTING");
        if (xp == null || !xp.isJsonPrimitive()) {
            return -1;
        }
        return levelFromXp(xp.getAsDouble());
    }

    /**
     * Fractional level for a total XP, with overflow past the table.
     *
     * <p>Overflow follows SkyHanni's rule, read from its bytecode: after level 60 each level costs
     * the previous level's XP plus a slope that starts at 600,000 and doubles every ten levels.
     * That's what the in-game overflow displays people compare against, so it's what this matches.
     */
    static double levelFromXp(double xp) {
        int level = 0;
        double remaining = Math.max(0, xp);
        for (long need : PER_LEVEL_XP) {
            if (remaining < need) {
                return level + remaining / need;
            }
            remaining -= need;
            level++;
        }
        long slope = 600_000L;
        long need = 7_000_000L + slope;
        while (remaining >= need) {
            remaining -= need;
            level++;
            if (level % 10 == 0) {
                slope *= 2;
            }
            need += slope;
        }
        return level + remaining / need;
    }

    /** Follows a dotted path through nested objects; null when any step is missing. */
    static JsonElement navigate(JsonElement root, String dotted) {
        JsonElement current = root;
        for (String step : dotted.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(step);
        }
        return current;
    }

    // ------------------------------------------------------------------ discovery

    private record Found(String path, Set<String> names) {
    }

    /**
     * Depth-first over the member object. Any subtree under a key containing "sparkl" is scored by
     * how many critter names it contains; the best score wins. Subtrees under "critter" without
     * "sparkl" are listed as candidates too — that's the ordinary Critterdex, and seeing it in the
     * candidate list is how you'd tell the two apart if the sparkling one is named unexpectedly.
     */
    private static Found discover(JsonElement node, String path, List<String> candidates) {
        Found best = null;
        if (!node.isJsonObject()) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String childPath = path.isEmpty() ? key : path + "." + key;
            String lower = key.toLowerCase();

            if (lower.contains("sparkl") || lower.contains("critter") || lower.contains("safari")) {
                Set<String> names = critterNamesIn(entry.getValue());
                candidates.add(childPath + " -> " + names.size() + " critter name(s)");
                if (lower.contains("sparkl") && !names.isEmpty()
                        && (best == null || names.size() > best.names.size())) {
                    best = new Found(childPath, names);
                }
            }

            Found deeper = discover(entry.getValue(), childPath, candidates);
            if (deeper != null && (best == null || deeper.names.size() > best.names.size())) {
                best = deeper;
            }
        }
        return best;
    }

    /**
     * Every critter name found in a subtree, as canonical dex names. Keys and string values both
     * count — an object keyed by critter id and an array of ids are both plausible shapes — and a
     * key mapping to a falsy value ({@code false}, {@code 0}) is treated as not caught.
     */
    static Set<String> critterNamesIn(JsonElement node) {
        Set<String> names = new LinkedHashSet<>();
        collectNames(node, names);
        return names;
    }

    private static void collectNames(JsonElement node, Set<String> into) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                String critter = asCritter(entry.getKey());
                if (critter != null) {
                    if (!isFalsy(entry.getValue())) {
                        into.add(critter);
                    }
                    continue;
                }
                collectNames(entry.getValue(), into);
            }
        } else if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String critter = asCritter(element.getAsString());
                    if (critter != null) {
                        into.add(critter);
                    }
                } else {
                    collectNames(element, into);
                }
            }
        }
    }

    private static boolean isFalsy(JsonElement value) {
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return !primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsDouble() <= 0;
            }
        }
        return value.isJsonNull() || (value.isJsonArray() && value.getAsJsonArray().isEmpty());
    }

    /** "MANTIS_SHRIMP", "mantisShrimp", "Mantis Shrimp" all resolve to the dex name; else null. */
    static String asCritter(String raw) {
        String squashed = raw.toLowerCase().replaceAll("[^a-z]", "");
        if (squashed.isEmpty()) {
            return null;
        }
        for (String biome : CritterDex.biomes()) {
            for (String critter : CritterDex.critters(biome)) {
                if (critter.toLowerCase().replaceAll("[^a-z]", "").equals(squashed)) {
                    return critter;
                }
            }
        }
        return null;
    }

    /** For tests and diagnostics: the array form of a discovered value. */
    static JsonArray asArray(JsonElement e) {
        return e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
