package com.shinyhunter.gui;

import com.shinyhunter.ContestTracker;
import com.shinyhunter.PartyDex;
import com.shinyhunter.CritterDex;
import com.shinyhunter.CritterTracker;
import com.shinyhunter.RockmiteTracker;
import com.shinyhunter.SafariTimer;
import com.shinyhunter.SnoozlingTracker;
import com.shinyhunter.SplitTracker;
import com.shinyhunter.ShardTradeWatcher;
import com.shinyhunter.ShinyConfig;
import com.shinyhunter.SkyblockSidebar;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The on-screen panels: shard trades, trackers, and one panel per biome listing the
 * critters still missing from this instance.
 *
 * <p>Each is a separate {@link net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement} so other
 * mods can reorder or remove them individually, and so one throwing can't take the rest down.
 *
 * <p><b>Stacking.</b> Independent elements have no shared layout, so they'd all draw on top of each
 * other at the configured corner. They cooperate through a running Y offset instead: the first panel
 * in the fixed order resets it each frame and every panel advances it by its own height, so panels
 * that draw nothing take up no space and the rest close the gap.
 */
public final class HudOverlay {

    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;
    private static final int GAP = 2;
    private static final int BACKGROUND = 0x90000000;

    /** Y offset for the next panel this frame. */
    private static int nextY;

    private static final List<HudPanel> PANELS = new ArrayList<>();

    private HudOverlay() {
    }

    public static void register() {
        PANELS.add(new HudPanel("timer", "Run timer", HudOverlay::timerLines));
        PANELS.add(new HudPanel("splits", "Biome splits", HudOverlay::splitLines));
        PANELS.add(new HudPanel("contest", "Miria's Contest", HudOverlay::contestLines));
        PANELS.add(new HudPanel("rockmites", "Rockmites", HudOverlay::rockmiteLines));
        PANELS.add(new HudPanel("trades", "Shard trades", HudOverlay::tradeLines));
        for (String biome : CritterDex.biomes()) {
            PANELS.add(new HudPanel("critters_" + biome.toLowerCase(),
                    biome + " critters", () -> critterLines(biome)));
        }

        for (int i = 0; i < PANELS.size(); i++) {
            HudPanel panel = PANELS.get(i);
            boolean first = i == 0;
            HudElementRegistry.addLast(id(panel.id()), (graphics, delta) -> render(graphics, panel, first));
        }
    }

    /** The panels, for the JARVIS adapter to expose. */
    public static List<HudPanel> panels() {
        return PANELS;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("shinyhunter", path);
    }

    private static void render(GuiGraphicsExtractor graphics, HudPanel panel, boolean first) {
        Minecraft client = Minecraft.getInstance();
        ShinyConfig config = ShinyConfig.get();

        if (first) {
            nextY = config.hudY;
        }

        // Hidden with the rest of the interface, in menus, and when not in a world.
        if (!config.showHud
                || client.player == null
                || client.options.hideGui
                || client.screen != null) {
            return;
        }

        List<String> lines = panel.lines();
        if (lines.isEmpty()) {
            return;
        }

        boolean placed = panel.isPlaced();
        int x = placed ? panel.x() : config.hudX;
        int y = placed ? panel.y() : nextY;
        float scale = panel.scale();

        int height;
        if (scale == 1.0f) {
            height = draw(graphics, client.font, x, y, lines);
        } else {
            // Scaling is applied around the panel's own corner so its stored position stays the
            // top-left of what's drawn, whatever size it's been set to.
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(scale, scale);
            height = Math.round(draw(graphics, client.font, 0, 0, lines) * scale);
            pose.popMatrix();
        }

        panel.recordSize(Math.round(measure(client.font, lines) * scale), height);

        // Only unplaced panels take part in the automatic stack; a placed one sits where it's put
        // and must not push the others around.
        if (!placed) {
            nextY += height + GAP;
        }
    }

    /** Width the panel would occupy, unscaled. */
    private static int measure(Font font, List<String> lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        return width + PADDING * 2;
    }

    // ------------------------------------------------------------------ panel contents

    /** The run clock, once the hunt has started. */
    private static List<String> timerLines() {
        List<String> lines = new ArrayList<>();
        ShinyConfig config = ShinyConfig.get();
        if (!config.showTimer) {
            return lines;
        }

        if (SafariTimer.isRunning()) {
            lines.add("§b§lRun §f" + SafariTimer.format(SafariTimer.elapsedMillis()));
        }
        return lines;
    }

    /**
     * One line per split, with the running clock shown for biomes still in progress so the panel
     * reads as a live scoreboard rather than filling in only at the end.
     */
    private static List<String> splitLines() {
        List<String> lines = new ArrayList<>();
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackSplits || !config.showSplitHud || !SafariTimer.isRunning()) {
            return lines;
        }

