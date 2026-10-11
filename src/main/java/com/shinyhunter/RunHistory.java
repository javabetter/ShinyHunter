package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A saved record of every Safari run, for the history screen and the achievements.
 *
 * <p>{@link RunTracker} says when a run starts and ends; this listens to chat in between for what
 * happened — your captures, teammates' catches (as loot shares), the shards they gave, and any
 * sparklings. It keeps its own tally rather than reading {@link CritterTracker} at the end, because
 * on a server hop that tracker has already been cleared by the time the run is closed.
 *
 * <p>Stored in {@code config/shinyhunter-runs.json}.
 */
public final class RunHistory {

    private static final Pattern CAPTURE = Pattern.compile("^CAPTURE!", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOT_SHARE = Pattern.compile("^LOOT SHARE!", Pattern.CASE_INSENSITIVE);

    /** "gained 2x Cavernfish Shard" — the quantity, when there's more than one. */
    static final Pattern QUANTITY = Pattern.compile("(\\d+)x [A-Za-z' ]+? Shard");

    /** "received a Rainbow Feather" / "received 2x Rainbow Feather", on a sparkling catch or its loot share. */
    static final Pattern FEATHERS = Pattern.compile("(?:(\\d+)x |an? )Rainbow Feather");

    /** The Macaw attribute's bonus: one more feather on top of the catch's. */
    static final String MACAW_BONUS = "MACAW! You found a bonus Rainbow Feather!";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One run. Public and mutable for Gson. */
    public static final class Run {
        public int number;
        /** When the run began (entering the Safari). */
        public long start;
        /** Run clock time when the clock started, else time spent in the Safari. */
        public long length;
        /** True when {@link #length} is the run clock rather than time in the area. */
        public boolean clocked;
        /** Different critters the party secured (your captures and loot shares). */
        public int party;
        /** Different critters you caught yourself. */
        public int you;
        /** Your captures, repeats included. */
        public int caught;
        /** Teammates' catches you got a share of. */
        public int lootShares;
        public int partySize;
        /** Safari tickets used for this run. */
        public int tickets;
        /** Critter -> shards received (captures and loot shares). */
        public Map<String, Integer> shards = new LinkedHashMap<>();
        /** Critter -> your captures of it. */
        public Map<String, Integer> catches = new LinkedHashMap<>();
        /** Sparklings caught in the run, as "player:critter". */
        public List<String> sparklings = new ArrayList<>();
        public int ownSparklings;
        /** Macaws the party secured — yours plus loot shares. */
        public int macaws;
        public Map<String, Long> splits = new LinkedHashMap<>();
        /** Rainbow Feathers received — one per sparkling caught or shared, plus Macaw bonuses. */
        public int feathers;
        /** Shard and feather value at the time, or -1 if prices weren't loaded yet. */
        public long coins = -1;
        /** Rebuilt from old logs: the length is approximate and there are no splits. */
        public boolean imported;

        transient Set<String> partySet = new LinkedHashSet<>();

        public boolean hasSparkling() {
            return !sparklings.isEmpty();
        }
    }

    private static final class Data {
        int nextNumber = 1;
        List<Run> runs = new ArrayList<>();
    }

    private static Data data = new Data();

    /** The run in progress, or null between runs. */
    private static Run current;

    /** Tickets are handed in at the entrance, before the run starts; held until it does. */
    private static int pendingTickets;

    private RunHistory() {
    }

    public static void register() {
        load();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        BazaarPrices.ensureFresh();
    }

    // ------------------------------------------------------------------ run boundaries

    /** Called by {@link RunTracker} when a run starts. */
    public static void begin() {
        // A ticket hops you to a fresh server (one run start) and then into the Safari (another);
        // a run that never got going hands its tickets on to the next.
        int carried = current != null && current.party == 0 ? current.tickets : 0;
        current = new Run();
        current.start = System.currentTimeMillis();
        current.tickets = carried + pendingTickets;
        pendingTickets = 0;
        BazaarPrices.ensureFresh();
    }

    /**
     * Called by {@link RunTracker} when a run ends, before the clock and splits are reset.
     *
     * @param inArea  how long the player was in the Safari
     * @param onClock the run clock, or 0 if it never started
     */
    public static void finish(long inArea, long onClock, Map<String, Long> splits) {
        Run run = current;
        current = null;
        if (run == null || !ShinyConfig.get().runHistoryEnabled) {
            return;
        }
        // Walking through the entrance and straight back out isn't a run.
        if (run.party == 0 && onClock <= 0) {
            return;
        }
        run.clocked = onClock > 0;
        run.length = run.clocked ? onClock : inArea;
        run.you = run.catches.size();
        run.partySize = PartyDex.partySize();
        run.splits = new LinkedHashMap<>(splits);
        run.coins = BazaarPrices.value(run.shards, run.feathers);
        run.number = data.nextNumber++;
        data.runs.add(0, run);
        save();
        ShinyHunterClient.LOGGER.info("Run #{} saved: {} party, {} you, {} caught, {} sparkling(s)",
                run.number, run.party, run.you, run.caught, run.sparklings.size());
        Achievements.onRunEnd(run);
    }

    public static void onTicket() {
        pendingTickets++;
    }

    public static void onSparkling(String player, String critter, boolean own) {
        if (current == null) {
            return;
        }
        current.sparklings.add(player + ":" + critter);
        if (own) {
            current.ownSparklings++;
        }
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        if (current == null) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || HotspotAnnouncer.isRelayed(text)) {
            return;
        }
        if (text.equals(MACAW_BONUS)) {
            current.feathers++;
            return;
        }
        boolean own = CAPTURE.matcher(text).find();
        if (!own && !LOOT_SHARE.matcher(text).find()) {
            return;
        }
        String critter = CritterDex.findIn(text);
        if (critter == null) {
            return;
        }
        Run run = current;
        if (own) {
            run.caught++;
            run.catches.merge(critter, 1, Integer::sum);
        } else {
            run.lootShares++;
        }
        if (run.partySet.add(critter)) {
            run.party = run.partySet.size();
        }
        if (critter.equals(SplitTracker.MACAW)) {
            run.macaws++;
            Achievements.onMacaw(run);
        }
        if (text.contains("Shard")) {
            Matcher quantity = QUANTITY.matcher(text);
            run.shards.merge(critter, quantity.find() ? Integer.parseInt(quantity.group(1)) : 1, Integer::sum);
        }
        Matcher feathers = FEATHERS.matcher(text);
        if (feathers.find()) {
            run.feathers += feathers.group(1) == null ? 1 : Integer.parseInt(feathers.group(1));
        }
    }

