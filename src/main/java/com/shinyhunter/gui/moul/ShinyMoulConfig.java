package com.shinyhunter.gui.moul;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings, as MoulConfig sees them — the same library Firmament draws its config with.
 *
 * <p><b>Generated.</b> Every field here mirrors a field of {@link com.shinyhunter.ShinyConfig} with
 * the same name, and every option's text is the same label and tooltip the mod's own screen uses,
 * because this file is generated from that screen's row declarations
 * ({@code scratchpad/gen_moulconfig.py}). Regenerate it after adding a setting rather than editing
 * it by hand, so the two can't drift apart.
 *
 * <p>Values are copied in and out by {@link MoulConfigBridge} rather than stored here: the mod's
 * own JSON file stays the one source of truth, so nothing else in the mod has to change and an
 * existing config keeps working.
 */
public class ShinyMoulConfig extends Config {

    @Override
    public StructuredText getTitle() {
        return StructuredText.of(com.shinyhunter.Edition.NAME);
    }

    @Override
    public boolean shouldSearchCategoryNames() {
        return true;
    }

    @Expose
    @Category(name = "Detection", desc = "Detection")
    public DetectionCategory detection = new DetectionCategory();

    @Expose
    @Category(name = "World", desc = "World")
    public WorldCategory world = new WorldCategory();

    @Expose
    @Category(name = "Alerts", desc = "Alerts")
    public AlertsCategory alerts = new AlertsCategory();

    @Expose
    @Category(name = "Hotspots", desc = "Hotspots")
    public HotspotsCategory hotspots = new HotspotsCategory();

    @Expose
    @Category(name = "Bee nests", desc = "Bee nests")
    public BeeNestsCategory beeNests = new BeeNestsCategory();

    @Expose
    @Category(name = "Floor drops", desc = "Floor drops")
    public FloorDropsCategory floorDrops = new FloorDropsCategory();

    @Expose
    @Category(name = "Party coordination", desc = "Party coordination")
    public PartyCoordinationCategory partyCoordination = new PartyCoordinationCategory();

    @Expose
    @Category(name = "Quest prompts", desc = "Quest prompts")
    public QuestPromptsCategory questPrompts = new QuestPromptsCategory();

    @Expose
    @Category(name = "Critters", desc = "Critters")
    public CrittersCategory critters = new CrittersCategory();

    @Expose
    @Category(name = "Party sparklings", desc = "Party sparklings")
    public PartySparklingsCategory partySparklings = new PartySparklingsCategory();

    @Expose
    @Category(name = "Miria's Contest", desc = "Miria's Contest")
    public MiriaSContestCategory miriaSContest = new MiriaSContestCategory();

    @Expose
    @Category(name = "Snoozling walls", desc = "Snoozling walls")
    public SnoozlingWallsCategory snoozlingWalls = new SnoozlingWallsCategory();

    @Expose
    @Category(name = "Rockmites", desc = "Rockmites")
    public RockmitesCategory rockmites = new RockmitesCategory();

    @Expose
    @Category(name = "Critter list", desc = "Critter list")
    public CritterListCategory critterList = new CritterListCategory();

    @Expose
    @Category(name = "Timers and splits", desc = "Timers and splits")
    public TimersAndSplitsCategory timersAndSplits = new TimersAndSplitsCategory();

    @Expose
    @Category(name = "Safari manager", desc = "Safari manager")
    public SafariManagerCategory safariManager = new SafariManagerCategory();

    @Expose
    @Category(name = "HUD", desc = "HUD")
    public HUDCategory hUD = new HUDCategory();

    @Expose
    @Category(name = "Ground objects (rocks, cases)", desc = "Ground objects (rocks, cases)")
    public GroundObjectsRocksCasesCategory groundObjectsRocksCases = new GroundObjectsRocksCasesCategory();

    @Expose
    @Category(name = "Shard trades", desc = "Shard trades")
    public ShardTradesCategory shardTrades = new ShardTradesCategory();

