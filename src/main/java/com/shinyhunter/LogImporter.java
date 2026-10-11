package com.shinyhunter;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Rebuilds past Safari runs from the game's own chat logs ({@code logs/*.log.gz} and
 * {@code latest.log}), for {@code /shiny history import}.
 *
 * <p>A run starts at the Safari Manager taking (or having seen) your ticket, and ends when you warp
 * out — Hypixel's "Sending to server mini143BE..." line — or when the log ends. In between, the same
 * lines the live tracker reads: your captures, loot shares (and their shards), sparklings, Macaws,
 * and HOTSPOT!/HEAD START! for when the hunt began. A run's length is from the hunt beginning to the
 * warp out. Splits come from biomes being called done in party chat ("Forest clear!", "icy done").
 *
 * <p>Only the server's own lines count: a channel prefix (party, guild, DMs) means somebody quoting
 * one. Read on a background thread; the results are merged on the client thread.
 */
public final class LogImporter {

    /** {@code 2026-10-10-3.log.gz}: the date the log was written, and its order that day. */
    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})-(\\d+)\\.log\\.gz$");

    /** {@code [13:21:18] [Render thread/INFO]: [CHAT] message}. */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\] \\[[^\\]]*\\]: \\[CHAT\\] (.*)$");

    private static final Pattern CODES = Pattern.compile("§.");

    private static final String MANAGER = "[NPC] Safari Manager: ";

    /** The three ways the Safari Manager lets you in. Only the first two take a ticket. */
    private static final String[] USES_TICKET = {
            "Looks good to me. Have fun out there!",
            "I already saw your ticket, so you're free to go."};
    private static final String ALREADY_GAVE = "You already gave me your ticket, bud.";

    private static final Pattern SENDING = Pattern.compile("^Sending to server \\S+\\.\\.\\.$");
    private static final Pattern SPARKLING = Pattern.compile("^SPARKLING! ([A-Za-z0-9_]{1,16}) caught a SPARKLING (.+?)!$");

    /** A party chat line: the message after the sender. */
    private static final Pattern PARTY_LINE = Pattern.compile("^Party > [^:]*?: (.+)$");

    /**
     * A biome called done in party chat — the whole message, so chatter like "need help icy when
     * someone's done" doesn't count: "Forest clear!" (this mod), "Icy Done!", "ice done".
     */
    private static final Pattern BIOME_DONE = Pattern.compile(
            "^(cavern|forest|haunted|icy|ice) (?:clear|done)!?$", Pattern.CASE_INSENSITIVE);

    /** This mod's Forest-is-done-but-Macaw call: "Forest 8/9, missing Macaw". */
    private static final Pattern FOREST_NO_MACAW = Pattern.compile("^Forest \\d+/\\d+, missing Macaw$");

    /** What one import found, and the newest archived log it read (by modification time). */
    public record Result(List<RunHistory.Run> runs, List<SparklingHistory.Entry> sparklings, int files,
                         long newestArchive) {
    }

    private static volatile boolean running;

    /** Progress of the read in progress, by bytes of log files, for the action-bar bar. */
    private static volatile long bytesTotal;
    private static volatile long bytesDone;
    private static volatile int filesTotal;
    private static volatile int filesDone;

    /** Whether the read in progress was asked for (and so shows its progress). */
    private static boolean showProgress;
    private static int progressTicks;

    private LogImporter() {
    }

    public static Path defaultLogs() {
        return FabricLoader.getInstance().getGameDir().resolve("logs");
    }

    public static boolean isRunning() {
        return running;
    }

    /** Reads every log in the folder off the client thread. */
    public static CompletableFuture<Result> read(Path folder, String self) {
        return read(folder, self, true, -1);
    }

    /**
     * Reads logs off the client thread.
     *
     * @param includeLatest whether {@code latest.log} (the session in progress) is read too
     * @param newerThan     only archived logs modified after this; 0 means just the newest one, and
     *                      -1 means all of them
     */
    static CompletableFuture<Result> read(Path folder, String self, boolean includeLatest, long newerThan) {
        running = true;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readAll(folder, self, includeLatest, newerThan);
            } finally {
                running = false;
            }
        });
    }

    // ------------------------------------------------------------------ reading

    private record LogFile(Path path, LocalDate day, int order, long modified) {
    }

    private static Result readAll(Path folder, String self, boolean includeLatest, long newerThan) {
        List<LogFile> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(folder)) {
            for (Path path : (Iterable<Path>) listing::iterator) {
                String name = path.getFileName().toString();
                Matcher m = FILE_NAME.matcher(name);
                if (m.matches()) {
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    files.add(new LogFile(path, LocalDate.of(Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), Integer.parseInt(m.group(4)),
                            modified));
                }
            }
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't list logs in {}", folder, e);
        }
        files.sort((a, b) -> a.day().equals(b.day()) ? Integer.compare(a.order(), b.order()) : a.day().compareTo(b.day()));
        long newest = 0;
        for (LogFile file : files) {
            newest = Math.max(newest, file.modified());
        }
        if (newerThan == 0) {
            // Nothing read before: just the most recently archived log.
            final long last = newest;
            files.removeIf(file -> file.modified() != last);
        } else if (newerThan > 0) {
            files.removeIf(file -> file.modified() <= newerThan);
        }
        Path latest = folder.resolve("latest.log");
        if (includeLatest && Files.exists(latest)) {
            try {
                LocalDate day = LocalDateTime.ofInstant(Files.getLastModifiedTime(latest).toInstant(),
                        ZoneId.systemDefault()).toLocalDate();
                files.add(new LogFile(latest, day, Integer.MAX_VALUE, 0));
            } catch (IOException ignored) {
                // no date, no latest.log
            }
        }

        long total = 0;
        for (LogFile file : files) {
            total += sizeOf(file.path());
        }
        bytesTotal = Math.max(1, total);
        bytesDone = 0;
        filesTotal = files.size();
        filesDone = 0;
        Parser parser = new Parser(self);
        for (LogFile file : files) {
            parser.read(file);
            bytesDone += sizeOf(file.path());
            filesDone++;
        }
        ShinyHunterClient.LOGGER.info("Log import: {} file(s), {} run(s), {} sparkling(s)",
                files.size(), parser.runs.size(), parser.sparklings.size());
        return new Result(parser.runs, parser.sparklings, files.size(), newest);
    }

    /** Walks the logs in order, carrying a run across nothing — each log file closes its own. */
    private static final class Parser {
        final String self;
        final List<RunHistory.Run> runs = new ArrayList<>();
        final List<SparklingHistory.Entry> sparklings = new ArrayList<>();
        int ticketsSinceSparkling;

        RunHistory.Run run;
        Set<String> party;
        long clockStart;
        long lastSecured;
        java.util.Map<String, Long> splits;

        Parser(String self) {
            this.self = self;
        }

        void read(LogFile file) {
            try (InputStream raw = Files.newInputStream(file.path());
                 InputStream in = file.path().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                LocalDate day = file.day();
                long last = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.indexOf("[CHAT]") < 0) {
                        continue;
                    }
                    Matcher m = LINE.matcher(line);
                    if (!m.matches()) {
                        continue;
                    }
                    long at = day.atTime(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3))).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (last != 0 && at < last - 3_600_000L) {
                        // The clock went backwards: the session ran past midnight.
                        day = day.plusDays(1);
                        at += 86_400_000L;
                    }
                    last = at;
                    handle(CODES.matcher(m.group(4)).replaceAll("").trim(), at);
                }
                close(last);
            } catch (IOException | RuntimeException e) {
                ShinyHunterClient.LOGGER.warn("Couldn't read log {}", file.path().getFileName(), e);
                close(0);
            }
        }

        void handle(String text, long at) {
            if (text.isEmpty()) {
                return;
            }
            if (run != null && clockStart > 0) {
                Matcher party = PARTY_LINE.matcher(text);
                if (party.matches()) {
                    split(party.group(1).trim(), at);
                    return;
                }
            }
            if (HotspotAnnouncer.isRelayed(text)) {
                return;
            }
            if (text.startsWith(MANAGER)) {
                String said = text.substring(MANAGER.length()).trim();
                boolean ticket = said.equals(USES_TICKET[0]) || said.equals(USES_TICKET[1]);
                if (ticket || said.equals(ALREADY_GAVE)) {
                    if (ticket) {
                        ticketsSinceSparkling++;
                    }
                    if (run == null) {
                        run = new RunHistory.Run();
                        run.start = at;
                        party = new LinkedHashSet<>();
                        clockStart = 0;
                        lastSecured = 0;
                        splits = new java.util.LinkedHashMap<>();
                    }
                    if (ticket) {
                        run.tickets++;
                    }
                }
                return;
            }
            Matcher sparkling = SPARKLING.matcher(text);
            if (sparkling.matches()) {
                SparklingHistory.Entry entry = new SparklingHistory.Entry();
                entry.at = at;
                entry.player = sparkling.group(1);
                String critter = sparkling.group(2).trim();
                String canonical = CritterDex.canonical(critter);
                entry.critter = canonical != null ? canonical : critter;
                entry.tickets = ticketsSinceSparkling;
                ticketsSinceSparkling = 0;
                sparklings.add(entry);
                if (run != null) {
                    run.sparklings.add(entry.player + ":" + entry.critter);
                    if (entry.player.equalsIgnoreCase(self)) {
                        run.ownSparklings++;
                    }
                }
                return;
            }
            if (run == null) {
                return;
            }
            if (SENDING.matcher(text).matches()) {
                close(at);
                return;
            }
            if (text.equals(RunHistory.MACAW_BONUS)) {
                run.feathers++;
                return;
            }
            if (text.startsWith("HOTSPOT!") || text.startsWith("HEAD START!")) {
                if (clockStart == 0) {
                    clockStart = at;
                }
                return;
            }
            boolean own = text.startsWith("CAPTURE!");
            if (!own && !text.startsWith("LOOT SHARE!")) {
                return;
            }
            String critter = CritterDex.findIn(text);
            if (critter == null) {
                return;
            }
            lastSecured = at;
            if (own) {
                run.caught++;
                run.catches.merge(critter, 1, Integer::sum);
            } else {
                run.lootShares++;
            }
            party.add(critter);
            if (critter.equals(SplitTracker.MACAW)) {
                run.macaws++;
            }
            if (text.contains("Shard")) {
                Matcher quantity = RunHistory.QUANTITY.matcher(text);
                run.shards.merge(critter, quantity.find() ? Integer.parseInt(quantity.group(1)) : 1, Integer::sum);
            }
            Matcher feathers = RunHistory.FEATHERS.matcher(text);
            if (feathers.find()) {
                run.feathers += feathers.group(1) == null ? 1 : Integer.parseInt(feathers.group(1));
            }
        }

        /** A biome called done: its split, the first call per biome counting. */
        void split(String said, long at) {
            String key = null;
            Matcher done = BIOME_DONE.matcher(said);
            if (done.matches()) {
                String word = done.group(1).toLowerCase(java.util.Locale.ROOT);
                key = word.equals("ice") ? "Icy" : Character.toUpperCase(word.charAt(0)) + word.substring(1);
            } else if (FOREST_NO_MACAW.matcher(said).matches()) {
                key = SplitTracker.FOREST_NO_MACAW;
            }
            if (key == null || splits.containsKey(key)) {
                return;
            }
            splits.put(key, at - clockStart);
            // A whole Forest is also a Forest without Macaw, as in a live run.
            if (key.equals("Forest")) {
                splits.putIfAbsent(SplitTracker.FOREST_NO_MACAW, at - clockStart);
            }
        }

        /** Ends the run in progress, keeping it only if anything was caught. */
        void close(long at) {
            RunHistory.Run done = run;
            run = null;
            if (done == null || party.isEmpty()) {
                return;
            }
            done.party = party.size();
            done.you = done.catches.size();
            done.clocked = clockStart > 0;
            // To the warp out (or the log's last line): standing around at the end is part of the run.
            long from = clockStart > 0 ? clockStart : done.start;
            long to = at > 0 ? at : lastSecured;
            done.length = Math.max(0, to - from);
            done.imported = true;
            for (String key : SplitTracker.keys()) {
                Long time = splits.get(key);
                if (time != null) {
                    done.splits.put(key, time);
                }
            }
            runs.add(done);
        }
    }

    // ------------------------------------------------------------------ the command

    /**
     * {@code /shiny history import}: the logs, then the shinylogs file if there is one. Says what it
     * found when done.
     */
    public static void runImport(Path folder) {
        Minecraft client = Minecraft.getInstance();
        if (running) {
            tell("§7An import is already running.");
            return;
        }
        if (!Files.isDirectory(folder)) {
            tell("§cNo logs folder at §f" + folder);
            return;
        }
        String self = client.player == null ? "" : client.player.getName().getString();
        tell("§7Reading your logs for past runs… progress is shown above your hotbar.");
        showProgress = true;
        read(folder, self).whenComplete((result, error) -> client.execute(() -> {
            showProgress = false;
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.literal("§aLog import done!"));
            }
            if (error != null || result == null) {
                tell("§cThe log import failed — see the game log.");
                ShinyHunterClient.LOGGER.warn("Log import failed", error);
                return;
            }
            int runs = merge(result);
            int catches = SparklingHistory.importEntries(result.sparklings());
            int fromSite = 0;
            Path site = SparklingHistory.defaultImportFile();
            if (Files.exists(site)) {
                fromSite = Math.max(0, SparklingHistory.importFrom(site));
            }
            int earned = Achievements.afterImport();
            tell("§fRead §e" + result.files() + "§f log files: added §e" + runs + "§f run" + (runs == 1 ? "" : "s")
                    + " and §e" + (catches + fromSite) + "§f sparkling" + (catches + fromSite == 1 ? "" : "s")
                    + (fromSite > 0 ? " §7(" + fromSite + " from sparkling-entries.json)" : "")
                    + "§f. " + (earned > 0 ? "§6" + earned + " achievement" + (earned == 1 ? "" : "s") + " unlocked! " : "")
                    + "§7Already-saved runs were skipped.");
        }));
    }

    /** {@code Importing logs ■■■■■■□□□□□□ 48% (171/356 files)}, by bytes read. */
    private static String progressBar() {
        int width = 24;
        double fraction = Math.min(1, (double) bytesDone / Math.max(1, bytesTotal));
        int filled = (int) Math.round(fraction * width);
        return "§bImporting logs §a" + "■".repeat(filled) + "§8" + "■".repeat(width - filled)
                + " §f" + Math.round(fraction * 100) + "% §7(" + filesDone + "/" + Math.max(filesTotal, filesDone) + " files)";
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Adds a result's runs and offers their splits to the bests. Returns how many runs were new. */
    private static int merge(Result result) {
        int runs = RunHistory.importRuns(result.runs());
        boolean bests = false;
        for (RunHistory.Run run : result.runs()) {
            bests |= SplitTracker.offerPastRun(run.splits, run.catches);
        }
        ShinyConfig config = ShinyConfig.get();
        if (result.newestArchive() > config.logsImportedUpTo) {
            config.logsImportedUpTo = result.newestArchive();
            bests = true;
        }
        if (bests) {
            config.save();
        }
        return runs;
    }

    // ------------------------------------------------------------------ on launch

    private static boolean autoDone;

    /**
     * Once per launch, when you're in game: quietly reads any archived log newer than the last one
     * read — normally just the session that rolled over on this launch. Minecraft keeps only 7 logs
     * per day and deletes the oldest of a day on the 8th launch, so this saves a busy day's runs
     * before they can go. Says something only if it found runs the mod didn't already have.
     */
    public static void onClientTick(Minecraft client) {
        if (showProgress && running && client.player != null && ++progressTicks >= 4) {
            progressTicks = 0;
            client.player.sendOverlayMessage(Component.literal(progressBar()));
        }
        if (autoDone || client.player == null || running) {
            return;
        }
        autoDone = true;
        ShinyConfig config = ShinyConfig.get();
        if (!config.autoImportLogs || !config.runHistoryEnabled || !Files.isDirectory(defaultLogs())) {
            return;
        }
        String self = client.player.getName().getString();
        read(defaultLogs(), self, false, config.logsImportedUpTo).whenComplete((result, error) -> client.execute(() -> {
            if (error != null || result == null) {
                ShinyHunterClient.LOGGER.warn("Automatic log import failed", error);
                return;
            }
            int runs = merge(result);
            int catches = SparklingHistory.importEntries(result.sparklings());
            int earned = Achievements.afterImport();
            ShinyHunterClient.LOGGER.info("Automatic log import: {} file(s), {} new run(s), {} new sparkling(s)",
                    result.files(), runs, catches);
            if (runs > 0 || catches > 0) {
                tell("§7Saved §f" + runs + "§7 run" + (runs == 1 ? "" : "s")
                        + (catches > 0 ? " and §f" + catches + "§7 sparkling" + (catches == 1 ? "" : "s") : "")
                        + " from your last session's log." + (earned > 0 ? " §6" + earned + " achievement"
                        + (earned == 1 ? "" : "s") + " unlocked!" : ""));
            }
        }));
    }

    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.getChat().addClientSystemMessage(Component.literal("§b[Shiny Hunter] " + message));
        }
    }
}
