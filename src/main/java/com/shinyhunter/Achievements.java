package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shinyhunter.api.PlayerDex;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Achievements, in the style of SkyBlock Overhaul's: each has a rarity, unlocks once with a chat
 * line and a sound, and some are secret until unlocked.
 *
 * <p>Most are worked out from what's already recorded — the run history, the sparkling history and
 * the ticket counter — so they're re-checked whenever one of those changes. The rest are events
 * seen in chat (an Emerald Dye, an emblem). The counters the history doesn't keep (your own dry
 * streaks, runs since a Macaw) live in {@code config/shinyhunter-achievements.json} with the
 * unlock times.
 *
 * <p>The first time this runs it unlocks whatever the existing history already earns, quietly, and
 * says how many in one line, rather than playing forty fanfares at once.
 */
public final class Achievements {

    /** Rarity, lowest first, with the colour it's shown in. */
    public enum Tier {
        COMMON("§f", 0xFFFFFFFF), UNCOMMON("§a", 0xFF55FF55), RARE("§9", 0xFF5555FF),
        EPIC("§5", 0xFFAA00AA), LEGENDARY("§6", 0xFFFFAA00), MYTHIC("§d", 0xFFFF55FF),
        DIVINE("§b", 0xFF55FFFF);

        public final String code;
        public final int argb;

        Tier(String code, int argb) {
            this.code = code;
            this.argb = argb;
        }
    }

    /** What an achievement measures; goal-type ones show a progress bar. */
    public enum Measure {
        EVENT, RUNS, HOURS, CAUGHT, COINS, OWN_SPARKLINGS, UNIQUES, DRY_SPARKLING, DRY_UNIQUE,
        RUNS_SINCE_MACAW, CONTESTS, EMBLEMS
    }

    /** One achievement. */
    public record Def(String id, String name, String description, Tier tier, boolean secret,
                      Measure measure, long goal) {
        public boolean hasProgress() {
            return measure != Measure.EVENT && goal > 1;
        }
    }

    private static final List<Def> ALL = new ArrayList<>();

    private static Def add(String id, String name, String description, Tier tier, Measure measure, long goal) {
        Def def = new Def(id, name, description, tier, false, measure, goal);
        ALL.add(def);
        return def;
    }

    private static Def secret(String id, String name, String description, Tier tier) {
        Def def = new Def(id, name, description, tier, true, Measure.EVENT, 1);
        ALL.add(def);
        return def;
    }

    private static Def event(String id, String name, String description, Tier tier) {
        return add(id, name, description, tier, Measure.EVENT, 1);
    }

    // ---- sparkling luck
    static final Def FIRST_SPARKLE = event("first_sparkle", "First Sparkle", "Catch your first sparkling critter.", Tier.COMMON);
    static final Def PARTY_DOUBLE = event("party_double", "Shared Shine", "Your party catches 2 sparklings in one run.", Tier.RARE);
    static final Def PARTY_TRIPLE = event("party_triple", "Sparkle Storm", "Your party catches 3 sparklings in one run.", Tier.LEGENDARY);
    static final Def SPARKLE_10 = add("sparkle_10", "Glimmer", "Catch 10 sparklings.", Tier.UNCOMMON, Measure.OWN_SPARKLINGS, 10);
    static final Def SPARKLE_25 = add("sparkle_25", "Gleam", "Catch 25 sparklings.", Tier.RARE, Measure.OWN_SPARKLINGS, 25);
    static final Def SPARKLE_50 = add("sparkle_50", "Radiance", "Catch 50 sparklings.", Tier.EPIC, Measure.OWN_SPARKLINGS, 50);
    static final Def SPARKLE_100 = add("sparkle_100", "Supernova", "Catch 100 sparklings.", Tier.LEGENDARY, Measure.OWN_SPARKLINGS, 100);
    static final Def UNIQUE_10 = add("unique_10", "Collector", "Have 10 different sparklings.", Tier.UNCOMMON, Measure.UNIQUES, 10);
    static final Def UNIQUE_20 = add("unique_20", "Curator", "Have 20 different sparklings.", Tier.RARE, Measure.UNIQUES, 20);
    static final Def UNIQUE_30 = add("unique_30", "Archivist", "Have 30 different sparklings.", Tier.EPIC, Measure.UNIQUES, 30);
    static final Def MAX_SPARKLINGS = add("max_sparklings", "Max Sparklings", "Have every sparkling critter.", Tier.DIVINE, Measure.UNIQUES, 37);
    static final Def LEGENDARY_SPARKLE = event("legendary_sparkle", "Legend in Lights", "Catch a sparkling Macaw, Wumpa or Doomspiral.", Tier.LEGENDARY);
    static final Def GEMZIE_SPARKLE = event("gemzie_sparkle", "Gem of a Gemzie", "Catch a sparkling Gemzie.", Tier.EPIC);
    static final Def SET_CAVERN = event("set_cavern", "Cavern Glitter", "Have every Cavern sparkling.", Tier.EPIC);
    static final Def SET_FOREST = event("set_forest", "Forest Glitter", "Have every Forest sparkling.", Tier.EPIC);
    static final Def SET_HAUNTED = event("set_haunted", "Haunted Glitter", "Have every Haunted sparkling.", Tier.EPIC);
    static final Def SET_ICY = event("set_icy", "Icy Glitter", "Have every Icy sparkling.", Tier.EPIC);