        lines.add("§b§lSplits");
        for (String key : SplitTracker.keys()) {
            Long done = SplitTracker.split(key);
            String time = done != null
                    ? "§a" + SafariTimer.format(done)
                    : "§7" + SafariTimer.format(SafariTimer.elapsedMillis());
            lines.add("§f" + key + ": " + time + bests(config, key));
        }
        return lines;
    }

    /** The "(personal / party)" tail. Omitted entirely when neither best has been set yet. */
    private static String bests(ShinyConfig config, String key) {
        Long personal = config.personalBests.get(key);
        Long party = config.partyBests.get(key);
        if (personal == null && party == null) {
            return "";
        }
        return " §8(" + (personal == null ? "--" : SafariTimer.format(personal))
                + " / " + (party == null ? "--" : SafariTimer.format(party)) + ")";
    }

    /**
     * Walls broken, out of five. Walls whose chunk isn't loaded are reported separately rather than
     * folded into either number — see {@link SnoozlingTracker} for why they can't be assumed broken.
     */
    /** Contest status for this window, and the time left until it ends. */
    /**
     * Whether the contest panel is allowed here. The schedule runs on the wall clock rather than
     * anything the server says, so showing it off Skyblock is a preference, not a lie.
     */
    private static boolean contestScopeAllows(Minecraft client, ShinyConfig config) {
        String scope = config.contestScope == null ? "skyblock" : config.contestScope.trim().toLowerCase();
        return switch (scope) {
            case "anywhere" -> true;
            case "hypixel" -> client.getCurrentServer() != null
                    && client.getCurrentServer().ip.toLowerCase().contains("hypixel");
            default -> SkyblockSidebar.inSkyblock(client);
        };
    }

    private static List<String> contestLines() {
        List<String> lines = new ArrayList<>();
        ShinyConfig config = ShinyConfig.get();
        Minecraft client = Minecraft.getInstance();

        if (!config.trackContest || !contestScopeAllows(client, config)) {
            return lines;
        }
        boolean complete = ContestTracker.isComplete();
        if (complete && config.hideContestWhenComplete) {
            return lines;
        }

        lines.add("§b§lCurrent Contest: " + (complete ? "§aComplete" : "§cIncomplete"));
        if (config.showContestTimer) {
            lines.add(ContestTracker.betweenContests()
                    ? "§7Next in §f" + ContestTracker.formatRemaining(ContestTracker.secondsToNextStart())
                    : "§7Ends in §f" + ContestTracker.formatRemaining(ContestTracker.secondsToEnd()));
        }
        return lines;
    }

    /** Mounds opened and Rockmites found, plus how many are still standing within render distance. */
    private static List<String> rockmiteLines() {
        List<String> lines = new ArrayList<>();
        ShinyConfig config = ShinyConfig.get();
        Minecraft client = Minecraft.getInstance();

        if (!config.trackRockmites
                || !SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            return lines;
        }

        if (RockmiteTracker.allOpened()) {
            lines.add("§b§lRockmites §a✔ all " + config.rockmiteMoundTotal + " opened");
        } else {
            lines.add("§b§lRockmites obtained: §f" + RockmiteTracker.obtained()
                    + " §8(" + RockmiteTracker.opened() + "/" + config.rockmiteMoundTotal + " opened)");
        }
        lines.add("§7Nearby: §f" + RockmiteTracker.nearby());
        return lines;
    }

    private static List<String> tradeLines() {
        List<ShardTradeWatcher.Offer> offers = ShardTradeWatcher.offers();
        List<String> lines = new ArrayList<>();
        if (offers.isEmpty()) {
            return lines;
        }

        lines.add("§b§lShard trades §7(" + offers.size() + ")");
        for (ShardTradeWatcher.Offer offer : offers) {
            String where = offer.pos() == null
                    ? "§8?"
                    : "§7" + offer.pos().getX() + ", " + offer.pos().getY() + ", " + offer.pos().getZ();
            lines.add("§f" + offer.shard() + " §7for §f" + offer.cost() + "  " + where);
        }
        return lines;
    }

    /**
     * Missing critters for one biome. Shown only in the Safari — outside it the instance's progress
     * is meaningless, and six panels following you round the hub would be noise. The entrance counts
     * as the Safari here, which is a knowing trade: see {@link SkyblockSidebar#isAtLocation}.
     */
    private static List<String> critterLines(String biome) {
        List<String> lines = new ArrayList<>();
        ShinyConfig config = ShinyConfig.get();
        Minecraft client = Minecraft.getInstance();

        if (!config.trackCritters
                || !config.showCritterHud
                // Absent from the map means shown: the default is everything on, and switching a
                // biome off is the deliberate act.
                || !config.critterHudBiomes.getOrDefault(biome, Boolean.TRUE)
                // The Safari, and knowingly its entrance too: separating the two cost more than
                // it was worth. See SkyblockSidebar.isAtLocation.
                || !SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            return lines;
        }

        List<String> missing = CritterTracker.missing(biome);
        int total = CritterDex.critters(biome).size();
        if (missing.isEmpty()) {
            // Worth keeping on screen: "done" is information too, and it stops the panels above
            // shuffling upward the moment a biome completes.
            lines.add("§b§l" + biome + " §a✔ clear");
            return lines;
        }

        lines.add("§b§l" + biome + " §7(" + (total - missing.size()) + "/" + total + ")");

        // A cap keeps the panel a predictable height while a biome is still wide open. The overflow
        // line is deliberately still a line, so the panel doesn't change size as names are ticked
        // off and the rest of the HUD stops shuffling under it.
        int max = config.critterHudMax.getOrDefault(biome, 0);
        boolean capped = max > 0 && missing.size() > max;
        int shown = capped ? max : missing.size();

        for (int i = 0; i < shown; i++) {
            lines.add("§7• §f" + missing.get(i));
        }
        if (capped) {
            lines.add("§8+ " + (missing.size() - shown) + " others...");
        }
        return lines;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws a panel and returns the height it occupied. */
    private static int draw(GuiGraphicsExtractor graphics, Font font, int x, int y, List<String> lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * LINE_HEIGHT + PADDING * 2;

        graphics.fill(x, y, x + width + PADDING * 2, y + height, BACKGROUND);
        int lineY = y + PADDING;
        for (String line : lines) {
            graphics.text(font, Component.literal(line), x + PADDING, lineY, 0xFFFFFFFF, true);
            lineY += LINE_HEIGHT;
        }
        return height;
    }
}