    // ------------------------------------------------------------------ reading

    /** Every saved run, newest first. Read-only. */
    public static List<Run> runs() {
        return Collections.unmodifiableList(data.runs);
    }

    public static Run current() {
        return current;
    }

    /**
     * Adds runs rebuilt from old logs. A run already saved — one starting within two minutes of
     * it — is skipped, so importing again (or importing a log the live tracker also saw) adds
     * nothing twice. Every run is then renumbered oldest first.
     *
     * @return how many were added
     */
    public static int importRuns(List<Run> found) {
        int added = 0;
        // Compared only with what was saved before: two found runs a minute apart are both real.
        List<Run> before = new ArrayList<>(data.runs);
        boolean refreshed = false;
        for (Run run : found) {
            boolean have = false;
            for (Run existing : before) {
                if (Math.abs(existing.start - run.start) <= 120_000L) {
                    have = true;
                    // An earlier import is replaced by this one, which may read the logs better;
                    // a run recorded live is always kept as it is.
                    if (existing.imported) {
                        int at = data.runs.indexOf(existing);
                        run.number = existing.number;
                        run.coins = BazaarPrices.value(run.shards, run.feathers);
                        data.runs.set(at, run);
                        refreshed = true;
                    }
                    break;
                }
            }
            if (!have) {
                run.coins = BazaarPrices.value(run.shards, run.feathers);
                data.runs.add(run);
                added++;
            }
        }
        if (added == 0 && refreshed) {
            save();
        }
        if (added > 0) {
            data.runs.sort((a, b) -> Long.compare(a.start, b.start));
            for (int i = 0; i < data.runs.size(); i++) {
                data.runs.get(i).number = i + 1;
            }
            data.nextNumber = data.runs.size() + 1;
            data.runs.sort((a, b) -> Long.compare(b.start, a.start));
            save();
        }
        return added;
    }

    /** Fills in coins for runs saved before the bazaar prices had loaded. */
    static void fillMissingCoins() {
        boolean changed = false;
        for (Run run : data.runs) {
            if (run.coins < 0 && run.shards != null) {
                run.coins = BazaarPrices.value(run.shards, run.feathers);
                changed |= run.coins >= 0;
            }
        }
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------------ storage

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-runs.json");
    }

    private static void load() {
        Path file = path();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            boolean migrated = false;
            if (read != null && read.runs != null) {
                read.runs.removeIf(r -> r == null);
                for (Run run : read.runs) {
                    if (run.shards == null) {
                        run.shards = new LinkedHashMap<>();
                    }
                    if (run.catches == null) {
                        run.catches = new LinkedHashMap<>();
                    }
                    if (run.sparklings == null) {
                        run.sparklings = new ArrayList<>();
                    }
                    if (run.splits == null) {
                        run.splits = new LinkedHashMap<>();
                    }
                    // Runs saved before feathers were counted: every sparkling in a run gives you a
                    // Rainbow Feather (your catch, or the loot share of a teammate's), so that many at
                    // least — a re-import from the logs adds any Macaw bonus. Coins are worked out
                    // again once the prices are in.
                    if (run.feathers == 0 && !run.sparklings.isEmpty()) {
                        run.feathers = run.sparklings.size();
                        run.coins = -1;
                        migrated = true;
                    }
                }
                read.runs.sort((a, b) -> Long.compare(b.start, a.start));
                data = read;
                if (migrated) {
                    ShinyHunterClient.LOGGER.info("Run history: counted Rainbow Feathers for older sparkling runs");
                    save();
                }
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read run history — starting empty", e);
        }
    }

    static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save run history", e);
        }
    }
}