    // ---- unlucky
    static final Def DRY_10 = add("dry_runs_10", "Unlucky", "10 runs in a row without catching a sparkling.", Tier.UNCOMMON, Measure.DRY_SPARKLING, 10);
    static final Def DRY_20 = add("dry_runs_20", "Very Unlucky", "20 runs in a row without catching a sparkling.", Tier.RARE, Measure.DRY_SPARKLING, 20);
    static final Def DRY_30 = add("dry_runs_30", "Cursed", "30 runs in a row without catching a sparkling.", Tier.EPIC, Measure.DRY_SPARKLING, 30);
    static final Def DRY_40 = add("dry_runs_40", "Forsaken", "40 runs in a row without catching a sparkling.", Tier.LEGENDARY, Measure.DRY_SPARKLING, 40);
    static final Def DRY_50 = add("dry_runs_50", "Doomed", "50 runs in a row without catching a sparkling.", Tier.MYTHIC, Measure.DRY_SPARKLING, 50);
    static final Def NO_UNIQUE_5 = add("no_unique_runs_5", "Same Old Sparkle", "5 runs in a row without a new unique sparkling.", Tier.RARE, Measure.DRY_UNIQUE, 5);
    static final Def NO_UNIQUE_10 = add("no_unique_runs_10", "Déjà Vu", "10 runs in a row without a new unique sparkling.", Tier.EPIC, Measure.DRY_UNIQUE, 10);
    static final Def NO_UNIQUE_25 = add("no_unique_runs_25", "Groundhog Day", "25 runs in a row without a new unique sparkling.", Tier.LEGENDARY, Measure.DRY_UNIQUE, 25);
    static final Def NO_UNIQUE_50 = add("no_unique_runs_50", "Broken Record", "50 runs in a row without a new unique sparkling.", Tier.MYTHIC, Measure.DRY_UNIQUE, 50);

    // ---- Macaw
    static final Def MACAW_3 = event("macaw_3", "Bird Watcher", "Your party secures 3 Macaws in one run.", Tier.RARE);
    static final Def MACAW_4 = event("macaw_4", "Flock Together", "Your party secures 4 Macaws in one run.", Tier.EPIC);
    static final Def MACAW_8 = event("macaw_8", "Macaw Mania", "Your party secures 8 Macaws in one run.", Tier.LEGENDARY);
    static final Def NO_MACAW_3 = add("no_macaw_3", "Where's the Macaw?", "3 runs in a row without a Macaw.", Tier.UNCOMMON, Measure.RUNS_SINCE_MACAW, 3);
    static final Def NO_MACAW_5 = add("no_macaw_5", "Macaw Drought", "5 runs in a row without a Macaw.", Tier.RARE, Measure.RUNS_SINCE_MACAW, 5);
    static final Def NO_MACAW_8 = add("no_macaw_8", "Extinct", "8 runs in a row without a Macaw.", Tier.EPIC, Measure.RUNS_SINCE_MACAW, 8);

    // ---- drops and emblems
    static final Def EMERALD_DYE = event("emerald_dye", "Emerald Dye", "Find an Emerald Dye.", Tier.DIVINE);
    static final Def FIRST_EMBLEM = add("emblem_1", "Emblematic", "Unlock an emblem.", Tier.RARE, Measure.EMBLEMS, 1);
    static final Def EMBLEMS_5 = add("emblem_5", "Emblem Collector", "Unlock 5 emblems.", Tier.LEGENDARY, Measure.EMBLEMS, 5);

