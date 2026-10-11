package com.shinyhunter.gui;

import com.shinyhunter.Achievements;
import com.shinyhunter.BazaarPrices;
import com.shinyhunter.CritterDex;
import com.shinyhunter.Edition;
import com.shinyhunter.EntityDataProbe;
import com.shinyhunter.PlaytimeReader;
import com.shinyhunter.RunHistory;
import com.shinyhunter.SafariTimer;
import com.shinyhunter.ShinyConfig;
import com.shinyhunter.SparklingHistory;
import com.shinyhunter.SplitTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /shiny history}: everything the mod has recorded, in tabs — saved runs, personal bests,
 * sparklings, achievements and overall stats.
 *
 * <p>Drawn entirely by hand (tabs and buttons included) rather than with vanilla widgets, so the
 * whole panel shares one look and nothing has to be repositioned when a tab scrolls. Clickable
 * areas are collected while drawing and checked on the next click.
 */
public final class HistoryScreen extends Screen {

    /** The tabs, in order, with their accent colours. */
    public enum Tab {
        RUNS("Runs", 0xFF55CC66), BESTS("Bests", 0xFF55DDDD), SPARKLINGS("Sparklings", 0xFFFF77DD),
        ACHIEVEMENTS("Achievements", 0xFFFFAA22), STATS("Stats", 0xFF7799FF);

        final String label;
        final int colour;