    @Expose
    @Category(name = "Warden", desc = "Warden")
    public WardenCategory warden = new WardenCategory();

    @Expose
    @Category(name = "Entity highlights", desc = "Entity highlights")
    public EntityHighlightsCategory entityHighlights = new EntityHighlightsCategory();

    @Expose
    @Category(name = "Sparkling particles", desc = "Sparkling particles")
    public SparklingParticlesCategory sparklingParticles = new SparklingParticlesCategory();

    @Expose
    @Category(name = "Tracked entities", desc = "Tracked entities")
    public TrackedEntitiesCategory trackedEntities = new TrackedEntitiesCategory();

    @Expose
    @Category(name = "Tracked blocks", desc = "Tracked blocks")
    public TrackedBlocksCategory trackedBlocks = new TrackedBlocksCategory();


    /** Category identifier -> the section name the old screen filters on. */
    public static final Map<Integer, String> LIST_EDITORS = Map.of(
            0, "Tracked entities",
            1, "Tracked blocks",
            2, "Sparkling particles",
            3, "Critter list",
            4, "Snoozling walls");

    @Override
    public boolean isValidRunnable(int runnableId) {
        return LIST_EDITORS.containsKey(runnableId);
    }

    @Override
    public void executeRunnable(int runnableId) {
        String section = LIST_EDITORS.get(runnableId);
        if (section != null) {
            MoulConfigBridge.openListEditor(section);
        }
    }

    public static class DetectionCategory {

        @Expose
        @ConfigOption(name = "Enabled", desc = "Master switch for shiny hunting.")
        @ConfigEditorBoolean
        public boolean enabled;

        @Expose
        @ConfigOption(name = "Only in Skyblock", desc = "Ignore everything outside Hypixel Skyblock.")
        @ConfigEditorBoolean
        public boolean onlyInSkyblock;

        @Expose
        @ConfigOption(name = "Waypoints", desc = "Mark coordinates the mod picks out of party chat.")
        @ConfigEditorBoolean
        public boolean waypointsEnabled;