    // ---- runs
    static final Def RUN_10 = add("run_10", "Safari Rookie", "Finish 10 Safari runs.", Tier.COMMON, Measure.RUNS, 10);
    static final Def RUN_50 = add("run_50", "Regular", "Finish 50 Safari runs.", Tier.UNCOMMON, Measure.RUNS, 50);
    static final Def RUN_100 = add("run_100", "Ranger", "Finish 100 Safari runs.", Tier.RARE, Measure.RUNS, 100);
    static final Def RUN_500 = add("run_500", "Warden of the Wilds", "Finish 500 Safari runs.", Tier.EPIC, Measure.RUNS, 500);
    static final Def HOURS_10 = add("hours_10", "Time Flies", "Spend 10 hours on Safari runs.", Tier.UNCOMMON, Measure.HOURS, 10);
    static final Def HOURS_50 = add("hours_50", "No Life", "Spend 50 hours on Safari runs.", Tier.EPIC, Measure.HOURS, 50);
    static final Def CAUGHT_1000 = add("party_caught_1000", "Catcher", "Your party catches 1,000 critters with you.", Tier.UNCOMMON, Measure.CAUGHT, 1000);
    static final Def CAUGHT_5000 = add("party_caught_5000", "Trapper", "Your party catches 5,000 critters with you.", Tier.RARE, Measure.CAUGHT, 5000);
    static final Def CAUGHT_10000 = add("party_caught_10000", "Exterminator", "Your party catches 10,000 critters with you.", Tier.EPIC, Measure.CAUGHT, 10000);
    static final Def CAUGHT_25000 = add("party_caught_25000", "Critter Apocalypse", "Your party catches 25,000 critters with you.", Tier.LEGENDARY, Measure.CAUGHT, 25000);
    static final Def CAUGHT_50000 = add("party_caught_50000", "Extinction Event", "Your party catches 50,000 critters with you.", Tier.MYTHIC, Measure.CAUGHT, 50000);
    static final Def COINS_100M = add("coins_100m", "Shard Shark", "Earn 100M coins of shards from runs.", Tier.RARE, Measure.COINS, 100_000_000L);
    static final Def COINS_1B = add("coins_1b", "Shard Tycoon", "Earn 1B coins of shards from runs.", Tier.LEGENDARY, Measure.COINS, 1_000_000_000L);
    static final Def FULL_HOUSE = event("full_house", "Full House", "Your party secures all 37 critters in a run.", Tier.UNCOMMON);
    static final Def SPEED_3 = event("speed_3", "Speedrunner", "Secure all 37 critters in under 3:00.", Tier.EPIC);
    static final Def SPEED_230 = event("speed_230", "Any%", "Secure all 37 critters in under 2:30.", Tier.LEGENDARY);
    static final Def TEN_OF_37 = event("ten_of_37", "Pulling Your Weight", "Catch 10 different critters yourself in a run.", Tier.RARE);
    static final Def CONTESTS_10 = add("contests_10", "Contestant", "Complete 10 of Miria's contests.", Tier.UNCOMMON, Measure.CONTESTS, 10);
    static final Def CONTESTS_50 = add("contests_50", "Honey Hoarder", "Complete 50 of Miria's contests.", Tier.RARE, Measure.CONTESTS, 50);
    static final Def CONTESTS_100 = add("contests_100", "Miria's Favourite", "Complete 100 of Miria's contests.", Tier.EPIC, Measure.CONTESTS, 100);

    // ---- secrets
    static final Def NIGHT_OWL = secret("night_owl", "Night Owl", "Finish a run between 3 and 5 in the morning.", Tier.UNCOMMON);
    static final Def DEDICATION = secret("dedication", "Dedication", "Finish 50 runs in one day.", Tier.RARE);
    static final Def BACK_TO_BACK = secret("back_to_back", "Back to Back", "Catch a sparkling in two runs in a row.", Tier.LEGENDARY);
    static final Def INSTANT = secret("instant", "Instant Gratification", "Catch a sparkling within 5 tickets of your last one.", Tier.EPIC);

    /** Emerald Dye drop lines: "RARE DROP! Emerald Dye", "WOW! You found Emerald Dye". */
    private static final Pattern DROP_PREFIX = Pattern.compile(
            "^(?:[A-Z!' ]*DROP!|WOW!|RNG METER|NICE!)", Pattern.CASE_INSENSITIVE);