        Tab(String label, int colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    private static final int BORDER = 0xFFE8A33A;
    private static final int BACKGROUND = 0xE0141210;
    private static final int ROW = 11;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int DIM = 0xFF666666;
    private static final int GOLD = 0xFFFFC94A;
    private static final int PINK = 0xFFFF77FF;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM  HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE, MMM dd yyyy 'at' hh:mm:ss a", Locale.ENGLISH);

    private enum RunSort {
        NEWEST("Newest"), OLDEST("Oldest"), FASTEST("Fastest"), LONGEST("Longest"),
        CAUGHT("Most caught"), COINS("Most coins"), PARTY("Party dex"), YOU("Your dex");

        final String label;

        RunSort(String label) {
            this.label = label;
        }
    }

    private enum AchievementFilter {
        ALL("All"), UNLOCKED("Unlocked"), LOCKED("Locked");

        final String label;

        AchievementFilter(String label) {
            this.label = label;
        }
    }

    private enum AchievementSort {
        DEFAULT("Unlocked first"), RARITY_UP("Rarity: low"), RARITY_DOWN("Rarity: high"),
        CLOSEST("Closest"), RECENT("Recent"), NAME("Name");

        final String label;

        AchievementSort(String label) {
            this.label = label;
        }
    }

    /** A clickable area collected while drawing. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen parent;
    private Tab tab;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<Tab, Integer> scroll = new LinkedHashMap<>();

    private boolean sparklingRunsOnly;
    private RunSort runSort = RunSort.NEWEST;
    private int selectedRun = -1;
    private boolean mineOnly;
    private AchievementFilter achievementFilter = AchievementFilter.ALL;
    private AchievementSort achievementSort = AchievementSort.DEFAULT;
    private String status;

    /** The scrollable list area of the current tab, so the wheel only scrolls over it. */
    private int listTop;
    private int listBottom;
    private int listRows;
    private int listSize;

    // Panel geometry, set each frame.
    private int px;
    private int py;
    private int pw;
    private int ph;

    public HistoryScreen(Screen parent, Tab tab) {
        super(Component.literal(Edition.NAME + " — History"));
        this.parent = parent;
        this.tab = tab;
        BazaarPrices.ensureFresh();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        hits.clear();

        pw = Math.min(640, width - 20);
        ph = Math.min(340, height - 50);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + 12;

        drawTabs(g, mouseX, mouseY);
        g.fill(px, py, px + pw, py + ph, BACKGROUND);
        g.outline(px, py, pw, ph, BORDER);
        g.outline(px + 1, py + 1, pw - 2, ph - 2, 0x40E8A33A);

        switch (tab) {
            case RUNS -> drawRuns(g, mouseX, mouseY);
            case BESTS -> drawBests(g);
            case SPARKLINGS -> drawSparklings(g, mouseX, mouseY);
            case ACHIEVEMENTS -> drawAchievements(g, mouseX, mouseY);
            case STATS -> drawStats(g);
        }
        button(g, mouseX, mouseY, px + pw - 66, py + ph - 20, 58, "Close", this::onClose);
        if (status != null) {
            g.text(font, status, px + 8, py + ph + 4, MUTED);
        }
    }

    private void drawTabs(GuiGraphicsExtractor g, int mx, int my) {
        int tabW = Math.min(100, (pw - 8) / Tab.values().length - 6);
        int total = Tab.values().length * (tabW + 6) - 6;
        int x = px + (pw - total) / 2;
        int y = py - 22;
        for (Tab t : Tab.values()) {
            boolean on = t == tab;
            boolean hover = mx >= x && mx < x + tabW && my >= y && my < y + 18;
            g.fill(x, y, x + tabW, y + 18, on ? (t.colour & 0x00FFFFFF) | 0xD0000000 : hover ? 0xC0302820 : 0xC0181410);
            g.outline(x, y, tabW, 18, t.colour);
            String label = t.label;
            g.text(font, label, x + (tabW - font.width(label)) / 2, y + 5, on ? 0xFF101010 : TEXT, !on);
            final Tab target = t;
            hits.add(new Hit(x, y, tabW, 18, () -> {
                tab = target;
                status = null;
            }));
            x += tabW + 6;
        }
    }

    // ------------------------------------------------------------------ runs

    private List<RunHistory.Run> filteredRuns() {
        List<RunHistory.Run> runs = new ArrayList<>();
        for (RunHistory.Run run : RunHistory.runs()) {
            if (!sparklingRunsOnly || run.hasSparkling()) {
                runs.add(run);
            }
        }
        Comparator<RunHistory.Run> order = switch (runSort) {
            case NEWEST -> Comparator.comparingLong((RunHistory.Run r) -> r.start).reversed();
            case OLDEST -> Comparator.comparingLong(r -> r.start);
            case FASTEST -> Comparator.comparingLong(r -> r.party >= CritterDex.total() ? r.length : Long.MAX_VALUE / 2 + r.length);
            case LONGEST -> Comparator.comparingLong((RunHistory.Run r) -> r.length).reversed();
            case CAUGHT -> Comparator.comparingInt((RunHistory.Run r) -> r.caught).reversed();
            case COINS -> Comparator.comparingLong((RunHistory.Run r) -> r.coins).reversed();
            case PARTY -> Comparator.comparingInt((RunHistory.Run r) -> r.party).reversed();
            case YOU -> Comparator.comparingInt((RunHistory.Run r) -> r.you).reversed();
        };
        runs.sort(order);
        return runs;
    }

    private void drawRuns(GuiGraphicsExtractor g, int mx, int my) {
        List<RunHistory.Run> all = RunHistory.runs();
        List<RunHistory.Run> runs = filteredRuns();

        g.text(font, "Saved Runs", px + 8, py + 6, GOLD);
        String kept = all.size() + " Kept";
        g.text(font, kept, px + pw - 8 - font.width(kept), py + 6, MUTED);

        long played = 0;
        long caught = 0;
        long coins = 0;
        for (RunHistory.Run run : all) {
            played += run.length;
            caught += run.caught;
            coins += Math.max(0, run.coins);
        }
        centred(g, "§f" + all.size() + " Runs  §8·  §f" + hours(played) + " Played  §8·  §f"
                + String.format(Locale.ROOT, "%,d", caught) + " Caught  §8·  §e"
                + BazaarPrices.format(coins) + " Coins", px + pw / 2, py + 19, TEXT);

        String[] headers = {"Run", "When", "Length", "Party", "You", "Caught", "Coins"};
        float[] at = {0.08f, 0.24f, 0.40f, 0.52f, 0.63f, 0.74f, 0.87f};
        int headerY = py + 33;
        for (int i = 0; i < headers.length; i++) {
            centred(g, headers[i], px + Math.round(pw * at[i]), headerY, 0xFFDDDDDD);
        }

        listTop = headerY + 12;
        listBottom = py + ph - 48;
        listRows = Math.max(1, (listBottom - listTop) / ROW);
        listSize = runs.size();
        int offset = clampScroll(Tab.RUNS);

        if (runs.isEmpty()) {
            centred(g, all.isEmpty() ? "No runs saved yet — finish a Safari run and it'll show up here."
                    : "No runs with a sparkling yet.", px + pw / 2, listTop + 20, MUTED);
        }
        int total = CritterDex.total();
        for (int i = 0; i < listRows && offset + i < runs.size(); i++) {
            RunHistory.Run run = runs.get(offset + i);
            int y = listTop + i * ROW;
            int index = offset + i;
            boolean selected = index == selectedRun;
            boolean hover = mx >= px + 4 && mx < px + pw - 4 && my >= y - 1 && my < y + ROW - 1;
            if (selected || hover) {
                g.fill(px + 4, y - 1, px + pw - 4, y + ROW - 1, selected ? 0x40FFFFFF : 0x18FFFFFF);
            }
            boolean sparkle = run.hasSparkling();
            String when = WHEN.format(Instant.ofEpochMilli(run.start).atZone(ZoneId.systemDefault()));
            String[] cells = {"#" + run.number, when, clock(run.length) + (run.clocked ? "" : "*"),
                    run.party + "/" + total, run.you + "/" + total, String.valueOf(run.caught),
                    BazaarPrices.format(run.coins)};
            if (sparkle) {
                // A sparkling run is drawn in a shimmering rainbow, one colour per character
                // flowing across the whole row.
                rainbow(g, "✦", px + 8, y);
                rainbow(g, "✦", px + pw - 14, y);
                for (int c = 0; c < cells.length; c++) {
                    rainbow(g, cells[c], px + Math.round(pw * at[c]) - font.width(cells[c]) / 2, y);
                }
            } else {
                int[] colours = {TEXT, TEXT, MUTED, run.party >= total ? 0xFF55FF55 : 0xFFDDDDDD,
                        0xFF77BBFF, 0xFF88DD88, GOLD};
                for (int c = 0; c < cells.length; c++) {
                    centred(g, cells[c], px + Math.round(pw * at[c]), y, colours[c]);
                }
            }
            hits.add(new Hit(px + 4, y - 1, pw - 8, ROW, () -> selectedRun = selectedRun == index ? -1 : index));
        }
        scrollbar(g, offset);

        // The selected run (or the one under the mouse) in detail.
        RunHistory.Run detail = null;
        if (selectedRun >= 0 && selectedRun < runs.size()) {
            detail = runs.get(selectedRun);
        }
        int detailY = py + ph - 44;
        if (detail != null) {
            if (detail.hasSparkling()) {
                List<String> names = new ArrayList<>();
                for (String s : detail.sparklings) {
                    String critter = s.substring(s.indexOf(':') + 1);
                    names.add(CritterDex.rarityOf(critter).colour + critter);
                }
                centred(g, "§d✦ §d" + detail.sparklings.size() + " Sparkling" + (detail.sparklings.size() == 1 ? "" : "s")
                        + ": " + String.join("§7, ", names) + " §d✦", px + pw / 2, detailY, PINK);
            } else {
                centred(g, "§7No sparklings this run", px + pw / 2, detailY, MUTED);
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Long> split : detail.splits.entrySet()) {
                parts.add("§f" + split.getKey() + " §b" + SafariTimer.format(split.getValue()));
            }
            String extra = "§7Macaws §f" + detail.macaws + "  §7Rainbow Feathers §f" + detail.feathers
                    + "  §7Loot shares §f" + detail.lootShares
                    + "  §7Tickets §f" + detail.tickets
                    + (detail.imported ? "  §8(from old logs — approximate, no splits)" : "  §7Party §f" + detail.partySize);
            centred(g, parts.isEmpty() ? extra : String.join(" §8· ", parts), px + pw / 2, detailY + 11, TEXT);
            if (!parts.isEmpty()) {
                // Splits take the line; the rest goes on hover over the detail.
                if (mx >= px && mx < px + pw && my >= detailY && my < detailY + 22) {
                    g.setComponentTooltipForNextFrame(font, List.of(Component.literal(extra)), mx, my);
                }
            }
        } else if (!runs.isEmpty()) {
            centred(g, "§8Click a run for its sparklings and splits  ·  §7*§8 = run clock never started",
                    px + pw / 2, detailY + 5, DIM);
        }

        int fy = py + ph - 20;
        toggle(g, mx, my, px + pw / 2 - 150, fy, "Sparkling Runs", sparklingRunsOnly, () -> {
            sparklingRunsOnly = !sparklingRunsOnly;
            selectedRun = -1;
            scroll.put(Tab.RUNS, 0);
        });
        button(g, mx, my, px + pw / 2 - 30, fy, 110, "Sort: " + runSort.label, () -> {
            runSort = RunSort.values()[(runSort.ordinal() + 1) % RunSort.values().length];
            selectedRun = -1;
        });
    }

    // ------------------------------------------------------------------ bests

    private void drawBests(GuiGraphicsExtractor g) {
        ShinyConfig config = ShinyConfig.get();
        g.text(font, "Personal Bests", px + 8, py + 6, GOLD);
        g.text(font, "§7Splits are cumulative from the start of the run.", px + 8, py + 18, MUTED);

        int left = px + 16;
        // Columns placed after the longest split name, so "Forest w/o Macaw" never runs into them.
        int labelW = 0;
        for (String key : SplitTracker.keys()) {
            labelW = Math.max(labelW, font.width(key));
        }
        int timeW = Math.max(font.width("9m42.00s"), font.width("Personal"));
        int colA = left + labelW + 8 + timeW / 2;
        int colB = colA + timeW + 6;
        int y = py + 36;
        g.text(font, "Split", left, y, 0xFFDDDDDD);
        centred(g, "Personal", colA, y, 0xFFDDDDDD);
        centred(g, "Party", colB, y, 0xFFDDDDDD);
        y += 13;
        for (String key : SplitTracker.keys()) {
            g.text(font, key, left, y, TEXT);
            Long mine = config.personalBests.get(key);
            Long party = config.partyBests.get(key);
            centred(g, mine == null ? "—" : SafariTimer.format(mine), colA, y, mine == null ? DIM : 0xFF55FF55);
            centred(g, party == null ? "—" : SafariTimer.format(party), colB, y, party == null ? DIM : 0xFF55DDDD);
            y += ROW + 1;
        }

        // Records from the saved runs.
        // The records start just past the split table, whatever its width.
        int rx = colB + timeW / 2 + 14;
        int ry = py + 36;
        g.text(font, "Run Records §8(best in one run)", rx, ry, 0xFFDDDDDD);
        ry += 13;
        List<RunHistory.Run> runs = RunHistory.runs();
        int total = CritterDex.total();
        RunHistory.Run fastest = best(runs, r -> r.clocked && r.party >= total, Comparator.comparingLong(r -> r.length));
        RunHistory.Run mostCaught = best(runs, r -> true, Comparator.comparingInt((RunHistory.Run r) -> r.caught).reversed());
        RunHistory.Run mostCoins = best(runs, r -> r.coins >= 0, Comparator.comparingLong((RunHistory.Run r) -> r.coins).reversed());
        RunHistory.Run mostYou = best(runs, r -> true, Comparator.comparingInt((RunHistory.Run r) -> r.you).reversed());
        RunHistory.Run mostSparkles = best(runs, RunHistory.Run::hasSparkling,
                Comparator.comparingInt((RunHistory.Run r) -> r.sparklings.size()).reversed());
        RunHistory.Run mostMacaws = best(runs, r -> r.macaws > 0, Comparator.comparingInt((RunHistory.Run r) -> r.macaws).reversed());

        ry = record(g, rx, ry, "Fastest run", fastest == null ? null : SafariTimer.format(fastest.length), fastest);
        ry = record(g, rx, ry, "Most caught", mostCaught == null ? null : String.valueOf(mostCaught.caught), mostCaught);
        ry = record(g, rx, ry, "Most coins", mostCoins == null ? null : BazaarPrices.format(mostCoins.coins), mostCoins);
        ry = record(g, rx, ry, "Best own dex", mostYou == null ? null : mostYou.you + "/" + total, mostYou);
        ry = record(g, rx, ry, "Sparklings", mostSparkles == null ? null : String.valueOf(mostSparkles.sparklings.size()), mostSparkles);
        ry = record(g, rx, ry, "Macaws", mostMacaws == null ? null : String.valueOf(mostMacaws.macaws), mostMacaws);
        ry += 6;
        g.text(font, "Luck", rx, ry, 0xFFDDDDDD);
        ry += 13;
        ry = record(g, rx, ry, "Dry streak", Achievements.longestDrySparkling() + " runs", null);
        record(g, rx, ry, "Macaw drought", Achievements.progress(achievement("no_macaw_10")) + " runs", null);
    }

    private int record(GuiGraphicsExtractor g, int x, int y, String label, String value, RunHistory.Run run) {
        g.text(font, "§7" + label, x, y, MUTED);
        String shown = value == null ? "§8—" : "§f" + value + (run == null ? "" : " §8(#" + run.number + ")");
        g.text(font, shown, px + pw - 12 - font.width(shown), y, TEXT);
        return y + ROW + 1;
    }

    private static RunHistory.Run best(List<RunHistory.Run> runs, java.util.function.Predicate<RunHistory.Run> keep,
                                       Comparator<RunHistory.Run> order) {
        RunHistory.Run best = null;
        for (RunHistory.Run run : runs) {
            if (keep.test(run) && (best == null || order.compare(run, best) < 0)) {
                best = run;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ sparklings

    private void drawSparklings(GuiGraphicsExtractor g, int mx, int my) {
        String self = minecraft.player == null ? "" : minecraft.player.getName().getString();
        List<SparklingHistory.Entry> entries = new ArrayList<>();
        for (SparklingHistory.Entry entry : SparklingHistory.entries()) {
            if (!mineOnly || entry.player.equalsIgnoreCase(self)) {
                entries.add(entry);
            }
        }
        int listW = Math.round(pw * 0.60f);
        g.text(font, "Sparkling History", px + 8, py + 6, PINK);
        String count = entries.size() + (entries.size() == 1 ? " catch" : " catches");
        g.text(font, count, px + listW - font.width(count), py + 6, MUTED);

        int y0 = py + 22;
        g.text(font, "When", px + 10, y0, 0xFFDDDDDD);
        g.text(font, "Player", px + 70, y0, 0xFFDDDDDD);
        g.text(font, "Critter", px + 70 + Math.round(listW * 0.28f), y0, 0xFFDDDDDD);
        String ticketsHeader = "Tickets";
        g.text(font, ticketsHeader, px + listW - font.width(ticketsHeader), y0, 0xFFDDDDDD);

        listTop = y0 + 12;
        listBottom = py + ph - 26;
        listRows = Math.max(1, (listBottom - listTop) / ROW);
        listSize = entries.size();
        int offset = clampScroll(Tab.SPARKLINGS);
        if (entries.isEmpty()) {
            g.text(font, "§7No sparklings recorded yet.", px + 10, listTop + 6, MUTED);
            g.text(font, "§7Have old logs? Use §fshinylogs.vercel.app§7, then Import.", px + 10, listTop + 18, MUTED);
        }
        for (int i = 0; i < listRows && offset + i < entries.size(); i++) {
            SparklingHistory.Entry entry = entries.get(offset + i);
            int y = listTop + i * ROW;
            var when = Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault());
            boolean hover = mx >= px + 4 && mx < px + listW + 4 && my >= y - 1 && my < y + ROW - 1;
            if (hover) {
                g.fill(px + 4, y - 1, px + listW + 4, y + ROW - 1, 0x18FFFFFF);
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("§f" + FULL.format(when)));
                if (entry.tickets != null) {
                    tip.add(Component.literal("§7Took §e" + entry.tickets + "§7 tickets since the last §6§lSPARKLING"));
                }
                g.setComponentTooltipForNextFrame(font, tip, mx, my);
            }
            g.text(font, "§7" + DateTimeFormatter.ofPattern("MMM dd", Locale.ENGLISH).format(when), px + 10, y, MUTED);
            g.text(font, SparklingHistory.nameColour(entry.player) + entry.player, px + 70, y, TEXT);
            String tag = entry.unique ? " §e§lNEW!" : " §b#" + SparklingHistory.numberOf(entry);
            g.text(font, CritterDex.rarityOf(entry.critter).colour + entry.critter + tag,
                    px + 70 + Math.round(listW * 0.28f), y, TEXT);
            String tickets = entry.tickets == null ? "§8—" : "§e" + entry.tickets;
            g.text(font, tickets, px + listW - font.width(tickets), y, TEXT);
        }
        scrollbarAt(g, offset, px + listW + 6);

        // Your dex, by biome.
        int dx = px + listW + 16;
        Set<String> dex = Achievements.ownDex();
        g.text(font, "Your Sparkling Dex", dx, py + 6, GOLD);
        String tally = dex.size() + "/" + CritterDex.total();
        g.text(font, tally, px + pw - 10 - font.width(tally), py + 6, dex.size() >= CritterDex.total() ? 0xFF55FF55 : TEXT);
        List<String> biomes = CritterDex.biomes();
        // Two columns of biomes; shrunk to fit when the window is too short for them.
        int half = (biomes.size() + 1) / 2;
        int[] needed = new int[2];
        for (int b = 0; b < biomes.size(); b++) {
            needed[b < half ? 0 : 1] += ROW + 1 + 10 * CritterDex.critters(biomes.get(b)).size() + 4;
        }
        float s = fit(Math.max(needed[0], needed[1]), py + ph - 26 - (py + 22));
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(dx, py + 22);
        pose.scale(s, s);
        int colW = Math.round((px + pw - 10 - dx) / 2f / s);
        int[] colY = {0, 0};
        for (int b = 0; b < biomes.size(); b++) {
            int col = b < half ? 0 : 1;
            int x = col * colW;
            String biome = biomes.get(b);
            List<String> critters = CritterDex.critters(biome);
            int have = 0;
            for (String c : critters) {
                have += dex.contains(c) ? 1 : 0;
            }
            g.text(font, "§l§n" + biome + "§r §7" + have + "/" + critters.size(), x, colY[col], biomeColour(biome));
            colY[col] += ROW + 1;
            for (String critter : critters) {
                boolean got = dex.contains(critter);
                g.text(font, got ? CritterDex.rarityOf(critter).colour + critter : "§8" + critter, x + 4, colY[col], TEXT);
                colY[col] += 10;
            }
            colY[col] += 4;
        }
        pose.popMatrix();

        int fy = py + ph - 20;
        toggle(g, mx, my, px + 8, fy, "Mine only", mineOnly, () -> {
            mineOnly = !mineOnly;
            scroll.put(Tab.SPARKLINGS, 0);
        });
        button(g, mx, my, px + 100, fy, 110, "Import backfill", () -> {
            java.nio.file.Path file = SparklingHistory.defaultImportFile();
            if (!java.nio.file.Files.exists(file)) {
                status = "§cNo Downloads\\sparkling-entries.json §7— download it from shinylogs.vercel.app first.";
                return;
            }
            int added = SparklingHistory.importFrom(file);
            status = added < 0 ? "§cCouldn't read sparkling-entries.json." : "§aImported " + added + " sparkling"
                    + (added == 1 ? "" : "s") + " §7(duplicates skipped).";
        });
        button(g, mx, my, px + 216, fy, 120, "Open shinylogs", () ->
                ConfirmLinkScreen.confirmLinkNow(this, "https://shinylogs.vercel.app"));
    }

    private static int biomeColour(String biome) {
        return switch (biome) {
            case "Forest" -> 0xFF00AA00;
            case "Cavern" -> 0xFFFFAA00;
            case "Icy" -> 0xFF5555FF;
            case "Haunted" -> 0xFFAA00AA;
            default -> TEXT;
        };
    }

    // ------------------------------------------------------------------ achievements

    private void drawAchievements(GuiGraphicsExtractor g, int mx, int my) {
        List<Achievements.Def> all = Achievements.all();
        int unlocked = 0;
        for (Achievements.Def def : all) {
            unlocked += Achievements.isUnlocked(def) ? 1 : 0;
        }
        List<Achievements.Def> shown = new ArrayList<>();
        for (Achievements.Def def : all) {
            boolean has = Achievements.isUnlocked(def);
            if (achievementFilter == AchievementFilter.ALL
                    || (achievementFilter == AchievementFilter.UNLOCKED) == has) {
                shown.add(def);
            }
        }
        shown.sort(achievementOrder());

        g.text(font, "Achievements", px + 8, py + 6, GOLD);
        int percent = all.isEmpty() ? 0 : unlocked * 100 / all.size();
        String summary = unlocked + "/" + all.size() + " unlocked (" + percent + "%)";
        g.text(font, summary, px + pw - 8 - font.width(summary), py + 6, TEXT);
        bar(g, px + 8, py + 18, pw - 16, 4, all.isEmpty() ? 0 : (double) unlocked / all.size(), GOLD);

        int rowH = 23;
        listTop = py + 28;
        listBottom = py + ph - 26;
        listRows = Math.max(1, (listBottom - listTop) / rowH);
        listSize = shown.size();
        int offset = clampScroll(Tab.ACHIEVEMENTS);
        for (int i = 0; i < listRows && offset + i < shown.size(); i++) {
            Achievements.Def def = shown.get(offset + i);
            int y = listTop + i * rowH;
            boolean has = Achievements.isUnlocked(def);
            boolean hidden = def.secret() && !has;
            g.fill(px + 6, y, px + pw - 12, y + rowH - 2, has ? (def.tier().argb & 0x00FFFFFF) | 0x22000000 : 0x14FFFFFF);
            g.fill(px + 6, y, px + 9, y + rowH - 2, has ? def.tier().argb : 0xFF444444);
            // The right-hand column (date or progress) is reserved; text on the left is cut to fit
            // beside it, and the whole thing is on hover.
            int right = px + pw - 18;
            String side = null;
            long progress = 0;
            if (has) {
                side = "§a✔ §7" + DAY.format(Instant.ofEpochMilli(Achievements.unlockedAt(def)).atZone(ZoneId.systemDefault()));
            } else if (def.hasProgress()) {
                progress = Math.min(Achievements.progress(def), def.goal());
                side = "§7" + compact(progress) + "/" + compact(def.goal());
            }
            int sideW = side == null ? 0 : Math.max(font.width(side), def.hasProgress() && !has ? 70 : 0);
            int textW = right - sideW - 10 - (px + 14);
            String namePrefix = hidden ? "§8§l" : (has ? def.tier().code + "§l" : "§7§l");
            String name = cut(namePrefix, hidden ? "???" : def.name(), textW);
            g.text(font, name, px + 14, y + 2, TEXT);
            String tierLabel = (has || !hidden ? def.tier().code : "§8") + def.tier().name();
            if (shownWidth(name) + 8 + font.width(tierLabel) <= textW) {
                g.text(font, tierLabel, px + 14 + shownWidth(name) + 8, y + 2, TEXT);
            }
            String description = hidden ? "Secret achievement — keep playing to find out." : def.description();
            g.text(font, cut(hidden ? "§8" : "§7", description, textW), px + 14, y + 12, MUTED);
            if (mx >= px + 6 && mx < px + pw - 12 && my >= y && my < y + rowH - 2) {
                g.setComponentTooltipForNextFrame(font, List.of(
                        Component.literal(hidden ? "§8§l???" : def.tier().code + "§l" + def.name()),
                        Component.literal(tierLabel),
                        Component.literal("§7" + description)), mx, my);
            }

            if (has) {
                g.text(font, side, right - font.width(side), y + 7, TEXT);
            } else if (side != null) {
                g.text(font, side, right - font.width(side), y + 2, MUTED);
                bar(g, right - sideW, y + 13, sideW, 4, (double) progress / def.goal(), def.tier().argb);
            }
        }
        scrollbar(g, offset);

        int fy = py + ph - 20;
        button(g, mx, my, px + 8, fy, 100, "Show: " + achievementFilter.label, () -> {
            achievementFilter = AchievementFilter.values()[(achievementFilter.ordinal() + 1) % AchievementFilter.values().length];
            scroll.put(Tab.ACHIEVEMENTS, 0);
        });
        button(g, mx, my, px + 114, fy, 130, "Sort: " + achievementSort.label, () -> {
            achievementSort = AchievementSort.values()[(achievementSort.ordinal() + 1) % AchievementSort.values().length];
            scroll.put(Tab.ACHIEVEMENTS, 0);
        });
    }

    /** The order for the current sort; ties always fall back to rarity, then name. */
    private Comparator<Achievements.Def> achievementOrder() {
        Comparator<Achievements.Def> rarity = Comparator.comparingInt(d -> d.tier().ordinal());
        Comparator<Achievements.Def> name = Comparator.comparing(Achievements.Def::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Achievements.Def> lockedLast = Comparator.comparing(d -> !Achievements.isUnlocked(d));
        Comparator<Achievements.Def> order = switch (achievementSort) {
            case DEFAULT -> lockedLast.thenComparing(rarity);
            case RARITY_UP -> rarity;
            case RARITY_DOWN -> rarity.reversed();
            // Locked ones nearest their goal first; unlocked (and secret) ones after.
            case CLOSEST -> Comparator.comparingDouble(HistoryScreen::remaining);
            case RECENT -> Comparator.comparingLong((Achievements.Def d) -> -Achievements.unlockedAt(d));
            case NAME -> name;
        };
        return order.thenComparing(rarity).thenComparing(name);
    }

    /** How far a locked achievement is from its goal, 0 to 1; done or unmeasurable ones sort last. */
    private static double remaining(Achievements.Def def) {
        if (Achievements.isUnlocked(def)) {
            return 3;
        }
        if (!def.hasProgress()) {
            return def.secret() ? 2.5 : 2;
        }
        return 1 - Math.min(1, (double) Achievements.progress(def) / def.goal());
    }

    private static Achievements.Def achievement(String id) {
        for (Achievements.Def def : Achievements.all()) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        throw new IllegalArgumentException(id);
    }

    // ------------------------------------------------------------------ stats

    private void drawStats(GuiGraphicsExtractor g) {
        List<RunHistory.Run> runs = RunHistory.runs();
        long played = 0;
        long caught = 0;
        long loot = 0;
        long shards = 0;
        long coins = 0;
        long tickets = 0;
        int withMacaw = 0;
        int macaws = 0;
        int fullClears = 0;
        int feathers = 0;
        Map<String, Integer> byCritter = new LinkedHashMap<>();
        for (RunHistory.Run run : runs) {
            played += run.length;
            caught += run.caught;
            loot += run.lootShares;
            coins += Math.max(0, run.coins);
            tickets += run.tickets;
            macaws += run.macaws;
            withMacaw += run.macaws > 0 ? 1 : 0;
            fullClears += run.party >= CritterDex.total() ? 1 : 0;
            feathers += run.feathers;
            for (int n : run.shards.values()) {
                shards += n;
            }
            run.catches.forEach((critter, n) -> byCritter.merge(critter, n, Integer::sum));
        }
        String self = minecraft.player == null ? "" : minecraft.player.getName().getString();
        int ownSparklings = 0;
        for (SparklingHistory.Entry entry : SparklingHistory.entries()) {
            ownSparklings += entry.player.equalsIgnoreCase(self) ? 1 : 0;
        }
        ShinyConfig config = ShinyConfig.get();

        g.text(font, "Stats", px + 8, py + 6, GOLD);
        int x = 0;
        int y = 0;
        String[][] rows = {
                {"Runs", String.format(Locale.ROOT, "%,d", runs.size())},
                {"Time on runs", hours(played)},
                {"Safari playtime", config.safariPlaytimeMinutes > 0 ? hours(PlaytimeReader.safariMillis())
                        : "§7open /playtime → Safari"},
                {"Average run", runs.isEmpty() ? "—" : clock(played / runs.size())},
                {"Full clears (37/37)", fullClears + (runs.isEmpty() ? "" : " §7(" + fullClears * 100 / runs.size() + "%)")},
                {"Critters caught", String.format(Locale.ROOT, "%,d", caught)},
                {"Loot shares", String.format(Locale.ROOT, "%,d", loot)},
                {"Shards", String.format(Locale.ROOT, "%,d", shards)},
                {"Rainbow Feathers", String.valueOf(feathers)},
                {"Shard + feather value", "§e" + BazaarPrices.format(coins) + (played > 0 ? " §7· " + BazaarPrices.format(coins * 3_600_000L / played) + "/h" : "")},
                {"Tickets (in runs)", String.format(Locale.ROOT, "%,d", tickets)},
                {"Sparklings (you / seen)", ownSparklings + " / " + SparklingHistory.entries().size()},
                {"Sparkling dex", Achievements.ownDex().size() + "/" + CritterDex.total()},
                {"Tickets since sparkling", config.ticketsSinceSparkling + " §7party · §f" + Achievements.ticketsSinceOwnSparkling() + " §7you"},
                {"Macaws", macaws + (runs.isEmpty() ? "" : " §7· " + withMacaw * 100 / runs.size() + "% of runs")},
                {"Runs since a Macaw", String.valueOf(Achievements.runsSinceMacaw())},
                {"Contests completed", String.valueOf(Achievements.contestsCompleted())},
                {"Unclaimed contests", config.unclaimedContests < 0 ? "unknown" : String.valueOf(config.unclaimedContests)},
        };
        float s = fit(rows.length * ROW, py + ph - 26 - (py + 22));
        int valueX = Math.round((pw * 0.50f - 12) / s);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(px + 12, py + 22);
        pose.scale(s, s);
        for (String[] row : rows) {
            g.text(font, "§7" + row[0], x, y, MUTED);
            g.text(font, "§f" + row[1], valueX - font.width(row[1]), y, TEXT);
            y += ROW;
        }
        pose.popMatrix();

        // Most caught critters, as bars.
        int bx = px + Math.round(pw * 0.56f);
        g.text(font, "Most caught", bx, py + 22, 0xFFDDDDDD);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(byCritter.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int max = top.isEmpty() ? 1 : top.get(0).getValue();
        int by = py + 35;
        int barX = bx + 76;
        int barW = px + pw - 50 - barX;
        for (int i = 0; i < Math.min(top.size(), 22) && by < py + ph - 32; i++) {
            Map.Entry<String, Integer> entry = top.get(i);
            CritterDex.Rarity rarity = CritterDex.rarityOf(entry.getKey());
            g.text(font, rarity.colour + entry.getKey(), bx, by, TEXT);
            int w = Math.max(1, Math.round(barW * (float) entry.getValue() / max));
            g.fill(barX, by + 1, barX + w, by + 8, rarityArgb(rarity));
            g.text(font, "§7" + String.format(Locale.ROOT, "%,d", entry.getValue()), barX + w + 4, by, MUTED);
            by += 11;
        }
        if (top.isEmpty()) {
            g.text(font, "§7Nothing yet.", bx, by, MUTED);
        }
    }

    private static int rarityArgb(CritterDex.Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 0xC0DDDDDD;
            case UNCOMMON -> 0xC055FF55;
            case RARE -> 0xC05555FF;
            case EPIC -> 0xC0AA00AA;
            case LEGENDARY -> 0xC0FFAA00;
        };
    }

    // ------------------------------------------------------------------ widgets

    private void button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable action) {
        if (action == null) {
            return;
        }
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 15;
        g.fill(x, y, x + w, y + 15, hover ? 0xC0403320 : 0xC0201A12);
        g.outline(x, y, w, 15, hover ? 0xFFFFD27A : BORDER);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 4, TEXT);
        hits.add(new Hit(x, y, w, 15, action));
    }

    private void toggle(GuiGraphicsExtractor g, int mx, int my, int x, int y, String label, boolean on, Runnable action) {
        int w = 14 + font.width(label);
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 15;
        g.outline(x, y + 3, 9, 9, hover ? 0xFFFFD27A : 0xFFBBBBBB);
        if (on) {
            g.fill(x + 2, y + 5, x + 7, y + 10, 0xFF55FF55);
        }
        g.text(font, label, x + 13, y + 4, TEXT);
        hits.add(new Hit(x, y, w, 15, action));
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double fraction, int colour) {
        g.fill(x, y, x + w, y + h, 0x60000000);
        g.fill(x, y, x + (int) Math.round(w * Math.max(0, Math.min(1, fraction))), y + h, colour);
    }

    private void scrollbar(GuiGraphicsExtractor g, int offset) {
        scrollbarAt(g, offset, px + pw - 8);
    }

    private void scrollbarAt(GuiGraphicsExtractor g, int offset, int x) {
        if (listSize <= listRows) {
            return;
        }
        int track = listBottom - listTop;
        int thumb = Math.max(10, track * listRows / listSize);
        int top = listTop + (track - thumb) * offset / Math.max(1, listSize - listRows);
        g.fill(x, listTop, x + 2, listBottom, 0x40FFFFFF);
        g.fill(x, top, x + 2, top + thumb, 0xC0E8A33A);
    }

    private int clampScroll(Tab t) {
        int max = Math.max(0, listSize - listRows);
        int value = Math.max(0, Math.min(max, scroll.getOrDefault(t, 0)));
        scroll.put(t, value);
        return value;
    }

    /**
     * How wide text will actually come out. Bold text is given a pixel per character on top of what
     * the font reports: some fonts (resource packs especially) draw bold wider than they measure it,
     * which put the rarity label on top of the achievement's name.
     */
    private int shownWidth(String text) {
        int width = font.width(text);
        if (text.contains("§l")) {
            width += EntityDataProbe.stripFormatting(text).length();
        }
        return width;
    }

    /** {@code prefix + text}, shortened with an ellipsis until it fits in {@code width} pixels. */
    private String cut(String prefix, String text, int width) {
        if (shownWidth(prefix + text) <= width) {
            return prefix + text;
        }
        String shown = text;
        while (!shown.isEmpty() && shownWidth(prefix + shown + "…") > width) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return prefix + shown.stripTrailing() + "…";
    }

    /** The scale that fits {@code needed} pixels into {@code available}, never below 0.6. */
    private static float fit(int needed, int available) {
        return needed <= available ? 1f : Math.max(0.6f, (float) available / needed);
    }

    /**
     * Text in a pastel rainbow: each character's hue comes from where it sits across the screen,
     * drifting slowly with time, so a whole row reads as one shimmering gradient.
     */
    private void rainbow(GuiGraphicsExtractor g, String text, int x, int y) {
        float drift = (System.currentTimeMillis() % 6000L) / 6000f;
        int cx = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float hue = (cx / 140f + drift) % 1f;
            g.text(font, ch, cx, y, 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.45f, 1.0f));
            cx += font.width(ch);
            i += Character.charCount(cp);
        }
    }

    private void centred(GuiGraphicsExtractor g, String text, int cx, int y, int colour) {
        g.text(font, text, cx - font.width(text) / 2, y, colour);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(event.x(), event.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int step = hasShiftDown() ? listRows : 3;
            scroll.merge(tab, scrollY > 0 ? -step : step, Integer::sum);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static boolean hasShiftDown() {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                net.minecraft.client.Minecraft.getInstance().getWindow(), com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT);
    }

    // ------------------------------------------------------------------ formatting

    /** {@code 950}, {@code 12.5k}, {@code 100M}, {@code 1B}. */
    private static String compact(long n) {
        if (n >= 1_000_000_000L) {
            return trim(n / 1e9) + "B";
        }
        if (n >= 1_000_000L) {
            return trim(n / 1e6) + "M";
        }
        if (n >= 10_000L) {
            return trim(n / 1e3) + "k";
        }
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** {@code 2:38}, or {@code 1:02:38} past an hour. */
    private static String clock(long millis) {
        long s = Math.max(0, millis) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** {@code 116H 41M}. */
    private static String hours(long millis) {
        long minutes = millis / 60_000L;
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
