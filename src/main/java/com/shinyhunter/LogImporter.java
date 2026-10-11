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
 * and HOTSPOT!/HEAD START! for when the hunt began. A run's length is from the hunt beginning to its
 * last catch, so standing around before warping out doesn't count.
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
    private static final Pattern QUANTITY = Pattern.compile("(\\d+)x\\s");

    /** What one import found. */
    public record Result(List<RunHistory.Run> runs, List<SparklingHistory.Entry> sparklings, int files) {
    }

    private static volatile boolean running;

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
        running = true;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readAll(folder, self);
            } finally {
                running = false;
            }
        });
    }

    // ------------------------------------------------------------------ reading

    private record LogFile(Path path, LocalDate day, int order) {
    }

    private static Result readAll(Path folder, String self) {
        List<LogFile> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(folder)) {
            for (Path path : (Iterable<Path>) listing::iterator) {
                String name = path.getFileName().toString();
                Matcher m = FILE_NAME.matcher(name);
                if (m.matches()) {
                    files.add(new LogFile(path, LocalDate.of(Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), Integer.parseInt(m.group(4))));
                }
            }
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't list logs in {}", folder, e);
        }
        files.sort((a, b) -> a.day().equals(b.day()) ? Integer.compare(a.order(), b.order()) : a.day().compareTo(b.day()));
        Path latest = folder.resolve("latest.log");
        if (Files.exists(latest)) {
            try {
                LocalDate day = LocalDateTime.ofInstant(Files.getLastModifiedTime(latest).toInstant(),
                        ZoneId.systemDefault()).toLocalDate();
                files.add(new LogFile(latest, day, Integer.MAX_VALUE));
            } catch (IOException ignored) {
                // no date, no latest.log
            }
        }

        Parser parser = new Parser(self);
        for (LogFile file : files) {
            parser.read(file);
        }
        ShinyHunterClient.LOGGER.info("Log import: {} file(s), {} run(s), {} sparkling(s)",
                files.size(), parser.runs.size(), parser.sparklings.size());
        return new Result(parser.runs, parser.sparklings, files.size());
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
            if (text.isEmpty() || HotspotAnnouncer.isRelayed(text)) {
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
                Matcher quantity = QUANTITY.matcher(text);
                run.shards.merge(critter, quantity.find() ? Integer.parseInt(quantity.group(1)) : 1, Integer::sum);
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
            long from = clockStart > 0 ? clockStart : done.start;
            long to = lastSecured > 0 ? lastSecured : at;
            done.length = Math.max(0, to - from);
            done.imported = true;
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
        tell("§7Reading your logs for past runs… this can take a little while.");
        read(folder, self).whenComplete((result, error) -> client.execute(() -> {
            if (error != null || result == null) {
                tell("§cThe log import failed — see the game log.");
                ShinyHunterClient.LOGGER.warn("Log import failed", error);
                return;
            }
            int runs = RunHistory.importRuns(result.runs());
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

    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.getChat().addClientSystemMessage(Component.literal("§b[Shiny Hunter] " + message));
        }
    }
}