    /** An emblem unlock, worded either way round. */
    private static final Pattern[] EMBLEM_LINES = {
            Pattern.compile("(?i)\\bemblem unlocked!?\\s*[:\\-]?\\s*(?:the\\s+)?(.+?)(?:\\s+emblem)?[.!]?$"),
            Pattern.compile("(?i)\\b(?:unlocked|earned|obtained|received)\\s+(?:the\\s+|an?\\s+)?(.+?)\\s+emblem\\b"),
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The saved file. */
    private static final class Data {
        boolean initialised;
        Map<String, Long> unlocked = new LinkedHashMap<>();
        /** Emblem name -> when it was unlocked. */
        Map<String, Long> emblems = new LinkedHashMap<>();
        long ticketsSinceOwnSparkling;
        long ticketsSinceOwnUnique;
        long longestDrySparkling;
        long longestDryUnique;
        int runsSinceMacaw;
        int longestRunsWithoutMacaw;
        int contestsCompleted;
        /** Whether the previous run had a sparkling of yours, for Back to Back. */
        boolean lastRunHadOwnSparkling;
        /** Runs in a row without your sparkling / your new unique, and the longest of each. */
        int runsSinceOwnSparkling;
        int runsSinceOwnUnique;
        int longestRunsWithoutSparkling;
        int longestRunsWithoutUnique;
        /** A new unique was confirmed since the last run ended (the API can answer late). */
        boolean uniqueSinceLastRun;
        /** Whether the run counters have been seeded from the ticket counters (one ticket per run). */
        boolean runCountersSeeded;
        /** Which version of the achievement list the unlocks were last checked against. */
        int listVersion;
    }

    private static Data data = new Data();
    private static int tickCounter;

    private Achievements() {
    }

    public static void register() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
    }

    // ------------------------------------------------------------------ events

    public static void onClientTick(Minecraft client) {
        if (client.player == null || !ShinyConfig.get().achievementsEnabled) {
            return;
        }
        if (!data.initialised) {
            initialise(client);
            return;
        }
        if (!data.runCountersSeeded) {
            seedRunCounters();
            checkAll(true);
        }
        if (data.listVersion < LIST_VERSION) {
            relist(client);
        }
        // The party dex lookups can arrive any time; that's where your full sparkling dex comes from.
        if (++tickCounter >= 200) {
            tickCounter = 0;
            checkAll(true);
        }
    }

    public static void onTicket() {
        data.ticketsSinceOwnSparkling++;
        data.ticketsSinceOwnUnique++;
        data.longestDrySparkling = Math.max(data.longestDrySparkling, data.ticketsSinceOwnSparkling);
        data.longestDryUnique = Math.max(data.longestDryUnique, data.ticketsSinceOwnUnique);
        save();
        checkAll(true);
    }

    public static void onSparkling(SparklingHistory.Entry entry, boolean own) {
        if (own) {
            if (data.ticketsSinceOwnSparkling <= 5 && ownEntries().size() > 1) {
                unlock(INSTANT, true);
            }
            data.ticketsSinceOwnSparkling = 0;
            data.runsSinceOwnSparkling = 0;
            String critter = entry.critter;
            if (critter.equals("Macaw") || critter.equals("Wumpa") || critter.equals("Doomspiral")) {
                unlock(LEGENDARY_SPARKLE, true);
            }
            if (critter.equals("Gemzie")) {
                unlock(GEMZIE_SPARKLE, true);
            }
            save();
        }
        RunHistory.Run run = RunHistory.current();
        if (run != null) {
            checkRunSparklings(run, true);
        }
        checkAll(true);
    }