        @Expose
        @ConfigOption(name = "Waypoint clear radius", desc = "Walking this close to a waypoint removes it.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 32.0f, minStep = 0.1f)
        public double waypointClearRadius;

        @Expose
        @ConfigOption(name = "Waypoint colour", desc = "Waypoint colour as hex, e.g. FFDD55.")
        @ConfigEditorText
        public String waypointColor = "";

        @Expose
        @ConfigOption(name = "Hunt area", desc = "Area keywords are confined to unless set to hunt everywhere.")
        @ConfigEditorText
        public String huntLocation = "";

        @Expose
        @ConfigOption(name = "Re-alert after (s)", desc = "Silence before the same entity alerts again.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 600f, minStep = 1f)
        public int renotifySeconds;

    }

    public static class WorldCategory {

        @Expose
        @ConfigOption(name = "Hide paintings in Safari", desc = "Don't draw paintings anywhere in the Critter Safari.")
        @ConfigEditorBoolean
        public boolean hidePaintingsInSafari;

        @Expose
        @ConfigOption(name = "Reload chunks repeat (ticks)", desc = "Held Reload chunks key fires every this many ticks. Key: Controls or /shiny reloadkey.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
        public int chunkReloadRepeatTicks;

    }

    public static class AlertsCategory {

        @Expose
        @ConfigOption(name = "Update notices", desc = "Say in chat when a newer Shiny Hunter is out, with a link to it.")
        @ConfigEditorBoolean
        public boolean checkForUpdates;

        @Expose
        @ConfigOption(name = "Sound", desc = "Play a sound on a find.")
        @ConfigEditorBoolean
        public boolean playSound;

        @Expose
        @ConfigOption(name = "Action bar", desc = "Show finds on the action bar too.")
        @ConfigEditorBoolean
        public boolean actionBar;

    }

    public static class HotspotsCategory {

        @Expose
        @ConfigOption(name = "Relay hotspot", desc = "Announce your hunting hotspot to chat.")
        @ConfigEditorBoolean
        public boolean announceHotspot;

        @Expose
        @ConfigOption(name = "Relay channel", desc = "Chat command used to relay, e.g. pc / gc / ac.")
        @ConfigEditorText
        public String hotspotCommand = "";

        @Expose
        @ConfigOption(name = "Split across party", desc = "Give everyone a different hotspot automatically.")
        @ConfigEditorBoolean
        public boolean coordinateHotspots;

        @Expose
        @ConfigOption(name = "Round resets (min)", desc = "Forget claims this long after the last one.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 120f, minStep = 1f)
        public int hotspotRoundMinutes;

    }

    public static class BeeNestsCategory {

        @Expose
        @ConfigOption(name = "Highlight nests", desc = "Outline bee nests in the location below.")
        @ConfigEditorBoolean
        public boolean highlightBeeNests;

        @Expose
        @ConfigOption(name = "Location", desc = "Sidebar zone the highlight is limited to.")
        @ConfigEditorText
        public String beeNestLocation = "";

        @Expose
        @ConfigOption(name = "Colour (hex)", desc = "Outline colour, e.g. 66D9FF.")
        @ConfigEditorText
        public String beeNestColor = "";

        @Expose
        @ConfigOption(name = "Search radius", desc = "How far to look for nests, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 256f, minStep = 1f)
        public int beeNestScanRadius;

        @Expose
        @ConfigOption(name = "Through walls", desc = "Draw nest outlines through terrain (nests never move, so this is allowed).")
        @ConfigEditorBoolean
        public boolean beeNestThroughWalls;

        @Expose
        @ConfigOption(name = "Hive beacons", desc = "Beacon the honeyhives that still have honey, while the contest is unfinished.")
        @ConfigEditorBoolean
        public boolean honeyHiveWaypoints;

        @Expose
        @ConfigOption(name = "Only when incomplete", desc = "Hide the hive beacons once the contest is complete.")
        @ConfigEditorBoolean
        public boolean honeyHiveOnlyWhenIncomplete;

        @Expose
        @ConfigOption(name = "Hive colour", desc = "Beacon colour as hex, e.g. 55FF55.")
        @ConfigEditorText
        public String honeyHiveColor = "";

        @Expose
        @ConfigOption(name = "Hive beam height", desc = "How tall the beacon beam is, in blocks.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 2048f, minStep = 1f)
        public int honeyHiveBeamHeight;

        @Expose
        @ConfigOption(name = "Hive outline", desc = "Also outline the hive block itself.")
        @ConfigEditorBoolean
        public boolean honeyHiveOutline;

        @Expose
        @ConfigOption(name = "Hive outline through walls", desc = "Show the box on the hive block through terrain.")
        @ConfigEditorBoolean
        public boolean honeyHiveThroughWalls;

        @Expose
        @ConfigOption(name = "Hive range", desc = "How far away a hive can be and still get a beacon.")
        @ConfigEditorSlider(minValue = 16f, maxValue = 512f, minStep = 1f)
        public int honeyHiveRange;

        @Expose
        @ConfigOption(name = "Beacon unknown hives", desc = "Also beacon hives whose state isn't known yet, dimmer.")
        @ConfigEditorBoolean
        public boolean honeyHiveShowUnknown;

        @Expose
        @ConfigOption(name = "Nest survey", desc = "Record fence+trapdoor bee nests to config/shinyhunter-nests.json.")
        @ConfigEditorBoolean
        public boolean surveyNests;

        @Expose
        @ConfigOption(name = "Survey area", desc = "Where the nest survey runs. Blank means anywhere in Skyblock.")
        @ConfigEditorText
        public String surveyLocation = "";

        @Expose
        @ConfigOption(name = "Share emptied", desc = "Announce emptied nests and hide ones the party emptied.")
        @ConfigEditorBoolean
        public boolean announceBeeEmptied;

        @Expose
        @ConfigOption(name = "Emptied channel", desc = "Chat command for emptied call-outs.")
        @ConfigEditorText
        public String beeEmptiedChannel = "";

    }

    public static class FloorDropsCategory {

        @Expose
        @ConfigOption(name = "Highlight drops", desc = "Outline clustered marker displays.")
        @ConfigEditorBoolean
        public boolean highlightFloorDrops;

        @Expose
        @ConfigOption(name = "Item token", desc = "Item id a drop's displays carry.")
        @ConfigEditorText
        public String floorDropItemToken = "";

        @Expose
        @ConfigOption(name = "Cluster size", desc = "Displays needed together to count as a drop.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 16f, minStep = 1f)
        public int floorDropClusterSize;

        @Expose
        @ConfigOption(name = "Colour (hex)", desc = "Drop outline colour.")
        @ConfigEditorText
        public String floorDropColor = "";

    }

    public static class PartyCoordinationCategory {

        @Expose
        @ConfigOption(name = "!dt command", desc = "Handle !dt [reason] in party chat: hold the Safari Manager after the run.")
        @ConfigEditorBoolean
        public boolean downtimeCommand;

        @Expose
        @ConfigOption(name = "Downtime channel", desc = "Chat command for the downtime announcement.")
        @ConfigEditorText
        public String downtimeChannel = "";

        @Expose
        @ConfigOption(name = "!timer command", desc = "Handle !timer <length> in party chat and call out expiry.")
        @ConfigEditorBoolean
        public boolean timerCommand;

        @Expose
        @ConfigOption(name = "Timer channel", desc = "Chat command for timer expiry call-outs.")
        @ConfigEditorText
        public String timerChannel = "";

        @Expose
        @ConfigOption(name = "Bird food call-outs", desc = "Announce picking up bird food, and running out.")
        @ConfigEditorBoolean
        public boolean announceBirdFood;

        @Expose
        @ConfigOption(name = "Bird food channel", desc = "Chat command for bird food call-outs.")
        @ConfigEditorText
        public String birdFoodChannel = "";

    }

    public static class QuestPromptsCategory {

        @Expose
        @ConfigOption(name = "Click to accept", desc = "Click anywhere with chat open to press the accept button. Use at your own risk.")
        @ConfigEditorBoolean
        public boolean acceptQuestClicks;

        @Expose
        @ConfigOption(name = "Mob", desc = "Whose dialogue prompt this applies to.")
        @ConfigEditorText
        public String questMobName = "";

        @Expose
        @ConfigOption(name = "Accept button", desc = "The option button to press.")
        @ConfigEditorText
        public String questAcceptLabel = "";

    }

    public static class CrittersCategory {

        @Expose
        @ConfigOption(name = "Track critters", desc = "Record which critters turn up on this Safari instance.")
        @ConfigEditorBoolean
        public boolean trackCritters;

        @Expose
        @ConfigOption(name = "Missing HUD panels", desc = "One panel per biome listing what's still missing.")
        @ConfigEditorBoolean
        public boolean showCritterHud;

        @Expose
        @ConfigOption(name = "Highlight missing only", desc = "Outline critters still outstanding; drop each when caught.")
        @ConfigEditorBoolean
        public boolean highlightMissingCritters;

        @Expose
        @ConfigOption(name = "Missing colour", desc = "Outline colour for outstanding critters, e.g. FFC94A.")
        @ConfigEditorText
        public String missingCritterColor = "";

        @Expose
        @ConfigOption(name = "Nest done critter", desc = "Once caught, bee nest outlines change colour for the rest of the run.")
        @ConfigEditorText
        public String beeNestStopCritter = "";

        @Expose
        @ConfigOption(name = "Hide nests when done", desc = "Stop outlining nests once that critter is caught, rather than recolouring them.")
        @ConfigEditorBoolean
        public boolean hideNestsWhenDone;

        @Expose
        @ConfigOption(name = "Nest done colour", desc = "Outline colour for nests after that critter is caught, e.g. FF4040.")
        @ConfigEditorText
        public String beeNestDoneColor = "";

    }

    public static class PartySparklingsCategory {

        @Expose
        @ConfigOption(name = "Party dex", desc = "Look up each member's Sparkling Critterdex as they join.")
        @ConfigEditorBoolean
        public boolean partyDexEnabled;

        @Expose
        @ConfigOption(name = "Post shared to party", desc = "Announce the shared sparklings in party chat when the party fills.")
        @ConfigEditorBoolean
        public boolean partyDexAnnounce;

        @Expose
        @ConfigOption(name = "!shared command", desc = "Answer !shared in party chat, even with the party dex off.")
        @ConfigEditorBoolean
        public boolean sharedCommand;

        @Expose
        @ConfigOption(name = "Skip shared", desc = "Leave sparklings the whole party has off the missing list and HUD.")
        @ConfigEditorBoolean
        public boolean partyDexSkipShared;

        @Expose
        @ConfigOption(name = "Dex path", desc = "Profile path to the Sparkling Critterdex, once known. Blank = discover.")
        @ConfigEditorText
        public String sparklingDexPath = "";

        @Expose
        @ConfigOption(name = "!shared max", desc = "Most sparklings !shared lists before \"+N more\".")
        @ConfigEditorSlider(minValue = 1f, maxValue = 37f, minStep = 1f)
        public int sharedListMax;

    }

    public static class MiriaSContestCategory {

        @Expose
        @ConfigOption(name = "Track contest", desc = "Show whether this window's contest is complete.")
        @ConfigEditorBoolean
        public boolean trackContest;

        @Expose
        @ConfigOption(name = "Hide when complete", desc = "Hide the panel once the contest is complete.")
        @ConfigEditorBoolean
        public boolean hideContestWhenComplete;

        @Expose
        @ConfigOption(name = "Contest scope", desc = "Where the contest panel shows: skyblock / hypixel / anywhere.")
        @ConfigEditorText
        public String contestScope = "";

        @Expose
        @ConfigOption(name = "Show timer", desc = "Show the time left until the contest ends.")
        @ConfigEditorBoolean
        public boolean showContestTimer;

        @Expose
        @ConfigOption(name = "Warn if incomplete", desc = "Ring at each mark below while the contest is not complete.")
        @ConfigEditorBoolean
        public boolean contestWarnEnabled;

        @Expose
        @ConfigOption(name = "Warn at (minutes)", desc = "Minutes-left marks to ring at, e.g. 5, 3, 1.")
        @ConfigEditorText
        public String contestWarnMinutes = "";

        @Expose
        @ConfigOption(name = "Warning popup", desc = "Flash CONTEST INCOMPLETE! on screen at each warning.")
        @ConfigEditorBoolean
        public boolean contestWarnTitle;

        @Expose
        @ConfigOption(name = "Sound id", desc = "Any sound id, e.g. minecraft:block.bell.use or minecraft:entity.player.levelup.")
        @ConfigEditorText
        public String contestSound = "";

        @Expose
        @ConfigOption(name = "Sound volume %", desc = "100 = one bell. 300 = three at once. Louder needs more copies, not a bigger number.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 999999f, minStep = 1f)
        public int contestSoundVolume;

    }

    public static class SnoozlingWallsCategory {

        @Expose
        @ConfigOption(name = "Wall waypoints", desc = "Mark the Snoozling walls still standing, through terrain, while in the Safari.")
        @ConfigEditorBoolean
        public boolean snoozlingWallWaypoints;

        @Expose
        @ConfigOption(name = "Wall waypoint colour", desc = "Hex colour, e.g. FF55FF.")
        @ConfigEditorText
        public String snoozlingWallColor = "";

        @Expose
        @ConfigOption(name = "Snoozling walls list", desc = "Open the list editor for snoozling walls.")
        @ConfigEditorButton(runnableId = 4, buttonText = "Edit")
        public boolean openSnoozlingWalls = false;

    }

    public static class RockmitesCategory {

        @Expose
        @ConfigOption(name = "Track rockmites", desc = "Count mounds opened and Rockmites found.")
        @ConfigEditorBoolean
        public boolean trackRockmites;

        @Expose
        @ConfigOption(name = "Mounds per run", desc = "How many mounds an instance has.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 200f, minStep = 1f)
        public int rockmiteMoundTotal;

    }

    public static class CritterListCategory {

        @Expose
        @ConfigOption(name = "Critter list list", desc = "Open the list editor for critter list.")
        @ConfigEditorButton(runnableId = 3, buttonText = "Edit")
        public boolean openCritterList = false;

    }

    public static class TimersAndSplitsCategory {

        @Expose
        @ConfigOption(name = "Run timer", desc = "Show the run clock.")
        @ConfigEditorBoolean
        public boolean showTimer;

        @Expose
        @ConfigOption(name = "Track splits", desc = "Record per-biome split times and compare against bests.")
        @ConfigEditorBoolean
        public boolean trackSplits;

        @Expose
        @ConfigOption(name = "Splits on HUD", desc = "Show the splits panel.")
        @ConfigEditorBoolean
        public boolean showSplitHud;

        @Expose
        @ConfigOption(name = "Announce splits", desc = "Say each split in chat as it is taken.")
        @ConfigEditorBoolean
        public boolean announceSplits;

        @Expose
        @ConfigOption(name = "Own-biome catches", desc = "Personal catches in a biome before its split counts as your PB.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
        public int ownBiomeMinCatches;

    }

    public static class SafariManagerCategory {

        @Expose
        @ConfigOption(name = "Guard Safari Manager", desc = "Refuse the click until the whole party is here; sneak to bypass.")
        @ConfigEditorBoolean
        public boolean guardSafariManager;

        @Expose
        @ConfigOption(name = "Manager name", desc = "Nameplate text of the NPC to guard.")
        @ConfigEditorText
        public String safariManagerName = "";

        @Expose
        @ConfigOption(name = "Block on \"kick\"", desc = "Also hold the manager shut when someone says kick in party chat.")
        @ConfigEditorBoolean
        public boolean blockOnKickCall;

        @Expose
        @ConfigOption(name = "Kick hold (s)", desc = "How long a called kick keeps the manager blocked.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 600f, minStep = 1f)
        public int kickBlockSeconds;

        @Expose
        @ConfigOption(name = "Guard ticket item", desc = "Also refuse the ticket itself, which is how a non-leader starts.")
        @ConfigEditorBoolean
        public boolean guardTicketItem;

        @Expose
        @ConfigOption(name = "Ticket name", desc = "Item-name text identifying a Safari ticket.")
        @ConfigEditorText
        public String ticketItemName = "";

        @Expose
        @ConfigOption(name = "Presence radius", desc = "How close a player must be to count as here.")
        @ConfigEditorSlider(minValue = 2f, maxValue = 64f, minStep = 1f)
        public int presenceRadius;

        @Expose
        @ConfigOption(name = "Instant party chat", desc = "Send right away when nothing has been sent recently.")
        @ConfigEditorBoolean
        public boolean instantPartyChat;

        @Expose
        @ConfigOption(name = "Announce gems", desc = "Say \"X Gem placed!\" when you place one.")
        @ConfigEditorBoolean
        public boolean announceGems;

        @Expose
        @ConfigOption(name = "Gem channel", desc = "Chat command for gem call-outs.")
        @ConfigEditorText
        public String gemChannel = "";

        @Expose
        @ConfigOption(name = "Announce all uniques", desc = "Say \"All uniques caught!\" once every biome is complete.")
        @ConfigEditorBoolean
        public boolean announceAllUniques;

        @Expose
        @ConfigOption(name = "Highlight shulkers", desc = "Outline shulkers as \"Shulker\" until their name loads, then by name.")
        @ConfigEditorBoolean
        public boolean shulkerIsHideonfloor;

        @Expose
        @ConfigOption(name = "Always highlight shulkers", desc = "Keep shulkers outlined even once that critter is caught.")
        @ConfigEditorBoolean
        public boolean alwaysHighlightShulkers;

        @Expose
        @ConfigOption(name = "Announce biome clear", desc = "Say \"<biome> clear!\" once a biome is complete.")
        @ConfigEditorBoolean
        public boolean announceBiomeClear;

        @Expose
        @ConfigOption(name = "Announce missing Macaw", desc = "Say \"Forest 8/9, missing Macaw\" once only the Macaw is left.")
        @ConfigEditorBoolean
        public boolean announceMacawOnly;

        @Expose
        @ConfigOption(name = "Answer !missing", desc = "Reply with your missing critters when the party asks.")
        @ConfigEditorBoolean
        public boolean respondToMissing;

        @Expose
        @ConfigOption(name = "Critter channel", desc = "Chat command for critter call-outs.")
        @ConfigEditorText
        public String critterChannel = "";

    }

    public static class HUDCategory {

        @Expose
        @ConfigOption(name = "Show HUD", desc = "Draw the HUD panels on screen.")
        @ConfigEditorBoolean
        public boolean showHud;

        @Expose
        @ConfigOption(name = "HUD x", desc = "Distance from the left edge, in pixels.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2000f, minStep = 1f)
        public int hudX;

        @Expose
        @ConfigOption(name = "HUD y", desc = "Distance from the top edge, in pixels.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2000f, minStep = 1f)
        public int hudY;

    }

    public static class GroundObjectsRocksCasesCategory {

        @Expose
        @ConfigOption(name = "Highlight objects", desc = "Outline item displays paired with an interaction hitbox.")
        @ConfigEditorBoolean
        public boolean highlightGroundObjects;

        @Expose
        @ConfigOption(name = "Colour (hex)", desc = "Ground object outline colour.")
        @ConfigEditorText
        public String groundObjectColor = "";

        @Expose
        @ConfigOption(name = "Search radius", desc = "How far to look for ground objects, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 128f, minStep = 1f)
        public int groundObjectScanRadius;

        @Expose
        @ConfigOption(name = "Announce coords", desc = "Print coordinates in chat when one is spotted.")
        @ConfigEditorBoolean
        public boolean groundObjectAnnounce;

    }

    public static class ShardTradesCategory {

        @Expose
        @ConfigOption(name = "Watch NPC trades", desc = "Detect hunter shard offers and relay them.")
        @ConfigEditorBoolean
        public boolean announceShardTrades;

        @Expose
        @ConfigOption(name = "Relay channel", desc = "Chat command for trade call-outs, e.g. pc.")
        @ConfigEditorText
        public String shardTradeChannel = "";

        @Expose
        @ConfigOption(name = "Title if affordable", desc = "Title you when a called-out trade's cost is in your inventory.")
        @ConfigEditorBoolean
        public boolean titleWhenTradeAffordable;

        @Expose
        @ConfigOption(name = "Out of capsules", desc = "Tell the party when you use your last Critter Capsule.")
        @ConfigEditorBoolean
        public boolean announceOutOfCapsules;

        @Expose
        @ConfigOption(name = "Capsule channel", desc = "Chat command for the out-of-capsules call-out.")
        @ConfigEditorText
        public String capsuleChannel = "";

    }

    public static class WardenCategory {

        @Expose
        @ConfigOption(name = "Warden ready title", desc = "Title when the warden's hitbox reaches full height.")
        @ConfigEditorBoolean
        public boolean announceWardenReady;

        @Expose
        @ConfigOption(name = "Watch radius", desc = "How far to watch for wardens, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 128f, minStep = 1f)
        public int wardenScanRadius;

    }

    public static class EntityHighlightsCategory {

        @Expose
        @ConfigOption(name = "Highlight entities", desc = "Outline the tracked entities below.")
        @ConfigEditorBoolean
        public boolean highlightEntities;

        @Expose
        @ConfigOption(name = "Name labels", desc = "Float a name above matches that have no nametag of their own.")
        @ConfigEditorBoolean
        public boolean highlightLabels;

        @Expose
        @ConfigOption(name = "Name label mode", desc = "When to draw it: never / default (only if the game shows none) / always.")
        @ConfigEditorText
        public String highlightLabelMode = "";

        @Expose
        @ConfigOption(name = "Outline padding", desc = "Blocks added on every side of an outline so it stands clear of the mob.")
        @ConfigEditorSlider(minValue = 0.05f, maxValue = 1.0f, minStep = 0.1f)
        public double highlightBoxGrow;

        @Expose
        @ConfigOption(name = "Minimum outline size", desc = "Small mobs get an outline at least this big, in blocks.")
        @ConfigEditorSlider(minValue = 0.0f, maxValue = 3.0f, minStep = 0.1f)
        public double highlightBoxMinSize;

        @Expose
        @ConfigOption(name = "Tint mobs", desc = "Colour outlined mobs toward their outline colour.")
        @ConfigEditorBoolean
        public boolean highlightTintMobs;

        @Expose
        @ConfigOption(name = "Ignore capture UI", desc = "Don't treat the capsule's CAPTURING display as a mob.")
        @ConfigEditorBoolean
        public boolean highlightIgnoreCapturing;

        @Expose
        @ConfigOption(name = "Match NBT too", desc = "Also match item/NBT data for unnamed things like rocks.")
        @ConfigEditorBoolean
        public boolean highlightDeepScan;

    }

    public static class SparklingParticlesCategory {

        @Expose
        @ConfigOption(name = "Particle detection", desc = "Treat a trail of these particles around a mob as a sparkling.")
        @ConfigEditorBoolean
        public boolean particleDetection;

        @Expose
        @ConfigOption(name = "Trail radius", desc = "How close to the mob a particle must be to count.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 16.0f, minStep = 0.1f)
        public double particleRadius;

        @Expose
        @ConfigOption(name = "Trail threshold", desc = "Particle packets in the window before it counts.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 500f, minStep = 1f)
        public int particleThreshold;

        @Expose
        @ConfigOption(name = "Trail window (s)", desc = "How far back the trail is counted.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 5f, minStep = 1f)
        public int particleWindowSeconds;

        @Expose
        @ConfigOption(name = "Sparkling particles list", desc = "Open the list editor for sparkling particles.")
        @ConfigEditorButton(runnableId = 2, buttonText = "Edit")
        public boolean openSparklingParticles = false;

    }

    public static class TrackedEntitiesCategory {

        @Expose
        @ConfigOption(name = "Tracked entities list", desc = "Open the list editor for tracked entities.")
        @ConfigEditorButton(runnableId = 0, buttonText = "Edit")
        public boolean openTrackedEntities = false;

    }

    public static class TrackedBlocksCategory {

        @Expose
        @ConfigOption(name = "Highlight blocks", desc = "Outline the block types listed below.")
        @ConfigEditorBoolean
        public boolean highlightBlocks;

        @Expose
        @ConfigOption(name = "Search radius", desc = "How far to look for tracked blocks, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 256f, minStep = 1f)
        public int blockScanRadius;

        @Expose
        @ConfigOption(name = "Tracked blocks list", desc = "Open the list editor for tracked blocks.")
        @ConfigEditorButton(runnableId = 1, buttonText = "Edit")
        public boolean openTrackedBlocks = false;

    }

}