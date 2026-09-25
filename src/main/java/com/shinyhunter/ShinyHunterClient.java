package com.shinyhunter;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shinyhunter.gui.ConfigScreen;
import com.shinyhunter.gui.HudEditorScreen;
import com.shinyhunter.gui.HudOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class ShinyHunterClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Shiny Hunter");

    private static final String PREFIX = "§b[Shiny Hunter]§r ";

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(ShinyScanner::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(PaintingHider::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(ChunkReloader::onClientTick);
        ChunkReloader.register();
        // These four must stay on END_CLIENT_TICK specifically — that's the hook that lands inside
        // the per-tick gizmo collector scope the highlighters draw through.
        ClientTickEvents.END_CLIENT_TICK.register(BeeNestHighlighter::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(NestSurvey::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(HoneyHives::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(com.shinyhunter.gui.moul.MoulConfigBridge::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(BlockHighlighter::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(EntityHighlighter::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(FloorDropHighlighter::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(GroundObjectHighlighter::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(WaypointManager::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(SnoozlingWallMarkers::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(CritterTracker::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(WardenWatcher::onClientTick);

        ClientTickEvents.END_CLIENT_TICK.register(HotspotCoordinator::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(BeeNestTracker::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(BirdFoodWatcher::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(TimerWatcher::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(ContestTracker::onClientTick);
        // Before RunTracker: the last split of a run has to be taken before the run is declared over.
        ClientTickEvents.END_CLIENT_TICK.register(SplitTracker::onClientTick);
        // After the trackers it clears, so a run boundary wipes state the same tick it's detected.
        ClientTickEvents.END_CLIENT_TICK.register(RunTracker::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(PartyChat::onClientTick);

        HotspotAnnouncer.register();
        BeeNestTracker.register();
        ShardTradeWatcher.register();
        CapsuleAnnouncer.register();
        DowntimeTracker.register();
        SparklingHistory.register();
        BirdFoodWatcher.register();
        TimerWatcher.register();
        CritterTracker.register();
        QuestAccepter.register();
        GemWatcher.register();
        SafariGuard.register();
        SafariTimer.register();
        SplitTracker.register();
        RockmiteTracker.register();
        PartyChat.register();
        PartyDex.register();
        // Honeyhives: loot tracking from chat, and real beacon beams (ordinary
        // world rendering, hidden by terrain like any other block).
        HoneyHives.register();
        HiveBeacons.register();
        HudOverlay.register();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildCommand(literal("shinyhunter")));
            dispatcher.register(buildCommand(literal("shiny")));

            // "/sparkling Name" is "/shiny dex Name"; "/sparkling A B C" compares them.
            dispatcher.register(literal("sparkling")
                    // Bare "/sparkling" is your own stats, which is the common case.
                    .executes(c -> {
                        Minecraft self = Minecraft.getInstance();
                        if (self.player != null) {
                            PartyDex.inspect(self.player.getName().getString());
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(argument("players", StringArgumentType.greedyString()).executes(c -> {
                        List<String> names = new ArrayList<>();
                        for (String part : StringArgumentType.getString(c, "players").trim().split("\\s+")) {
                            if (!part.isEmpty()) {
                                names.add(part);
                            }
                        }
                        if (names.size() == 1 && names.get(0).equalsIgnoreCase("party")) {
                            PartyDex.announcePartyNow();
                        } else if (names.size() == 1) {
                            PartyDex.inspect(names.get(0));
                        } else if (!names.isEmpty()) {
                            PartyDex.compare(names);
                        }
                        return Command.SINGLE_SUCCESS;
                    })));
        });

        ShinyConfig config = ShinyConfig.get();
        Welcome.register();
        UpdateChecker.register();
        var all = ShinyScanner.allKeywords(config);
        var everywhere = ShinyScanner.activeKeywords(config, false);
        LOGGER.info("{} loaded — hunting {} (in {}), of which {} hunt everywhere.", Edition.NAME,
                all.isEmpty() ? "nothing (no keywords configured)" : all,
                config.huntLocation, everywhere.isEmpty() ? "none" : everywhere);
        LOGGER.info("Loaded {} keyword(s), {} entity tracker(s), {} block tracker(s), {} setting(s).",
                config.huntKeywords.size(), config.trackers.size(), config.blockTrackers.size(),
                ConfigScreen.options().size());
    }

    /**
     * The command surface is deliberately tiny: open the settings, or reset the two pieces of
     * per-round state that a player might genuinely need to clear mid-session. Everything else is
     * configured on the screen.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand(
            LiteralArgumentBuilder<FabricClientCommandSource> root) {
        return root
                .executes(ShinyHunterClient::openConfig)
                .then(literal("waypoints").executes(ShinyHunterClient::clearWaypoints))
                .then(literal("mark").executes(ShinyHunterClient::markWaypoint))
                .then(literal("hotspots").executes(ShinyHunterClient::resetHotspots))
                .then(literal("nests").executes(ShinyHunterClient::resetNests))
                .then(literal("hud").executes(ShinyHunterClient::openHudEditor))
                .then(literal("reloadkey").executes(c -> {
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> client.setScreenAndShow(new com.shinyhunter.gui.KeybindPromptScreen(
                            null, ChunkReloader.RELOAD_CHUNKS)));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("togglehud").executes(ShinyHunterClient::toggleHud))
                .then(literal("splits").executes(ShinyHunterClient::showSplits))
                .then(literal("resetpb").executes(ShinyHunterClient::resetBests))
                .then(literal("zone").executes(ShinyHunterClient::showZone))
                .then(literal("who").executes(ShinyHunterClient::listPresent))
                .then(literal("hives").executes(c -> {
                    for (String line : HoneyHives.status()) {
                        c.getSource().sendFeedback(Component.literal(line));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(literal("nestsurvey")
                        .then(literal("on").executes(c -> setSurvey(c, true)))
                        .then(literal("off").executes(c -> setSurvey(c, false)))
                        .then(literal("clear").executes(c -> {
                            NestSurvey.clear();
                            return feedback(c, "Nest survey cleared.");
                        }))
                        .executes(c -> {
                            for (String line : NestSurvey.status()) {
                                c.getSource().sendFeedback(Component.literal(line));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(literal("history")
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> showHistory(c, IntegerArgumentType.getInteger(c, "page"))))
                        .executes(c -> showHistory(c, 1)))
                .then(literal("help").executes(ShinyHunterClient::showHelp))
                .then(literal("dex")
                        .then(argument("player", StringArgumentType.word())
                                .executes(c -> {
                                    PartyDex.inspect(StringArgumentType.getString(c, "player"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .executes(ShinyHunterClient::showPartyDex));
    }

    private static int openConfig(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        // Deferred: a screen can't be opened while the command is still being dispatched, and there
        // is no getter for the current screen on this version, so the parent is always null.
        client.execute(() -> client.setScreenAndShow(
                com.shinyhunter.gui.moul.MoulConfigBridge.screen(null)));
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleHud(CommandContext<FabricClientCommandSource> context) {
        ShinyConfig config = ShinyConfig.get();
        config.showHud = !config.showHud;
        config.save();
        return feedback(context, "HUD " + (config.showHud ? "§ashown" : "§chidden") + "§r.");
    }

    /**
     * Drops a marker ten blocks ahead. Useful for flagging a spot by hand, and it's the quickest way
     * to confirm waypoints are drawing without waiting for something to be found.
     */
    private static int markWaypoint(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return feedback(context, "§cNot in a world.");
        }
        // Ten blocks out, so it isn't instantly inside the clear radius and removed again.
        var target = client.player.getEyePosition().add(client.player.getLookAngle().scale(10.0));
        var pos = net.minecraft.core.BlockPos.containing(target);
        WaypointManager.add(pos, "Marker");
        return feedback(context, "Marker at §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + "§r (" + WaypointManager.count() + " waypoint(s)).");
    }

    private static int clearWaypoints(CommandContext<FabricClientCommandSource> context) {
        int count = WaypointManager.count();
        WaypointManager.clear();
        return feedback(context, "Cleared §f" + count + "§r waypoint(s).");
    }

    private static int resetHotspots(CommandContext<FabricClientCommandSource> context) {
        HotspotCoordinator.reset();
        return feedback(context, "Hotspot claims cleared — next round starts fresh.");
    }

    private static int resetNests(CommandContext<FabricClientCommandSource> context) {
        int count = BeeNestTracker.emptiedCount();
        BeeNestTracker.clearEmptied();
        return feedback(context, "Restored §f" + count + "§r hidden bee nest(s).");
    }

    /**
     * Prints who the Safari Manager guard is counting. The guard counts loaded player entities and
     * discounts NPCs by their absence from the server player list, so when the tally looks wrong
     * this shows exactly which side of that split each name landed on.
     */
    private static int listPresent(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        ShinyConfig config = ShinyConfig.get();
        feedback(context, "Counting §f" + SafariGuard.presentPlayers(client) + "§r of §f"
                + PartyDex.partySize() + "§r within §f" + config.presenceRadius + "§r blocks"
                + (PartyDex.members().isEmpty() ? " §8(no party seen - run /p list)" : "")
                + (SafariGuard.kickPending() ? " §e(kick pending)" : "") + ":");
        for (String line : SafariGuard.presenceReport(client)) {
            context.getSource().sendFeedback(Component.literal("  " + line));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The current roster and skip list, without a lookup. */
    private static int showPartyDex(CommandContext<FabricClientCommandSource> context) {
        var members = PartyDex.members();
        feedback(context, "Party: §f" + (members.isEmpty() ? "nobody else" : String.join(", ", members))
                + "§r — " + PartyDex.dexes().size() + " looked up");
        var shared = PartyDex.shared();
        feedback(context, "Shared sparklings: §f" + (shared.isEmpty() ? "none" : String.join(", ", shared)));
        feedback(context, "§7/shiny dex <player> looks one player up and lists every dex path found.");
        return Command.SINGLE_SUCCESS;
    }

    private static int showHistory(CommandContext<FabricClientCommandSource> context, int page) {
        for (Component line : SparklingHistory.lines(page)) {
            context.getSource().sendFeedback(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Every command and party command, in one place. */
    private static int showHelp(CommandContext<FabricClientCommandSource> context) {
        String[][] slash = {
                {"/shiny", "open settings"},
                {"/shiny hud", "drag / resize HUD panels"},
                {"/shiny reloadkey", "pick the Reload chunks key"},
                {"/shiny togglehud", "show or hide the HUD"},
                {"/shiny dex <name>", "a player's Hunting level and sparklings"},
                {"/sparkling <name>", "same as /shiny dex"},
                {"/sparkling <a> <b> ...", "sparklings those players all have"},
                {"/sparkling party", "re-run the shared-sparklings announcement"},
                {"/shiny splits", "this run's splits and your bests"},
                {"/shiny resetpb", "clear saved best times"},
                {"/shiny mark", "drop a waypoint ahead of you"},
                {"/shiny waypoints", "clear waypoints"},
                {"/shiny hotspots", "reset hotspot claims"},
                {"/shiny nests", "restore hidden bee nests"},
                {"/shiny who", "who the Safari Manager guard is counting"},
                {"/shiny history [page]", "every sparkling caught, newest first"},
                {"/shiny nestsurvey [on|off|clear]", "record fixture bee nests to a file"},
                {"/shiny hives", "which honeyhives are full, and what's known about each"},
                {"/shiny zone", "the sidebar zone as the mod reads it"},
        };
        String[][] party = {
                {"!dt [reason]", "downtime after this run: holds the manager until you say r"},
                {"!timer <length>", "start a shared timer"},
                {"!missing [biome], !m [i|f|c|h]", "what this run is still missing"},
                {"!shiny <name>", "a player's sparklings, to the party"},
                {"!shared / !shared all", "sparklings the whole party has"},
                {"kick", "holds the Safari Manager shut"},
        };
        feedback(context, "§b§lCommands");
        for (String[] row : slash) {
            context.getSource().sendFeedback(Component.literal("  §f" + row[0] + " §8- §7" + row[1]));
        }
        feedback(context, "§b§lParty chat");
        for (String[] row : party) {
            context.getSource().sendFeedback(Component.literal("  §f" + row[0] + " §8- §7" + row[1]));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int openHudEditor(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new HudEditorScreen(null)));
        return Command.SINGLE_SUCCESS;
    }

    /** This run's splits and the bests behind them, without waiting for the HUD to have room. */
    private static int showSplits(CommandContext<FabricClientCommandSource> context) {
        ShinyConfig config = ShinyConfig.get();
        var mine = SplitTracker.ownBiomes();
        feedback(context, SafariTimer.isRunning()
                ? "Run §f" + SafariTimer.format(SafariTimer.elapsedMillis())
                        + "§r — yours: §f" + (mine.isEmpty() ? "none yet" : String.join(", ", mine))
                : "No run in progress. Saved bests:");
        context.getSource().sendFeedback(Component.literal(
                "  §7your catches: §f" + (SplitTracker.ownCatches().isEmpty()
                        ? "none" : SplitTracker.ownCatches().toString())));

        for (String key : SplitTracker.keys()) {
            Long now = SplitTracker.split(key);
            Long personal = config.personalBests.get(key);
            Long party = config.partyBests.get(key);
            context.getSource().sendFeedback(Component.literal(
                    "  §f" + key + ": "
                            + (now == null ? "§8--" : "§a" + SafariTimer.format(now))
                            + " §8(" + (personal == null ? "--" : SafariTimer.format(personal))
                            + " / " + (party == null ? "--" : SafariTimer.format(party)) + ")"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int resetBests(CommandContext<FabricClientCommandSource> context) {
        SplitTracker.clearBests();
        return feedback(context, "Cleared all personal and party bests.");
    }

    /**
     * The sidebar zone exactly as the mod reads it. The Safari and its entrance are separate zones
     * that read almost identically, so when a feature fires in the wrong one this settles which
     * string the mod actually saw.
     */
    private static int showZone(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        ShinyConfig config = ShinyConfig.get();
        String zone = SkyblockSidebar.zoneName(client);
        feedback(context, "Zone: §f\"" + zone + "\"");
        context.getSource().sendFeedback(Component.literal(
                "  §7hunting grounds (exact): "
                        + (SkyblockSidebar.isAtLocation(client, config.huntLocation) ? "§ayes" : "§cno")));
        context.getSource().sendFeedback(Component.literal(
                "  §7safari area (incl. entrance): "
                        + (SkyblockSidebar.isAtOrWithin(client, config.huntLocation) ? "§ayes" : "§cno")));
        return Command.SINGLE_SUCCESS;
    }

    private static int setSurvey(CommandContext<FabricClientCommandSource> context, boolean on) {
        ShinyConfig config = ShinyConfig.get();
        config.surveyNests = on;
        config.save();
        return feedback(context, "Nest survey " + (on ? "§aon" : "§coff") + "§r — walk "
                + config.surveyLocation + " and it fills config/shinyhunter-nests.json.");
    }

    private static int feedback(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(Component.literal(PREFIX + message));
        return Command.SINGLE_SUCCESS;
    }
}
