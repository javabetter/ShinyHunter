package com.shinyhunter.gui;

import com.shinyhunter.ShinyConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The mod's settings screen, reachable from {@code /shiny} or the JARVIS config search that
 * Firmament provides ({@code /jarvis options}).
 *
 * <p>Rows are declared once in {@link #buildRows()} against getters and setters on
 * {@link ShinyConfig}, so adding a setting is a single line here and nothing else. Each row owns its
 * widget and a content-space Y offset; every frame the widgets are repositioned to
 * {@code top - scroll + offset} and hidden when they fall outside the viewport.
 *
 * <p>Hiding rather than removing off-screen widgets is what keeps clicks, focus and tooltips working
 * — vanilla widgets don't clip, so a scrolled-past button would otherwise still be clickable outside
 * the list area.
 */
public class ConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int SECTION_HEIGHT = 26;
    private static final int LIST_TOP = 40;
    private static final int FOOTER = 34;
    private static final int CONTROL_WIDTH = 110;
    private static final int PANEL_WIDTH = 460;

    private final Screen parent;

    /**
     * When set, {@link #buildRows()} only records what settings exist instead of building widgets.
     * That's what lets the option list be handed to JARVIS without a screen being open — the rows
     * are declared in exactly one place, so the catalogue can't drift from the real screen.
     */
    private List<Option> catalogue;

    /** One setting, as JARVIS sees it. */
    public record Option(String section, String label, String tooltip) {
    }

    /** One entry per line: a section heading, or a label plus its control. */
    private record Row(String label, String tooltip, AbstractWidget widget, int offsetY, boolean section) {
    }

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;

    private double scrollY;
    private double targetScrollY;

    /** Kept across rebuilds so typing in it doesn't lose the text or the caret. */
    private EditBox searchBox;
    private String searchTerm = "";

    /**
     * Heading waiting for its first visible row. Sections are written out lazily so that one whose
     * every row was filtered out never appears as an empty heading in the results.
     */
    private String pendingSection;

    /**
     * The section rows are currently being added to. Unlike {@link #pendingSection} this survives the
     * heading being written out, so every row in a section matches a search for the section's name —
     * not just the first one.
     */
    private String currentSection;

    /**
     * Set while a rebuild was caused by typing in the search box. {@link #rebuild()} is also used by
     * the add/remove buttons in the list sections, and those must not pull focus out of whichever
     * field the player is editing — only the search rebuild puts focus back.
     */
    private boolean rebuildingForSearch;

    private boolean draggingScrollbar;
    /** Where inside the thumb the drag began, so it doesn't snap its centre to the cursor. */
    private double scrollbarGrabOffset;

    public ConfigScreen(Screen parent) {
        this(parent, "");
    }

    /**
     * @param initialSearch pre-fills the search box, which is how JARVIS jumps to one setting:
     *                      searching a label leaves that row (and its section) on screen alone.
     */
    public ConfigScreen(Screen parent, String initialSearch) {
        super(Component.literal("Shiny Hunter"));
        this.parent = parent;
        this.searchTerm = initialSearch == null ? "" : initialSearch.trim().toLowerCase();
    }

    /** Every setting on this screen, in screen order, without building any of it. */
    public static List<Option> options() {
        ConfigScreen screen = new ConfigScreen(null);
        screen.catalogue = new ArrayList<>();
        try {
            screen.buildRows();
        } catch (RuntimeException e) {
            com.shinyhunter.ShinyHunterClient.LOGGER.warn("Couldn't build the settings catalogue", e);
        }
        return screen.catalogue;
    }

    /** True while only cataloguing; the list sections and widget building are skipped. */
    private boolean cataloguing() {
        return catalogue != null;
    }

    @Override
    protected void init() {
        rows.clear();
        contentHeight = 0;
        pendingSection = null;
        currentSection = null;

        if (searchBox == null) {
            searchBox = new EditBox(font, 0, 0, 160, 16, Component.literal("Search"));
            searchBox.setMaxLength(48);
            if (!searchTerm.isEmpty()) {
                searchBox.setValue(searchTerm);
            }
            searchBox.setHint(Component.literal("Search settings..."));
            searchBox.setResponder(value -> {
                String next = value == null ? "" : value.trim().toLowerCase();
                if (next.equals(searchTerm)) {
                    return;
                }
                searchTerm = next;
                // Results start at the top; the old offset means nothing against a new list.
                scrollY = 0;
                targetScrollY = 0;
                rebuildingForSearch = true;
                rebuild();
                rebuildingForSearch = false;
            });
        }
        searchBox.setPosition(width / 2 + PANEL_WIDTH / 2 - 168, 14);
        addRenderableWidget(searchBox);

        buildRows();

        for (Row row : rows) {
            if (row.widget() != null) {
                addRenderableWidget(row.widget());
            }
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());

        if (rebuildingForSearch) {
            // clearWidgets() drops the screen's focus; put it back so typing continues uninterrupted.
            setFocused(searchBox);
            searchBox.setFocused(true);
        }

        clampScroll();
    }

    // ------------------------------------------------------------------ row declarations

    private void buildRows() {
        ShinyConfig c = ShinyConfig.get();


        section("Detection");
        bool("Enabled", "Master switch for shiny hunting.", () -> c.enabled, v -> c.enabled = v);
        bool("Only in Skyblock", "Ignore everything outside Hypixel Skyblock.",
                () -> c.onlyInSkyblock, v -> c.onlyInSkyblock = v);
        bool("Waypoints", "Mark coordinates the mod picks out of party chat.",
                () -> c.waypointsEnabled, v -> c.waypointsEnabled = v);
        decimal("Waypoint clear radius", "Walking this close to a waypoint removes it.",
                () -> c.waypointClearRadius, v -> c.waypointClearRadius = v, 0.5, 32.0);
        text("Waypoint colour", "Waypoint colour as hex, e.g. FFDD55.",
                () -> c.waypointColor, v -> c.waypointColor = v);
        text("Hunt area", "Area keywords are confined to unless set to hunt everywhere.",
                () -> c.huntLocation, v -> c.huntLocation = v);
        number("Re-alert after (s)", "Silence before the same entity alerts again.",
                () -> c.renotifySeconds, v -> c.renotifySeconds = v, 1, 600);

        section("World");
        bool("Hide paintings in Safari", "Don't draw paintings anywhere in the Critter Safari.",
                () -> c.hidePaintingsInSafari, v -> c.hidePaintingsInSafari = v);
        number("Reload chunks repeat (ticks)", "Held Reload chunks key fires every this many ticks. Key: Controls or /shiny reloadkey.",
                () -> c.chunkReloadRepeatTicks, v -> c.chunkReloadRepeatTicks = v, 1, 20);

        section("Alerts");
        bool("Update notices", "Say in chat when a newer Shiny Hunter is out, with a link to it.",
                () -> c.checkForUpdates, v -> c.checkForUpdates = v);
        bool("Sound", "Play a sound on a find.", () -> c.playSound, v -> c.playSound = v);
        bool("Action bar", "Show finds on the action bar too.",
                () -> c.actionBar, v -> c.actionBar = v);

        section("Hotspots");
        bool("Relay hotspot", "Announce your hunting hotspot to chat.",
                () -> c.announceHotspot, v -> c.announceHotspot = v);
        text("Relay channel", "Chat command used to relay, e.g. pc / gc / ac.",
                () -> c.hotspotCommand, v -> c.hotspotCommand = v);
        bool("Split across party", "Give everyone a different hotspot automatically.",
                () -> c.coordinateHotspots, v -> c.coordinateHotspots = v);
        number("Round resets (min)", "Forget claims this long after the last one.",
                () -> c.hotspotRoundMinutes, v -> c.hotspotRoundMinutes = v, 1, 120);

        section("Bee nests");
        bool("Highlight nests", "Outline bee nests in the location below.",
                () -> c.highlightBeeNests, v -> c.highlightBeeNests = v);
        text("Location", "Sidebar zone the highlight is limited to.",
                () -> c.beeNestLocation, v -> c.beeNestLocation = v);
        text("Colour (hex)", "Outline colour, e.g. 66D9FF.",
                () -> c.beeNestColor, v -> c.beeNestColor = v);
        number("Search radius", "How far to look for nests, in blocks.",
                () -> c.beeNestScanRadius, v -> c.beeNestScanRadius = v, 8, 256);
        bool("Through walls", "Draw nest outlines through terrain (nests never move, so this is allowed).",
                () -> c.beeNestThroughWalls, v -> c.beeNestThroughWalls = v);
        bool("Hive beacons", "Beacon the honeyhives that still have honey, while the contest is unfinished.",
                () -> c.honeyHiveWaypoints, v -> c.honeyHiveWaypoints = v);
        bool("Only when incomplete", "Hide the hive beacons once the contest is complete.",
                () -> c.honeyHiveOnlyWhenIncomplete, v -> c.honeyHiveOnlyWhenIncomplete = v);
        text("Hive colour", "Beacon colour as hex, e.g. 55FF55.",
                () -> c.honeyHiveColor, v -> c.honeyHiveColor = v);
        number("Hive beam height", "How tall the beacon beam is, in blocks.",
                () -> c.honeyHiveBeamHeight, v -> c.honeyHiveBeamHeight = v, 1, 2048);
        bool("Hive outline", "Also outline the hive block itself.",
                () -> c.honeyHiveOutline, v -> c.honeyHiveOutline = v);
        bool("Hive outline through walls", "Show the box on the hive block through terrain.",
                () -> c.honeyHiveThroughWalls, v -> c.honeyHiveThroughWalls = v);
        number("Hive range", "How far away a hive can be and still get a beacon.",
                () -> c.honeyHiveRange, v -> c.honeyHiveRange = v, 16, 512);
        bool("Beacon unknown hives", "Also beacon hives whose state isn't known yet, dimmer.",
                () -> c.honeyHiveShowUnknown, v -> c.honeyHiveShowUnknown = v);
        bool("Nest survey", "Record fence+trapdoor bee nests to config/shinyhunter-nests.json.",
                () -> c.surveyNests, v -> c.surveyNests = v);
        text("Survey area", "Where the nest survey runs. Blank means anywhere in Skyblock.",
                () -> c.surveyLocation, v -> c.surveyLocation = v);
        bool("Share emptied", "Announce emptied nests and hide ones the party emptied.",
                () -> c.announceBeeEmptied, v -> c.announceBeeEmptied = v);
        text("Emptied channel", "Chat command for emptied call-outs.",
                () -> c.beeEmptiedChannel, v -> c.beeEmptiedChannel = v);

        section("Floor drops");
        bool("Highlight drops", "Outline clustered marker displays.",
                () -> c.highlightFloorDrops, v -> c.highlightFloorDrops = v);
        text("Item token", "Item id a drop's displays carry.",
                () -> c.floorDropItemToken, v -> c.floorDropItemToken = v);
        number("Cluster size", "Displays needed together to count as a drop.",
                () -> c.floorDropClusterSize, v -> c.floorDropClusterSize = v, 1, 16);
        text("Colour (hex)", "Drop outline colour.",
                () -> c.floorDropColor, v -> c.floorDropColor = v);

        section("Party coordination");
        bool("!dt command", "Handle !dt [reason] in party chat: hold the Safari Manager after the run.",
                () -> c.downtimeCommand, v -> c.downtimeCommand = v);
        text("Downtime channel", "Chat command for the downtime announcement.",
                () -> c.downtimeChannel, v -> c.downtimeChannel = v);
        bool("!timer command", "Handle !timer <length> in party chat and call out expiry.",
                () -> c.timerCommand, v -> c.timerCommand = v);
        text("Timer channel", "Chat command for timer expiry call-outs.",
                () -> c.timerChannel, v -> c.timerChannel = v);
        bool("Bird food call-outs", "Announce picking up bird food, and running out.",
                () -> c.announceBirdFood, v -> c.announceBirdFood = v);
        text("Bird food channel", "Chat command for bird food call-outs.",
                () -> c.birdFoodChannel, v -> c.birdFoodChannel = v);

        section("Quest prompts");
        bool("Click to accept", "Click anywhere with chat open to press the accept button. Use at your own risk.",
                () -> c.acceptQuestClicks, v -> c.acceptQuestClicks = v);
        text("Mob", "Whose dialogue prompt this applies to.",
                () -> c.questMobName, v -> c.questMobName = v);
        text("Accept button", "The option button to press.",
                () -> c.questAcceptLabel, v -> c.questAcceptLabel = v);

        section("Critters");
        bool("Track critters", "Record which critters turn up on this Safari instance.",
                () -> c.trackCritters, v -> c.trackCritters = v);
        bool("Missing HUD panels", "One panel per biome listing what's still missing.",
                () -> c.showCritterHud, v -> c.showCritterHud = v);
        bool("Highlight missing only", "Outline critters still outstanding; drop each when caught.",
                () -> c.highlightMissingCritters, v -> c.highlightMissingCritters = v);
        text("Missing colour", "Outline colour for outstanding critters, e.g. FFC94A.",
                () -> c.missingCritterColor, v -> c.missingCritterColor = v);
        text("Nest done critter", "Once caught, bee nest outlines change colour for the rest of the run.",
                () -> c.beeNestStopCritter, v -> c.beeNestStopCritter = v);
        bool("Hide nests when done", "Stop outlining nests once that critter is caught, rather than recolouring them.",
                () -> c.hideNestsWhenDone, v -> c.hideNestsWhenDone = v);
        text("Nest done colour", "Outline colour for nests after that critter is caught, e.g. FF4040.",
                () -> c.beeNestDoneColor, v -> c.beeNestDoneColor = v);
        section("Party sparklings");
        bool("Party dex", "Look up each member's Sparkling Critterdex as they join.",
                () -> c.partyDexEnabled, v -> c.partyDexEnabled = v);
        bool("Post shared to party", "Announce the shared sparklings in party chat when the party fills.",
                () -> c.partyDexAnnounce, v -> c.partyDexAnnounce = v);
        bool("!shared command", "Answer !shared in party chat, even with the party dex off.",
                () -> c.sharedCommand, v -> c.sharedCommand = v);
        bool("Skip shared", "Leave sparklings the whole party has off the missing list and HUD.",
                () -> c.partyDexSkipShared, v -> c.partyDexSkipShared = v);
        text("Dex path", "Profile path to the Sparkling Critterdex, once known. Blank = discover.",
                () -> c.sparklingDexPath, v -> c.sparklingDexPath = v);
        number("!shared max", "Most sparklings !shared lists before \"+N more\".",
                () -> c.sharedListMax, v -> c.sharedListMax = v, 1, 37);
        text("Useful sparklings", "Comma-separated list !shared reports; !shared all ignores it.",
                () -> String.join(", ", c.usefulSparklings),
                v -> {
                    c.usefulSparklings.clear();
                    for (String part : v.split(",")) {
                        if (!part.trim().isEmpty()) {
                            c.usefulSparklings.add(part.trim());
                        }
                    }
                });

        section("Miria's Contest");
        bool("Track contest", "Show whether this window's contest is complete.",
                () -> c.trackContest, v -> c.trackContest = v);
        bool("Hide when complete", "Hide the panel once the contest is complete.",
                () -> c.hideContestWhenComplete, v -> c.hideContestWhenComplete = v);
        text("Contest scope", "Where the contest panel shows: skyblock / hypixel / anywhere.",
                () -> c.contestScope, v -> c.contestScope = v);
        bool("Show timer", "Show the time left until the contest ends.",
                () -> c.showContestTimer, v -> c.showContestTimer = v);
        bool("Warn if incomplete", "Ring at each mark below while the contest is not complete.",
                () -> c.contestWarnEnabled, v -> c.contestWarnEnabled = v);
        text("Warn at (minutes)", "Minutes-left marks to ring at, e.g. 5, 3, 1.",
                () -> c.contestWarnMinutes, v -> c.contestWarnMinutes = v);
        bool("Warning popup", "Flash CONTEST INCOMPLETE! on screen at each warning.",
                () -> c.contestWarnTitle, v -> c.contestWarnTitle = v);
        text("Sound id", "Any sound id, e.g. minecraft:block.bell.use or minecraft:entity.player.levelup.",
                () -> c.contestSound, v -> c.contestSound = v);
        number("Sound volume %", "100 = one bell. 300 = three at once. Louder needs more copies, not a bigger number.",
                () -> c.contestSoundVolume, v -> c.contestSoundVolume = v, 0, 999999);

        section("Snoozling walls");
        bool("Wall waypoints", "Mark the Snoozling walls still standing, through terrain, while in the Safari.",
                () -> c.snoozlingWallWaypoints, v -> c.snoozlingWallWaypoints = v);
        text("Wall waypoint colour", "Hex colour, e.g. FF55FF.",
                () -> c.snoozlingWallColor, v -> c.snoozlingWallColor = v);

        section("Rockmites");
        bool("Track rockmites", "Count mounds opened and Rockmites found.",
                () -> c.trackRockmites, v -> c.trackRockmites = v);
        number("Mounds per run", "How many mounds an instance has.",
                () -> c.rockmiteMoundTotal, v -> c.rockmiteMoundTotal = v, 1, 200);

        section("Critter list");
        buildCritterHudRows(c);

        section("Timers and splits");
        bool("Run timer", "Show the run clock.",
                () -> c.showTimer, v -> c.showTimer = v);
        bool("Track splits", "Record per-biome split times and compare against bests.",
                () -> c.trackSplits, v -> c.trackSplits = v);
        bool("Splits on HUD", "Show the splits panel.",
                () -> c.showSplitHud, v -> c.showSplitHud = v);
        bool("Announce splits", "Say each split in chat as it is taken.",
                () -> c.announceSplits, v -> c.announceSplits = v);
        number("Own-biome catches", "Personal catches in a biome before its split counts as your PB.",
                () -> c.ownBiomeMinCatches, v -> c.ownBiomeMinCatches = v, 1, 20);

        section("Safari manager");
        bool("Guard Safari Manager", "Refuse the click until the whole party is here; sneak to bypass.",
                () -> c.guardSafariManager, v -> c.guardSafariManager = v);
        text("Manager name", "Nameplate text of the NPC to guard.",
                () -> c.safariManagerName, v -> c.safariManagerName = v);
        bool("Block on \"kick\"", "Also hold the manager shut when someone says kick in party chat.",
                () -> c.blockOnKickCall, v -> c.blockOnKickCall = v);
        number("Kick hold (s)", "How long a called kick keeps the manager blocked.",
                () -> c.kickBlockSeconds, v -> c.kickBlockSeconds = v, 1, 600);
        bool("Guard ticket item", "Also refuse the ticket itself, which is how a non-leader starts.",
                () -> c.guardTicketItem, v -> c.guardTicketItem = v);
        text("Ticket name", "Item-name text identifying a Safari ticket.",
                () -> c.ticketItemName, v -> c.ticketItemName = v);
        number("Presence radius", "How close a player must be to count as here.",
                () -> c.presenceRadius, v -> c.presenceRadius = v, 2, 64);
        bool("Instant party chat", "Send right away when nothing has been sent recently.",
                () -> c.instantPartyChat, v -> c.instantPartyChat = v);
        bool("Announce gems", "Say \"X Gem placed!\" when you place one.",
                () -> c.announceGems, v -> c.announceGems = v);
        text("Gem channel", "Chat command for gem call-outs.",
                () -> c.gemChannel, v -> c.gemChannel = v);
        bool("Announce all uniques", "Say \"All uniques caught!\" once every biome is complete.",
                () -> c.announceAllUniques, v -> c.announceAllUniques = v);
        text("Clear mark", "Symbol for a done biome in the !missing summary. Use a word if Hypixel "
                        + "strips the tick.",
                () -> c.clearMark, v -> c.clearMark = v);
        bool("Highlight shulkers", "Outline shulkers as \"Shulker\" until their name loads, then by name.",
                () -> c.shulkerIsHideonfloor, v -> c.shulkerIsHideonfloor = v);
        bool("Always highlight shulkers", "Keep shulkers outlined even once that critter is caught.",
                () -> c.alwaysHighlightShulkers, v -> c.alwaysHighlightShulkers = v);
        bool("Announce biome clear", "Say \"<biome> clear!\" once a biome is complete.",
                () -> c.announceBiomeClear, v -> c.announceBiomeClear = v);
        bool("Announce missing Macaw", "Say \"Forest 8/9, missing Macaw\" once only the Macaw is left.",
                () -> c.announceMacawOnly, v -> c.announceMacawOnly = v);
        bool("Answer !missing", "Reply with your missing critters when the party asks.",
                () -> c.respondToMissing, v -> c.respondToMissing = v);
        text("Critter channel", "Chat command for critter call-outs.",
                () -> c.critterChannel, v -> c.critterChannel = v);

        section("HUD");
        bool("Show HUD", "Draw the HUD panels on screen.",
                () -> c.showHud, v -> c.showHud = v);
        number("HUD x", "Distance from the left edge, in pixels.",
                () -> c.hudX, v -> c.hudX = v, 0, 2000);
        number("HUD y", "Distance from the top edge, in pixels.",
                () -> c.hudY, v -> c.hudY = v, 0, 2000);

        section("Ground objects (rocks, cases)");
        bool("Highlight objects", "Outline item displays paired with an interaction hitbox.",
                () -> c.highlightGroundObjects, v -> c.highlightGroundObjects = v);
        text("Colour (hex)", "Ground object outline colour.",
                () -> c.groundObjectColor, v -> c.groundObjectColor = v);
        number("Search radius", "How far to look for ground objects, in blocks.",
                () -> c.groundObjectScanRadius, v -> c.groundObjectScanRadius = v, 8, 128);
        bool("Announce coords", "Print coordinates in chat when one is spotted.",
                () -> c.groundObjectAnnounce, v -> c.groundObjectAnnounce = v);

        section("Shard trades");
        bool("Watch NPC trades", "Detect hunter shard offers and relay them.",
                () -> c.announceShardTrades, v -> c.announceShardTrades = v);
        text("Relay channel", "Chat command for trade call-outs, e.g. pc.",
                () -> c.shardTradeChannel, v -> c.shardTradeChannel = v);
        bool("Title if affordable", "Title you when a called-out trade's cost is in your inventory.",
                () -> c.titleWhenTradeAffordable, v -> c.titleWhenTradeAffordable = v);
        bool("Out of capsules", "Tell the party when you use your last Critter Capsule.",
                () -> c.announceOutOfCapsules, v -> c.announceOutOfCapsules = v);
        text("Capsule channel", "Chat command for the out-of-capsules call-out.",
                () -> c.capsuleChannel, v -> c.capsuleChannel = v);

        section("Warden");
        bool("Warden ready title", "Title when the warden's hitbox reaches full height.",
                () -> c.announceWardenReady, v -> c.announceWardenReady = v);
        number("Watch radius", "How far to watch for wardens, in blocks.",
                () -> c.wardenScanRadius, v -> c.wardenScanRadius = v, 8, 128);

        section("Entity highlights");
        bool("Highlight entities", "Outline the tracked entities below.",
                () -> c.highlightEntities, v -> c.highlightEntities = v);
        bool("Name labels", "Float a name above matches that have no nametag of their own.",
                () -> c.highlightLabels, v -> c.highlightLabels = v);
        text("Name label mode", "When to draw it: never / default (only if the game shows none) / always.",
                () -> c.highlightLabelMode, v -> c.highlightLabelMode = v);
        decimal("Outline padding", "Blocks added on every side of an outline so it stands clear of the mob.",
                () -> c.highlightBoxGrow, v -> c.highlightBoxGrow = v, 0.05, 1.0);
        decimal("Minimum outline size", "Small mobs get an outline at least this big, in blocks.",
                () -> c.highlightBoxMinSize, v -> c.highlightBoxMinSize = v, 0.0, 3.0);
        bool("Tint mobs", "Colour outlined mobs toward their outline colour.",
                () -> c.highlightTintMobs, v -> c.highlightTintMobs = v);
        bool("Ignore capture UI", "Don't treat the capsule's CAPTURING display as a mob.",
                () -> c.highlightIgnoreCapturing, v -> c.highlightIgnoreCapturing = v);
        bool("Match NBT too", "Also match item/NBT data for unnamed things like rocks.",
                () -> c.highlightDeepScan, v -> c.highlightDeepScan = v);

        section("Sparkling particles");
        bool("Particle detection", "Treat a trail of these particles around a mob as a sparkling.",
                () -> c.particleDetection, v -> c.particleDetection = v);
        decimal("Trail radius", "How close to the mob a particle must be to count.",
                () -> c.particleRadius, v -> c.particleRadius = v, 0.5, 16.0);
        number("Trail threshold", "Particle packets in the window before it counts.",
                () -> c.particleThreshold, v -> c.particleThreshold = v, 1, 500);
        number("Trail window (s)", "How far back the trail is counted.",
                () -> c.particleWindowSeconds, v -> c.particleWindowSeconds = v, 1, 5);
        if (sectionMatches("Sparkling particles")) {
            buildStringListRows(c.sparklingParticles, "particle", "minecraft:end_rod");
        }


        section("Tracked entities");
        if (sectionMatches("Tracked entities")) {
            buildTrackerRows(c);
        }

        section("Tracked blocks");
        bool("Highlight blocks", "Outline the block types listed below.",
                () -> c.highlightBlocks, v -> c.highlightBlocks = v);
        number("Search radius", "How far to look for tracked blocks, in blocks.",
                () -> c.blockScanRadius, v -> c.blockScanRadius = v, 8, 256);
        if (sectionMatches("Tracked blocks")) {
            buildBlockRows(c);
        }
    }

    /**
     * Tracked block types, grouped the same way entities are. Entries are matched as substrings of a
     * block's registry id, so "minecraft:bee_nest" and the looser "ore" both work.
     */
    private void buildBlockRows(ShinyConfig c) {
        if (cataloguing()) {
            return; // list editors have no single label to catalogue
        }
        flushSection();
        int labelX = width / 2 - PANEL_WIDTH / 2 + 8;

        Map<String, List<ShinyConfig.BlockTracker>> groups = new LinkedHashMap<>();
        for (ShinyConfig.BlockTracker tracker : c.blockTrackers) {
            String group = tracker.group == null || tracker.group.isBlank()
                    ? ShinyConfig.DEFAULT_GROUP
                    : tracker.group.trim();
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(tracker);
        }

        for (Map.Entry<String, List<ShinyConfig.BlockTracker>> group : groups.entrySet()) {
            buildBlockGroupHeader(labelX, group.getKey(), group.getValue());
            for (ShinyConfig.BlockTracker tracker : group.getValue()) {
                buildBlockRow(labelX, tracker);
            }
        }

        if (c.blockTrackers.isEmpty()) {
            rows.add(new Row("No block types tracked", null, null, contentHeight, false));
            contentHeight += ROW_HEIGHT;
        }

        Button add = Button.builder(Component.literal("+  Add tracked block"), b -> {
                    ShinyConfig config = ShinyConfig.get();
                    config.blockTrackers.add(
                            new ShinyConfig.BlockTracker(uniqueBlockId(config)));
                    config.save();
                    rebuild();
                })
                .bounds(labelX, 0, 200, 20).build();
        rows.add(new Row(null, null, add, contentHeight, false));
        contentHeight += ROW_HEIGHT + 4;
    }

    private void buildBlockGroupHeader(int labelX, String name, List<ShinyConfig.BlockTracker> members) {
        int y = contentHeight;
        int enabled = 0;
        for (ShinyConfig.BlockTracker tracker : members) {
            if (tracker.enabled) {
                enabled++;
            }
        }

        rows.add(new Row(name + "  §8(" + enabled + "/" + members.size() + ")", null, null, y, true));

        Button all = Button.builder(Component.literal("All on"), b -> {
                    for (ShinyConfig.BlockTracker tracker : members) {
                        tracker.enabled = true;
                    }
                    ShinyConfig.get().save();
                    rebuild();
                })
                .bounds(labelX + PANEL_WIDTH - 150, 0, 52, 16).build();
        rows.add(new Row(null, null, all, y, false));

        Button none = Button.builder(Component.literal("All off"), b -> {
                    for (ShinyConfig.BlockTracker tracker : members) {
                        tracker.enabled = false;
                    }
                    ShinyConfig.get().save();
                    rebuild();
                })
                .bounds(labelX + PANEL_WIDTH - 94, 0, 52, 16).build();
        rows.add(new Row(null, null, none, y, false));

        contentHeight += SECTION_HEIGHT;
    }

    private void buildBlockRow(int labelX, ShinyConfig.BlockTracker tracker) {
        int y = contentHeight;

        EditBox id = new EditBox(font, labelX + 8, 0, 190, 18, Component.literal("Block id"));
        id.setValue(tracker.blockId == null ? "" : tracker.blockId);
        id.setMaxLength(64);
        // Captures the tracker itself, not its index, so edits elsewhere can't hit the wrong row.
        id.setResponder(v -> {
            tracker.blockId = v;
            ShinyConfig.get().save();
        });
        id.setTooltip(Tooltip.create(Component.literal(
                "Block registry id, or part of one — e.g. minecraft:bee_nest, or just ore")));
        rows.add(new Row(null, null, id, y, false));

        EditBox group = new EditBox(font, labelX + 202, 0, 84, 18, Component.literal("Group"));
        group.setValue(tracker.group == null ? "" : tracker.group);
        group.setMaxLength(32);
        group.setResponder(v -> {
            tracker.group = v;
            ShinyConfig.get().save();
        });
        group.setTooltip(Tooltip.create(Component.literal(
                "Group name. The list regroups when you reopen this screen.")));
        rows.add(new Row(null, null, group, y, false));

        EditBox colour = new EditBox(font, labelX + 290, 0, 46, 18, Component.literal("Hex"));
        colour.setValue(tracker.color == null ? "" : tracker.color);
        colour.setMaxLength(8);
        colour.setResponder(v -> {
            tracker.color = v;
            ShinyConfig.get().save();
        });
        colour.setTooltip(Tooltip.create(Component.literal("Outline colour, e.g. 66D9FF")));
        rows.add(new Row(null, null, colour, y, false));

        Button toggle = Button.builder(
                        Component.literal(tracker.enabled ? "On" : "Off"),
                        b -> {
                            tracker.enabled = !tracker.enabled;
                            b.setMessage(Component.literal(tracker.enabled ? "On" : "Off"));
                            ShinyConfig.get().save();
                        })
                .bounds(labelX + 340, 0, 34, 18).build();
        rows.add(new Row(null, null, toggle, y, false));

        Button remove = Button.builder(Component.literal("x"), b -> {
                    ShinyConfig config = ShinyConfig.get();
                    config.blockTrackers.remove(tracker);
                    config.save();
                    rebuild();
                })
                .bounds(labelX + 382, 0, 18, 18).build();
        remove.setTooltip(Tooltip.create(Component.literal("Remove this block type")));
        rows.add(new Row(null, null, remove, y, false));

        contentHeight += ROW_HEIGHT;
    }

    /** Avoids two new entries sharing a placeholder id. */
    private static String uniqueBlockId(ShinyConfig config) {
        String base = "minecraft:bee_nest";
        outer:
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? base : base + " " + n;
            for (ShinyConfig.BlockTracker tracker : config.blockTrackers) {
                if (candidate.equalsIgnoreCase(tracker.blockId == null ? "" : tracker.blockId.trim())) {
                    continue outer;
                }
            }
            return candidate;
        }
    }

    /**
     * One row per hunted keyword. An entity matching any of them raises the find alert; with none
     * configured nothing is hunted at all, which is deliberate — a blank keyword used to match every
     * entity in the world, since every string contains the empty string.
     */
    /**
     * One show/hide switch and one length cap per biome. Built rather than declared because the
     * biome list belongs to {@link CritterDex}, and duplicating it here would let the two drift.
     */
    private void buildCritterHudRows(ShinyConfig c) {
        if (cataloguing()) {
            return; // list editors have no single label to catalogue
        }
        flushSection();
        for (String biome : com.shinyhunter.CritterDex.biomes()) {
            bool(biome, "Show the " + biome + " missing-critter panel.",
                    () -> c.critterHudBiomes.getOrDefault(biome, Boolean.TRUE),
                    v -> c.critterHudBiomes.put(biome, v));
            number(biome + " max", "Longest the " + biome + " list gets before the rest collapse "
                            + "into \"+ N others...\". 0 means no limit.",
                    () -> c.critterHudMax.getOrDefault(biome, 0),
                    v -> c.critterHudMax.put(biome, v), 0, 20);
        }
    }

    /**
     * Rows for a plain list of strings — one edit box and a remove button each, plus an add
     * button. Used for the particle and texture lists, which have no per-entry options.
     */
    private void buildStringListRows(java.util.List<String> list, String what, String template) {
        if (cataloguing()) {
            return; // list editors have no single label to catalogue
        }
        flushSection();
        int labelX = width / 2 - PANEL_WIDTH / 2 + 8;

        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            int y = contentHeight;

            EditBox box = new EditBox(font, labelX, 0, 330, 18, Component.literal(what));
            box.setValue(list.get(i));
            box.setMaxLength(128);
            box.setResponder(v -> {
                if (index < list.size()) {
                    list.set(index, v.trim());
                    ShinyConfig.get().save();
                }
            });
            rows.add(new Row(null, null, box, y, false));

            Button remove = Button.builder(Component.literal("x"), b -> {
                        if (index < list.size()) {
                            list.remove(index);
                        }
                        ShinyConfig.get().save();
                        rebuild();
                    })
                    .bounds(labelX + 336, 0, 20, 18).build();
            remove.setTooltip(Tooltip.create(Component.literal("Remove this " + what)));
            rows.add(new Row(null, null, remove, y, false));

            contentHeight += ROW_HEIGHT;
        }

        Button add = Button.builder(Component.literal("+  Add " + what), b -> {
                    list.add(template);
                    ShinyConfig.get().save();
                    rebuild();
                })
                .bounds(labelX, 0, 200, 20).build();
        rows.add(new Row(null, null, add, contentHeight, false));
        contentHeight += ROW_HEIGHT + 4;
    }

    /**
     * Tracked entities, bundled under their group with a switch that flips the whole group at once.
     *
     * <p>Groups are just the free-text {@code group} field, gathered in first-appearance order so
     * the list doesn't reshuffle alphabetically as things are renamed. There's no separate
     * group-enabled state to drift out of sync — the group buttons simply set every member's own
     * flag, which stays the single source of truth.
     */
    private void buildTrackerRows(ShinyConfig c) {
        if (cataloguing()) {
            return; // list editors have no single label to catalogue
        }
        flushSection();
        int labelX = width / 2 - PANEL_WIDTH / 2 + 8;

        Map<String, List<ShinyConfig.Tracker>> groups = new LinkedHashMap<>();
        for (ShinyConfig.Tracker tracker : c.trackers) {
            String group = tracker.group == null || tracker.group.isBlank()
                    ? ShinyConfig.DEFAULT_GROUP
                    : tracker.group.trim();
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(tracker);
        }

        for (Map.Entry<String, List<ShinyConfig.Tracker>> group : groups.entrySet()) {
            buildGroupHeader(labelX, group.getKey(), group.getValue());
            for (ShinyConfig.Tracker tracker : group.getValue()) {
                buildTrackerRow(labelX, tracker);
            }
        }

        Button add = Button.builder(Component.literal("+  Add tracked entity"), b -> {
                    ShinyConfig config = ShinyConfig.get();
                    config.trackers.add(new ShinyConfig.Tracker(uniqueName(config), ShinyConfig.DEFAULT_GROUP));
                    config.save();
                    rebuild();
                })
                .bounds(labelX, 0, 200, 20).build();
        rows.add(new Row(null, null, add, contentHeight, false));
        contentHeight += ROW_HEIGHT + 4;
    }

    private void buildGroupHeader(int labelX, String name, List<ShinyConfig.Tracker> members) {
        int y = contentHeight;
        int enabled = 0;
        for (ShinyConfig.Tracker tracker : members) {
            if (tracker.enabled) {
                enabled++;
            }
        }

        rows.add(new Row(name + "  §8(" + enabled + "/" + members.size() + ")", null, null, y, true));

        Button all = Button.builder(Component.literal("All on"), b -> {
                    setGroup(members, true);
                    rebuild();
                })
                .bounds(labelX + PANEL_WIDTH - 150, 0, 52, 16).build();
        all.setTooltip(Tooltip.create(Component.literal("Enable every tracker in " + name)));
        rows.add(new Row(null, null, all, y, false));

        Button none = Button.builder(Component.literal("All off"), b -> {
                    setGroup(members, false);
                    rebuild();
                })
                .bounds(labelX + PANEL_WIDTH - 94, 0, 52, 16).build();
        none.setTooltip(Tooltip.create(Component.literal("Disable every tracker in " + name)));
        rows.add(new Row(null, null, none, y, false));

        contentHeight += SECTION_HEIGHT;
    }

    private static void setGroup(List<ShinyConfig.Tracker> members, boolean enabled) {
        for (ShinyConfig.Tracker tracker : members) {
            tracker.enabled = enabled;
        }
        ShinyConfig.get().save();
    }

    private void buildTrackerRow(int labelX, ShinyConfig.Tracker tracker) {
        int y = contentHeight;

        EditBox name = new EditBox(font, labelX + 8, 0, 122, 18, Component.literal("Name"));
        name.setValue(tracker.token == null ? "" : tracker.token);
        name.setMaxLength(64);
        // Responders capture the tracker itself, not its index, so a list edit elsewhere can't make
        // this write to the wrong row.
        name.setResponder(v -> {
            tracker.token = v;
            ShinyConfig.get().save();
        });
        rows.add(new Row(null, null, name, y, false));

        EditBox group = new EditBox(font, labelX + 134, 0, 84, 18, Component.literal("Group"));
        group.setValue(tracker.group == null ? "" : tracker.group);
        group.setMaxLength(32);
        group.setResponder(v -> {
            tracker.group = v;
            ShinyConfig.get().save();
        });
        group.setTooltip(Tooltip.create(Component.literal(
                "Group name. The list regroups when you reopen this screen.")));
        rows.add(new Row(null, null, group, y, false));

        EditBox colour = new EditBox(font, labelX + 222, 0, 46, 18, Component.literal("Hex"));
        colour.setValue(tracker.color == null ? "" : tracker.color);
        colour.setMaxLength(8);
        colour.setResponder(v -> {
            tracker.color = v;
            ShinyConfig.get().save();
        });
        colour.setTooltip(Tooltip.create(Component.literal("Outline colour, e.g. FFAA33")));
        rows.add(new Row(null, null, colour, y, false));

        Button toggle = Button.builder(
                        Component.literal(tracker.enabled ? "On" : "Off"),
                        b -> {
                            tracker.enabled = !tracker.enabled;
                            b.setMessage(Component.literal(tracker.enabled ? "On" : "Off"));
                            ShinyConfig.get().save();
                        })
                .bounds(labelX + 272, 0, 34, 18).build();
        toggle.setTooltip(Tooltip.create(Component.literal("Enable or disable this tracker")));
        rows.add(new Row(null, null, toggle, y, false));

        Button announce = Button.builder(
                        Component.literal(tracker.announce ? "Announce" : "Silent"),
                        b -> {
                            tracker.announce = !tracker.announce;
                            b.setMessage(Component.literal(tracker.announce ? "Announce" : "Silent"));
                            ShinyConfig.get().save();
                        })
                .bounds(labelX + 310, 0, 68, 18).build();
        announce.setTooltip(Tooltip.create(
                Component.literal("Announce coordinates in chat when one is found")));
        rows.add(new Row(null, null, announce, y, false));

        Button remove = Button.builder(Component.literal("x"), b -> {
                    ShinyConfig config = ShinyConfig.get();
                    config.trackers.remove(tracker);
                    config.save();
                    rebuild();
                })
                .bounds(labelX + 382, 0, 18, 18).build();
        remove.setTooltip(Tooltip.create(Component.literal("Remove this tracker")));
        rows.add(new Row(null, null, remove, y, false));

        contentHeight += ROW_HEIGHT;
    }

    /** Avoids handing two new trackers the same placeholder, which load-time dedupe would collapse. */
    private static String uniqueName(ShinyConfig config) {
        String base = "New entity";
        outer:
        for (int n = 1; ; n++) {
            String candidate = n == 1 ? base : base + " " + n;
            for (ShinyConfig.Tracker tracker : config.trackers) {
                if (candidate.equalsIgnoreCase(tracker.token == null ? "" : tracker.token.trim())) {
                    continue outer;
                }
            }
            return candidate;
        }
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    // ------------------------------------------------------------------ row builders

    /**
     * The party privacy switch, first thing on the screen because it changes what leaves your client
     * and is the one setting worth checking before joining strangers.
     */
    private void section(String title) {
        pendingSection = title;
        currentSection = title;
    }

    /** Writes out the waiting heading, if any. Called just before the first row beneath it. */
    private void flushSection() {
        if (pendingSection != null) {
            rows.add(new Row(pendingSection, null, null, contentHeight, true));
            contentHeight += SECTION_HEIGHT;
            pendingSection = null;
        }
    }

    /** True when no search is active, or the given text matches it. */
    private boolean matchesSearch(String... texts) {
        if (searchTerm.isEmpty()) {
            return true;
        }
        for (String text : texts) {
            if (text != null && text.toLowerCase().contains(searchTerm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a list section (keywords, trackers, blocks) should be built. Their rows carry no
     * labels to search, so the section is matched by its own name — searching for "tracker" finds
     * the list, and searching for a setting name doesn't drag the whole list along with it.
     */
    private boolean sectionMatches(String title) {
        return matchesSearch(title);
    }

    private void bool(String label, String tip, BooleanSupplier get, Consumer<Boolean> set) {
        if (cataloguing()) {
            addRow(label, tip, null);
            return;
        }
        int x = width / 2 + PANEL_WIDTH / 2 - CONTROL_WIDTH - 8;
        Button button = Button.builder(Component.literal(get.getAsBoolean() ? "On" : "Off"), b -> {
            boolean value = !get.getAsBoolean();
            set.accept(value);
            b.setMessage(Component.literal(value ? "On" : "Off"));
            ShinyConfig.get().save();
        }).bounds(x, 0, CONTROL_WIDTH, 18).build();
        addRow(label, tip, button);
    }

    private void text(String label, String tip, Supplier<String> get, Consumer<String> set) {
        if (cataloguing()) {
            addRow(label, tip, null);
            return;
        }
        int x = width / 2 + PANEL_WIDTH / 2 - CONTROL_WIDTH - 8;
        EditBox box = new EditBox(font, x, 0, CONTROL_WIDTH, 18, Component.literal(label));
        // Long enough for the list-shaped settings: 64 silently cut "Useful sparklings" short, so
        // every name past the cut stopped counting as useful.
        box.setMaxLength(512);
        box.setValue(get.get() == null ? "" : get.get());
        // Written straight through on every keystroke; the file write is cheap and this avoids an
        // apply step that's easy to forget.
        box.setResponder(v -> {
            set.accept(v);
            ShinyConfig.get().save();
        });
        addRow(label, tip, box);
    }

    private void number(String label, String tip, IntSupplier get, IntConsumer set, int min, int max) {
        if (cataloguing()) {
            addRow(label, tip, null);
            return;
        }
        int x = width / 2 + PANEL_WIDTH / 2 - CONTROL_WIDTH - 8;
        EditBox box = new EditBox(font, x, 0, CONTROL_WIDTH, 18, Component.literal(label));
        box.setMaxLength(6);
        box.setValue(Integer.toString(get.getAsInt()));
        box.setResponder(v -> {
            // Ignore anything unparseable or out of range instead of fighting the user mid-type —
            // an empty box while they retype a number must not become a zero.
            try {
                int parsed = Integer.parseInt(v.trim());
                if (parsed >= min && parsed <= max) {
                    set.accept(parsed);
                    ShinyConfig.get().save();
                }
            } catch (NumberFormatException ignored) {
                // keep the previous value
            }
        });
        addRow(label, tip + "  (" + min + "-" + max + ")", box);
    }

    /** Same as {@link #number} but for fractional values such as a radius. */
    private void decimal(String label, String tip, DoubleSupplier get, DoubleConsumer set,
                         double min, double max) {
        if (cataloguing()) {
            addRow(label, tip, null);
            return;
        }
        int x = width / 2 + PANEL_WIDTH / 2 - CONTROL_WIDTH - 8;
        EditBox box = new EditBox(font, x, 0, CONTROL_WIDTH, 18, Component.literal(label));
        box.setMaxLength(8);
        box.setValue(trimTrailingZero(get.getAsDouble()));
        box.setResponder(v -> {
            // Out-of-range and half-typed values are ignored rather than clamped, so the box doesn't
            // fight the user mid-edit — "1" on the way to "12" must not stick as 1.
            try {
                double parsed = Double.parseDouble(v.trim());
                if (parsed >= min && parsed <= max) {
                    set.accept(parsed);
                    ShinyConfig.get().save();
                }
            } catch (NumberFormatException ignored) {
                // keep the previous value
            }
        });
        addRow(label, tip + "  (" + trimTrailingZero(min) + "-" + trimTrailingZero(max) + ")", box);
    }

    /** "3.0" reads better as "3" in a settings box. */
    private static String trimTrailingZero(double value) {
        return value == Math.rint(value)
                ? Integer.toString((int) value)
                : Double.toString(value);
    }

    private void addRow(String label, String tip, AbstractWidget widget) {
        if (cataloguing()) {
            catalogue.add(new Option(currentSection, label, tip));
            return;
        }
        // Matched against the tooltip as well as the label, so searching for what a setting *does*
        // finds it even when the label is terse.
        if (!matchesSearch(label, tip, currentSection)) {
            return;
        }
        if (tip != null) {
            widget.setTooltip(Tooltip.create(Component.literal(tip)));
        }
        flushSection();
        rows.add(new Row(label, tip, widget, contentHeight, false));
        contentHeight += ROW_HEIGHT;
    }

    // ------------------------------------------------------------------ scrolling

    private int listBottom() {
        return height - FOOTER;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight - (listBottom() - LIST_TOP));
    }

    private void clampScroll() {
        double max = maxScroll();
        targetScrollY = Math.max(0, Math.min(max, targetScrollY));
        scrollY = Math.max(0, Math.min(max, scrollY));
    }

    private void updateScroll() {
        // Ease toward the target so the wheel feels smooth rather than stepping.
        scrollY += (targetScrollY - scrollY) * 0.5;
        if (Math.abs(targetScrollY - scrollY) < 0.4) {
            scrollY = targetScrollY;
        }
    }

    private void layoutRows() {
        int top = LIST_TOP;
        int bottom = listBottom();
        for (Row row : rows) {
            AbstractWidget widget = row.widget();
            if (widget == null) {
                continue;
            }
            int y = (int) Math.round(top - scrollY + row.offsetY() + 2);
            widget.setY(y);
            // Hidden widgets neither render nor take clicks, which is what keeps scrolled-past
            // controls from being clickable outside the list.
            widget.visible = y >= top - ROW_HEIGHT && y + widget.getHeight() <= bottom + ROW_HEIGHT;
            widget.active = widget.visible;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXAmount, double scrollYAmount) {
        if (maxScroll() > 0 && mouseY >= LIST_TOP && mouseY <= listBottom()) {
            targetScrollY = Math.max(0, Math.min(maxScroll(), targetScrollY - scrollYAmount * 28));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollXAmount, scrollYAmount);
    }

    // ------------------------------------------------------------------ scrollbar dragging

    private int scrollbarX() {
        return width / 2 - PANEL_WIDTH / 2 + PANEL_WIDTH - 4;
    }

    /** Height of the thumb, sized to the visible fraction of the content. */
    private int thumbHeight() {
        int viewport = listBottom() - LIST_TOP;
        return Math.max(20, (int) ((float) viewport * viewport / contentHeight));
    }

    private int thumbY() {
        int viewport = listBottom() - LIST_TOP;
        return LIST_TOP + (int) ((viewport - thumbHeight()) * (scrollY / maxScroll()));
    }

    /**
     * Maps a mouse position on the track to a scroll offset, treating {@code grab} as where within
     * the thumb the drag started — so the thumb doesn't jump under the cursor when picked up.
     */
    private void scrollToMouse(double mouseY, double grab) {
        int viewport = listBottom() - LIST_TOP;
        int travel = viewport - thumbHeight();
        if (travel <= 0) {
            return;
        }
        double top = mouseY - LIST_TOP - grab;
        double fraction = Math.max(0, Math.min(1, top / travel));
        targetScrollY = fraction * maxScroll();
        scrollY = targetScrollY; // dragging should track the cursor exactly, not ease behind it
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();

        // A few pixels wider than the bar is drawn — 3px is an unfairly small target.
        if (maxScroll() > 0
                && mouseX >= scrollbarX() - 2 && mouseX <= scrollbarX() + 5
                && mouseY >= LIST_TOP && mouseY <= listBottom()) {
            int thumbTop = thumbY();
            int thumbBottom = thumbTop + thumbHeight();
            if (mouseY >= thumbTop && mouseY <= thumbBottom) {
                // Picked the thumb up: remember where, so it moves with the cursor.
                draggingScrollbar = true;
                scrollbarGrabOffset = mouseY - thumbTop;
            } else {
                // Clicked the bare track: jump so the thumb centres on the click, then keep dragging.
                draggingScrollbar = true;
                scrollbarGrabOffset = thumbHeight() / 2.0;
                scrollToMouse(mouseY, scrollbarGrabOffset);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollToMouse(event.y(), scrollbarGrabOffset);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        updateScroll();
        layoutRows();

        int panelX = width / 2 - PANEL_WIDTH / 2;
        int top = LIST_TOP;
        int bottom = listBottom();

        // Scrim behind the list so text stays readable over the game or dirt background.
        g.fill(panelX, top, panelX + PANEL_WIDTH, bottom, 0x66000000);

        g.enableScissor(panelX, top, panelX + PANEL_WIDTH, bottom);
        for (Row row : rows) {
            if (row.label() == null) {
                continue;
            }
            int y = (int) Math.round(top - scrollY + row.offsetY());
            if (y < top - ROW_HEIGHT || y > bottom) {
                continue;
            }
            if (row.section()) {
                g.fill(panelX + 4, y + 15, panelX + PANEL_WIDTH - 4, y + 16, 0x33FFFFFF);
                g.text(font, Component.literal(row.label()), panelX + 8, y + 4, 0xFF8FE3FF, false);
            } else {
                g.text(font, Component.literal(row.label()), panelX + 12, y + 7, 0xFFDDDDDD, false);
            }
        }
        if (rows.isEmpty() && !searchTerm.isEmpty()) {
            g.centeredText(font, Component.literal("No settings match \"" + searchTerm + "\""),
                    width / 2, top + 20, 0xFFAAAAAA);
        }
        g.disableScissor();

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

        if (maxScroll() > 0) {
            // Drawn from the same helpers the click handling uses, so the bar you see and the bar
            // you can grab can't drift apart.
            int trackX = scrollbarX();
            int thumbTop = thumbY();
            boolean hovering = mouseX >= trackX - 2 && mouseX <= trackX + 5
                    && mouseY >= top && mouseY <= bottom;
            g.fill(trackX, top, trackX + 3, bottom, 0x33FFFFFF);
            g.fill(trackX, thumbTop, trackX + 3, thumbTop + thumbHeight(),
                    draggingScrollbar || hovering ? 0xFFBDEFFF : 0xAA8FE3FF);
        }
    }

    @Override
    public void onClose() {
        ShinyConfig.get().save();
        minecraft.setScreenAndShow(parent);
    }
}