    /** A catch confirmed as a new unique — only known once the API has answered. */
    public static void onUnique(SparklingHistory.Entry entry) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && entry.player.equalsIgnoreCase(client.player.getName().getString())) {
            data.ticketsSinceOwnUnique = 0;
            data.runsSinceOwnUnique = 0;
            data.uniqueSinceLastRun = true;
            save();
        }
        checkAll(true);
    }

    public static void onMacaw(RunHistory.Run run) {
        checkMacaws(run, true);
    }

    public static void onRunEnd(RunHistory.Run run) {
        if (run.macaws > 0) {
            data.runsSinceMacaw = 0;
        } else {
            data.runsSinceMacaw++;
            data.longestRunsWithoutMacaw = Math.max(data.longestRunsWithoutMacaw, data.runsSinceMacaw);
        }
        boolean ownSparkling = run.ownSparklings > 0;
        if (!ownSparkling) {
            data.runsSinceOwnSparkling++;
            data.longestRunsWithoutSparkling = Math.max(data.longestRunsWithoutSparkling, data.runsSinceOwnSparkling);
        }
        if (!data.uniqueSinceLastRun) {
            data.runsSinceOwnUnique++;
            data.longestRunsWithoutUnique = Math.max(data.longestRunsWithoutUnique, data.runsSinceOwnUnique);
        }
        data.uniqueSinceLastRun = false;
        if (ownSparkling && data.lastRunHadOwnSparkling) {
            unlock(BACK_TO_BACK, true);
        }
        data.lastRunHadOwnSparkling = ownSparkling;
        save();
        checkRun(run, true);
        checkAll(true);
    }

    public static void onContestCompleted() {
        data.contestsCompleted++;
        save();
        checkAll(true);
    }

    private static void handle(Component message) {
        if (!ShinyConfig.get().achievementsEnabled) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || text.startsWith("[Shiny Hunter]") || HotspotAnnouncer.isRelayed(text)) {
            return;
        }
        if (text.contains("Emerald Dye") && isOwnDrop(text)) {
            ShinyHunterClient.LOGGER.info("Emerald Dye drop seen: {}", text);
            unlock(EMERALD_DYE, true);
        }
        if (text.toLowerCase(Locale.ROOT).contains("emblem")) {
            for (Pattern pattern : EMBLEM_LINES) {
                Matcher m = pattern.matcher(text);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && name.length() <= 32 && !data.emblems.containsKey(name)) {
                        data.emblems.put(name, System.currentTimeMillis());
                        save();
                        ShinyHunterClient.LOGGER.info("Emblem unlocked: {} (from \"{}\")", name, text);
                        announce(new Def("emblem:" + name, name + " Emblem", "Unlock the " + name + " emblem.",
                                Tier.EPIC, false, Measure.EVENT, 1));
                        checkAll(true);
                    }
                    break;
                }
            }
        }
    }

    /** Your drop, not a broadcast of somebody else's ("WOW! Name found Emerald Dye"). */
    private static boolean isOwnDrop(String text) {
        if (!DROP_PREFIX.matcher(text).find() && !text.contains("You found")) {
            return false;
        }
        int found = text.indexOf(" found ");
        if (found < 0) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        String before = text.substring(0, found);
        return before.endsWith("You") || client.player != null
                && before.endsWith(client.player.getName().getString());
    }

    // ------------------------------------------------------------------ checking

    /** Checks every measured achievement against the current numbers. */
    private static void checkAll(boolean announce) {
        if (!data.initialised) {
            return;
        }
        for (Def def : ALL) {
            if (def.measure() != Measure.EVENT && !isUnlocked(def) && progress(def) >= def.goal()) {
                unlock(def, announce);
            }
        }
        Set<String> dex = ownDex();
        if (!dex.isEmpty()) {
            unlockIf(dex.size() >= 1, FIRST_SPARKLE, announce);
            unlockIf(dex.containsAll(CritterDex.critters("Cavern")), SET_CAVERN, announce);
            unlockIf(dex.containsAll(CritterDex.critters("Forest")), SET_FOREST, announce);
            unlockIf(dex.containsAll(CritterDex.critters("Haunted")), SET_HAUNTED, announce);
            unlockIf(dex.containsAll(CritterDex.critters("Icy")), SET_ICY, announce);
        }
    }

    private static void checkRun(RunHistory.Run run, boolean announce) {
        checkRunSparklings(run, announce);
        checkMacaws(run, announce);
        int total = CritterDex.total();
        unlockIf(run.party >= total, FULL_HOUSE, announce);
        unlockIf(run.clocked && run.party >= total && run.length < 180_000L, SPEED_3, announce);
        unlockIf(run.clocked && run.party >= total && run.length < 150_000L, SPEED_230, announce);
        unlockIf(run.catches.size() >= 10 || run.you >= 10, TEN_OF_37, announce);
        int hour = Instant.ofEpochMilli(run.start + run.length).atZone(ZoneId.systemDefault()).getHour();
        unlockIf(hour >= 3 && hour < 5, NIGHT_OWL, announce);

        LocalDate day = Instant.ofEpochMilli(run.start).atZone(ZoneId.systemDefault()).toLocalDate();
        int sameDay = 0;
        for (RunHistory.Run other : RunHistory.runs()) {
            if (Instant.ofEpochMilli(other.start).atZone(ZoneId.systemDefault()).toLocalDate().equals(day)) {
                sameDay++;
            }
        }
        unlockIf(sameDay >= 50, DEDICATION, announce);
    }

    private static void checkRunSparklings(RunHistory.Run run, boolean announce) {
        unlockIf(run.sparklings.size() >= 2, PARTY_DOUBLE, announce);
        unlockIf(run.sparklings.size() >= 3, PARTY_TRIPLE, announce);
    }

    private static void checkMacaws(RunHistory.Run run, boolean announce) {
        unlockIf(run.macaws >= 3, MACAW_3, announce);
        unlockIf(run.macaws >= 4, MACAW_4, announce);
        unlockIf(run.macaws >= 8, MACAW_8, announce);
    }

    private static void unlockIf(boolean condition, Def def, boolean announce) {
        if (condition) {
            unlock(def, announce);
        }
    }

    /** The number an achievement's goal is measured against. */
    public static long progress(Def def) {
        return switch (def.measure()) {
            case EVENT -> isUnlocked(def) ? 1 : 0;
            case RUNS -> RunHistory.runs().size();
            case HOURS -> PlaytimeReader.safariMillis() / 3_600_000L;
            case CAUGHT -> {
                // Yours plus your party's: a teammate's catch reaches you as a loot share.
                long caught = 0;
                for (RunHistory.Run run : RunHistory.runs()) {
                    caught += run.caught + run.lootShares;
                }
                yield caught;
            }
            case COINS -> {
                long coins = 0;
                for (RunHistory.Run run : RunHistory.runs()) {
                    coins += Math.max(0, run.coins);
                }
                yield coins;
            }
            case OWN_SPARKLINGS -> ownEntries().size();
            case UNIQUES -> ownDex().size();
            case DRY_SPARKLING -> Math.max(data.longestRunsWithoutSparkling, data.runsSinceOwnSparkling);
            case DRY_UNIQUE -> Math.max(data.longestRunsWithoutUnique, data.runsSinceOwnUnique);
            case RUNS_SINCE_MACAW -> Math.max(data.longestRunsWithoutMacaw, data.runsSinceMacaw);
            case CONTESTS -> data.contestsCompleted;
            case EMBLEMS -> data.emblems.size();
        };
    }


    // ------------------------------------------------------------------ unlocking

    private static void unlock(Def def, boolean announce) {
        if (isUnlocked(def)) {
            return;
        }
        data.unlocked.put(def.id(), System.currentTimeMillis());
        save();
        ShinyHunterClient.LOGGER.info("Achievement unlocked: {} ({})", def.name(), def.tier());
        if (announce) {
            announce(def);
        }
    }

    /**
     * {@code ACHIEVEMENT UNLOCKED! Macaw Mania} in the tier's colour, the description on hover,
     * and the challenge-complete sound. Added straight to the chat box, so it can't re-enter this
     * mod's own chat listeners.
     */
    private static void announce(Def def) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        ShinyConfig config = ShinyConfig.get();
        Component hover = Component.literal(def.tier().code + "§l" + def.name() + "\n§7" + def.description()
                + "\n\n" + def.tier().code + "§l" + def.tier().name() + " ACHIEVEMENT");
        MutableComponent line = Component.literal("§b[Shiny Hunter] §6§lACHIEVEMENT UNLOCKED! ")
                .append(Component.literal(def.tier().code + "§l" + def.name())
                        .withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover))));
        client.gui.getChat().addClientSystemMessage(line);
        client.gui.getChat().addClientSystemMessage(Component.literal("  §7" + def.description()
                + " §8(" + def.tier().code + def.tier().name() + "§8)"));
        if (config.achievementSound) {
            // Mythic and Divine get the challenge-advancement fanfare ("How Did We Get Here?");
            // the rest a lighter chime, so the big ones stand out.
            if (def.tier().ordinal() >= Tier.MYTHIC.ordinal()) {
                client.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else {
                client.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.6f, 1.6f);
            }
        }
        if (def.tier().ordinal() >= Tier.LEGENDARY.ordinal()) {
            client.gui.setTimes(5, 50, 15);
            client.gui.setSubtitle(Component.literal(def.tier().code + def.name()));
            client.gui.setTitle(Component.literal("§6§lACHIEVEMENT"));
        }
        if (config.achievementsToParty) {
            PartyChat.send(config.critterChannel, "Achievement unlocked: " + def.name() + "!");
        }
    }

    /**
     * First run: unlock what the existing history already earns, without a fanfare for each. The
     * dry-streak counters start from the ticket counter, and the no-unique streak adds the tickets
     * of every repeat caught since your last unique.
     */
    private static void initialise(Minecraft client) {
        data.initialised = true;
        ShinyConfig config = ShinyConfig.get();
        data.ticketsSinceOwnSparkling = config.ticketsSinceSparkling;
        long sinceUnique = config.ticketsSinceSparkling;
        long longest = 0;
        for (SparklingHistory.Entry entry : ownEntries()) { // newest first
            if (entry.tickets != null) {
                longest = Math.max(longest, entry.tickets);
            }
        }
        for (SparklingHistory.Entry entry : ownEntries()) {
            if (entry.unique) {
                break;
            }
            sinceUnique += entry.tickets == null ? 0 : entry.tickets;
        }
        data.ticketsSinceOwnUnique = sinceUnique;
        data.longestDrySparkling = Math.max(longest, data.ticketsSinceOwnSparkling);
        data.longestDryUnique = sinceUnique;
        seedRunCounters();

        int before = data.unlocked.size();
        checkAll(false);
        for (RunHistory.Run run : RunHistory.runs()) {
            checkRun(run, false);
        }
        checkHistoryCatches(false);
        save();
        int earned = data.unlocked.size() - before;
        if (earned > 0) {
            client.gui.getChat().addClientSystemMessage(Component.literal("§b[Shiny Hunter] §fAchievements are here! §7Your history already earns §e"
                    + earned + "§7 of them — see §f/shiny achievements§7."));
        }
    }

    /** The achievements earned by particular catches in your history. */
    private static void checkHistoryCatches(boolean announce) {
        for (SparklingHistory.Entry entry : ownEntries()) {
            if (entry.critter.equals("Macaw") || entry.critter.equals("Wumpa") || entry.critter.equals("Doomspiral")) {
                unlock(LEGENDARY_SPARKLE, announce);
            }
            if (entry.critter.equals("Gemzie")) {
                unlock(GEMZIE_SPARKLE, announce);
            }
        }
    }

    /** After catches are imported: everything the bigger history now earns, announced. */
    public static void recheckHistory() {
        if (!data.initialised) {
            return; // the first start will see the imported catches anyway
        }
        checkHistoryCatches(true);
        checkAll(true);
    }

    /**
     * After runs and catches are imported from old logs: the streak counters are worked out again
     * from the whole run history (they can only grow), and every run is checked. Unlocks quietly —
     * the importer reports the count in one line.
     *
     * @return how many achievements this unlocked
     */
    public static int afterImport() {
        if (!data.initialised) {
            return 0;
        }
        int before = data.unlocked.size();
        List<RunHistory.Run> oldestFirst = new ArrayList<>(RunHistory.runs());
        java.util.Collections.reverse(oldestFirst);
        int noMacaw = 0;
        int noSparkle = 0;
        boolean lastHadOwn = false;
        for (RunHistory.Run run : oldestFirst) {
            noMacaw = run.macaws > 0 ? 0 : noMacaw + 1;
            data.longestRunsWithoutMacaw = Math.max(data.longestRunsWithoutMacaw, noMacaw);
            noSparkle = run.ownSparklings > 0 ? 0 : noSparkle + 1;
            data.longestRunsWithoutSparkling = Math.max(data.longestRunsWithoutSparkling, noSparkle);
            if (run.ownSparklings > 0 && lastHadOwn) {
                unlock(BACK_TO_BACK, false);
            }
            lastHadOwn = run.ownSparklings > 0;
            checkRun(run, false);
        }
        data.runsSinceMacaw = noMacaw;
        data.runsSinceOwnSparkling = noSparkle;
        data.lastRunHadOwnSparkling = lastHadOwn;
        checkHistoryCatches(false);
        checkAll(false);
        save();
        return data.unlocked.size() - before;
    }

    /** Bump whenever achievements are added, removed or re-tuned. */
    private static final int LIST_VERSION = 2;

    /**
     * The achievement list changed: drop unlocks of achievements that no longer exist, and unlock
     * whatever the new thresholds already give — quietly, with one line saying how many — rather
     * than a fanfare per achievement.
     */
    private static void relist(Minecraft client) {
        data.listVersion = LIST_VERSION;
        Set<String> ids = new java.util.HashSet<>();
        for (Def def : ALL) {
            ids.add(def.id());
        }
        data.unlocked.keySet().removeIf(id -> !ids.contains(id));
        int before = data.unlocked.size();
        checkAll(false);
        for (RunHistory.Run run : RunHistory.runs()) {
            checkRun(run, false);
        }
        save();
        int earned = data.unlocked.size() - before;
        if (earned > 0) {
            client.gui.getChat().addClientSystemMessage(Component.literal("§b[Shiny Hunter] §fAchievements were re-tuned: §e"
                    + earned + "§f unlocked with the new numbers. §7See /shiny achievements."));
        }
    }

    // ------------------------------------------------------------------ reading

    /** Every achievement, including one per emblem unlocked. */
    public static List<Def> all() {
        List<Def> out = new ArrayList<>(ALL);
        for (String emblem : data.emblems.keySet()) {
            out.add(new Def("emblem:" + emblem, emblem + " Emblem", "Unlock the " + emblem + " emblem.",
                    Tier.EPIC, false, Measure.EVENT, 1));
        }
        return out;
    }

    public static boolean isUnlocked(Def def) {
        return def.id().startsWith("emblem:")
                ? data.emblems.containsKey(def.id().substring(7))
                : data.unlocked.containsKey(def.id());
    }

    /** When it was unlocked, or 0. */
    public static long unlockedAt(Def def) {
        Long at = def.id().startsWith("emblem:") ? data.emblems.get(def.id().substring(7)) : data.unlocked.get(def.id());
        return at == null ? 0 : at;
    }

    public static long ticketsSinceOwnSparkling() {
        return data.ticketsSinceOwnSparkling;
    }

    /** Longest run of Safari runs without your sparkling, the current one included. */
    public static long longestDrySparkling() {
        return Math.max(data.longestRunsWithoutSparkling, data.runsSinceOwnSparkling);
    }

    /**
     * Run counts didn't exist before; each Safari run costs one ticket, so the ticket counters are
     * the run counts so far. Done once, for a file from before the change or a first start.
     */
    private static void seedRunCounters() {
        if (data.runCountersSeeded) {
            return;
        }
        data.runCountersSeeded = true;
        data.runsSinceOwnSparkling = (int) data.ticketsSinceOwnSparkling;
        data.runsSinceOwnUnique = (int) data.ticketsSinceOwnUnique;
        data.longestRunsWithoutSparkling = (int) Math.max(data.longestDrySparkling, data.ticketsSinceOwnSparkling);
        data.longestRunsWithoutUnique = (int) Math.max(data.longestDryUnique, data.ticketsSinceOwnUnique);
        save();
    }

    public static int runsSinceMacaw() {
        return data.runsSinceMacaw;
    }

    public static int contestsCompleted() {
        return data.contestsCompleted;
    }

    /** Your own catches in the sparkling history, newest first. */
    static List<SparklingHistory.Entry> ownEntries() {
        Minecraft client = Minecraft.getInstance();
        List<SparklingHistory.Entry> out = new ArrayList<>();
        if (client.player == null) {
            return out;
        }
        String self = client.player.getName().getString();
        for (SparklingHistory.Entry entry : SparklingHistory.entries()) {
            if (entry.player.equalsIgnoreCase(self)) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * Your sparkling dex: everything in your history, plus your Sparkling Critterdex from the API
     * when a party lookup has fetched it.
     */
    public static Set<String> ownDex() {
        Set<String> dex = new LinkedHashSet<>();
        for (SparklingHistory.Entry entry : ownEntries()) {
            String canonical = CritterDex.canonical(entry.critter);
            if (canonical != null) {
                dex.add(canonical);
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String self = client.player.getName().getString();
            for (Map.Entry<String, PlayerDex> known : PartyDex.dexes().entrySet()) {
                if (known.getKey().equalsIgnoreCase(self)) {
                    dex.addAll(known.getValue().caught());
                }
            }
        }
        return dex;
    }

    // ------------------------------------------------------------------ storage

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-achievements.json");
    }

    private static void load() {
        Path file = path();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.unlocked == null) {
                    read.unlocked = new LinkedHashMap<>();
                }
                if (read.emblems == null) {
                    read.emblems = new LinkedHashMap<>();
                }
                data = read;
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read achievements — starting empty", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save achievements", e);
        }
    }
}
