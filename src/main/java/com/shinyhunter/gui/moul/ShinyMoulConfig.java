package com.shinyhunter.gui.moul;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
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
    @Category(name = "General", desc = "General")
    public GeneralCategory general = new GeneralCategory();

    @Expose
    @Category(name = "Sparkling detection", desc = "Sparkling detection")
    public SparklingDetectionCategory sparklingDetection = new SparklingDetectionCategory();

    @Expose
    @Category(name = "Runs & critters", desc = "Runs & critters")
    public RunsCrittersCategory runsCritters = new RunsCrittersCategory();

    @Expose
    @Category(name = "Party", desc = "Party")
    public PartyCategory party = new PartyCategory();

    @Expose
    @Category(name = "Safari helpers", desc = "Safari helpers")
    public SafariHelpersCategory safariHelpers = new SafariHelpersCategory();

    @Expose
    @Category(name = "Floor drops", desc = "Floor drops")
    public FloorDropsCategory floorDrops = new FloorDropsCategory();

    @Expose
    @Category(name = "Honeyhives & contest", desc = "Honeyhives & contest")
    public HoneyhivesContestCategory honeyhivesContest = new HoneyhivesContestCategory();

    @Expose
    @Category(name = "Highlights", desc = "Highlights")
    public HighlightsCategory highlights = new HighlightsCategory();

    /** Category identifier -> the section name the old screen filters on. */
    public static final Map<Integer, String> LIST_EDITORS = Map.of(
            0, "Sparkling particles",
            1, "Critter list",
            2, "Snoozling walls",
            3, "Tracked entities",
            4, "Tracked blocks");

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

    public static class GeneralCategory {

        @Expose
        @ConfigOption(name = "Basics", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionGeneralBasics = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Enabled", desc = "Master switch for shiny hunting.")
        @ConfigEditorBoolean
        public boolean enabled;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Only in Skyblock", desc = "Ignore everything outside Hypixel Skyblock.")
        @ConfigEditorBoolean
        public boolean onlyInSkyblock;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Update notices", desc = "Say in chat when a newer Shiny Hunter is out, with a link to it.")
        @ConfigEditorBoolean
        public boolean checkForUpdates;

        @Expose
        @ConfigOption(name = "Alerts", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionGeneralAlerts = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Sound", desc = "Play a sound on a find.")
        @ConfigEditorBoolean
        public boolean playSound;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Action bar", desc = "Show finds on the action bar too.")
        @ConfigEditorBoolean
        public boolean actionBar;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Re-alert after (s)", desc = "Silence before the same entity alerts again.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 600f, minStep = 1f)
        public int renotifySeconds;

        @Expose
        @ConfigOption(name = "Waypoints", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionGeneralWaypoints = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Waypoints", desc = "Mark coordinates the mod picks out of party chat.")
        @ConfigEditorBoolean
        public boolean waypointsEnabled;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Waypoint clear radius", desc = "Walking this close to a waypoint removes it.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 32.0f, minStep = 0.1f)
        public double waypointClearRadius;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Waypoint colour", desc = "Waypoint colour as hex, e.g. FFDD55.")
        @ConfigEditorText
        public String waypointColor = "";

        @Expose
        @ConfigOption(name = "HUD", desc = "")
        @ConfigEditorAccordion(id = 3)
        public boolean accordionGeneralHUD = false;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Show HUD", desc = "Draw the HUD panels on screen.")
        @ConfigEditorBoolean
        public boolean showHud;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "HUD x", desc = "Distance from the left edge, in pixels.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2000f, minStep = 1f)
        public int hudX;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "HUD y", desc = "Distance from the top edge, in pixels.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2000f, minStep = 1f)
        public int hudY;

        @Expose
        @ConfigOption(name = "Hypixel API", desc = "")
        @ConfigEditorAccordion(id = 4)
        public boolean accordionGeneralHypixelAPI = false;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Dex path", desc = "Profile path to the Sparkling Critterdex, once known. Blank = discover.")
        @ConfigEditorText
        public String sparklingDexPath = "";

        @Expose
        @ConfigOption(name = "World", desc = "")
        @ConfigEditorAccordion(id = 5)
        public boolean accordionGeneralWorld = false;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Hide paintings in Safari", desc = "Don't draw paintings anywhere in the Critter Safari.")
        @ConfigEditorBoolean
        public boolean hidePaintingsInSafari;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Reload chunks repeat (ticks)", desc = "Held Reload chunks key fires every this many ticks. Key: Controls or /shiny reloadkey.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
        public int chunkReloadRepeatTicks;

    }

    public static class SparklingDetectionCategory {

        @Expose
        @ConfigOption(name = "Scanning", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionSparklingDetectionScanning = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hunt area", desc = "Area keywords are confined to unless set to hunt everywhere.")
        @ConfigEditorText
        public String huntLocation = "";

        @Expose
        @ConfigOption(name = "Sparkling particles", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionSparklingDetectionSparklingParticles = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Sparkling particles list", desc = "Open the list editor for sparkling particles.")
        @ConfigEditorButton(runnableId = 0, buttonText = "Edit")
        public boolean openSparklingParticles = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Particle detection", desc = "Treat a trail of these particles around a mob as a sparkling.")
        @ConfigEditorBoolean
        public boolean particleDetection;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Trail radius", desc = "How close to the mob a particle must be to count.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 16.0f, minStep = 0.1f)
        public double particleRadius;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Trail threshold", desc = "Particle packets in the window before it counts.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 500f, minStep = 1f)
        public int particleThreshold;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Trail window (s)", desc = "How far back the trail is counted.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 5f, minStep = 1f)
        public int particleWindowSeconds;

        @Expose
        @ConfigOption(name = "Sparkling luck", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionSparklingDetectionSparklingLuck = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Tickets since sparkling", desc = "Count Safari tickets used since anyone in the party last caught a sparkling (HUD, chat on a catch, history).")
        @ConfigEditorBoolean
        public boolean trackTicketsSinceSparkling;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Tickets so far", desc = "Tickets used since the party's last sparkling. Set it by hand if you know it.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 100000f, minStep = 1f)
        public int ticketsSinceSparkling;

    }

    public static class RunsCrittersCategory {

        @Expose
        @ConfigOption(name = "Run", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionRunsCrittersRun = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Run mode", desc = "unique, fullclear or sparkling: what the missing panels, !m, splits and outlines go for. /shiny mode switches.")
        @ConfigEditorText
        public String safariMode = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Track critters", desc = "Record which critters turn up on this Safari instance.")
        @ConfigEditorBoolean
        public boolean trackCritters;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Save runs", desc = "Record every Safari run for /shiny history: length, catches, shards, sparklings, splits.")
        @ConfigEditorBoolean
        public boolean runHistoryEnabled;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Import logs on launch", desc = "Save runs from your last session's log when the game starts. Minecraft only keeps 7 logs a day.")
        @ConfigEditorBoolean
        public boolean autoImportLogs;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Coins at insta-sell", desc = "Value shards at the bazaar's instant-sell price instead of the sell-offer price.")
        @ConfigEditorBoolean
        public boolean runCoinsInstaSell;

        @Expose
        @ConfigOption(name = "Missing critters", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionRunsCrittersMissingCritters = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Missing HUD panels", desc = "One panel per biome listing what's still missing.")
        @ConfigEditorBoolean
        public boolean showCritterHud;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Highlight missing only", desc = "Outline critters still outstanding; drop each when caught.")
        @ConfigEditorBoolean
        public boolean highlightMissingCritters;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Missing colour", desc = "Outline colour for outstanding critters, e.g. FFC94A.")
        @ConfigEditorText
        public String missingCritterColor = "";

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Highlight shulkers", desc = "Outline shulkers as \"Shulker\" until their name loads, then by name.")
        @ConfigEditorBoolean
        public boolean shulkerIsHideonfloor;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Always highlight shulkers", desc = "Keep shulkers outlined even once that critter is caught.")
        @ConfigEditorBoolean
        public boolean alwaysHighlightShulkers;

        @Expose
        @ConfigOption(name = "Critter list", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionRunsCrittersCritterList = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Critter list", desc = "Open the list editor for critter list.")
        @ConfigEditorButton(runnableId = 1, buttonText = "Edit")
        public boolean openCritterList = false;

        @Expose
        @ConfigOption(name = "Timers and splits", desc = "")
        @ConfigEditorAccordion(id = 3)
        public boolean accordionRunsCrittersTimersAndSplits = false;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Run timer", desc = "Show the run clock.")
        @ConfigEditorBoolean
        public boolean showTimer;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Track splits", desc = "Record per-biome split times and compare against bests.")
        @ConfigEditorBoolean
        public boolean trackSplits;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Splits on HUD", desc = "Show the splits panel.")
        @ConfigEditorBoolean
        public boolean showSplitHud;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Announce splits", desc = "Say each split in chat as it is taken.")
        @ConfigEditorBoolean
        public boolean announceSplits;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Own-biome catches", desc = "Personal catches in a biome before its split counts as your PB.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
        public int ownBiomeMinCatches;

        @Expose
        @ConfigOption(name = "Achievements", desc = "")
        @ConfigEditorAccordion(id = 4)
        public boolean accordionRunsCrittersAchievements = false;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Achievements", desc = "Unlock achievements as you play (/shiny achievements).")
        @ConfigEditorBoolean
        public boolean achievementsEnabled;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Achievement sound", desc = "Play a sound when an achievement unlocks.")
        @ConfigEditorBoolean
        public boolean achievementSound;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Achievements to party", desc = "Also say \"Achievement unlocked: X!\" in party chat.")
        @ConfigEditorBoolean
        public boolean achievementsToParty;

    }

    public static class PartyCategory {

        @Expose
        @ConfigOption(name = "Party chat", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionPartyPartyChat = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Instant party chat", desc = "Send right away when nothing has been sent recently.")
        @ConfigEditorBoolean
        public boolean instantPartyChat;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Critter channel", desc = "Chat command for critter call-outs.")
        @ConfigEditorText
        public String critterChannel = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Answer !missing", desc = "Reply with your missing critters when the party asks.")
        @ConfigEditorBoolean
        public boolean respondToMissing;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Announce biome clear", desc = "Say \"<biome> clear!\" once a biome is complete.")
        @ConfigEditorBoolean
        public boolean announceBiomeClear;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Announce missing Macaw", desc = "Say \"Forest 8/9, missing Macaw\" once only the Macaw is left.")
        @ConfigEditorBoolean
        public boolean announceMacawOnly;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Announce all uniques", desc = "Say \"All uniques caught!\" once every biome is complete.")
        @ConfigEditorBoolean
        public boolean announceAllUniques;

        @Expose
        @ConfigOption(name = "Commands", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionPartyCommands = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "!dt command", desc = "Handle !dt [reason] in party chat: hold the Safari Manager after the run.")
        @ConfigEditorBoolean
        public boolean downtimeCommand;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Downtime channel", desc = "Chat command for the downtime announcement.")
        @ConfigEditorText
        public String downtimeChannel = "";

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "!timer command", desc = "Handle !timer <length> in party chat and call out expiry.")
        @ConfigEditorBoolean
        public boolean timerCommand;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Timer channel", desc = "Chat command for timer expiry call-outs.")
        @ConfigEditorText
        public String timerChannel = "";

        @Expose
        @ConfigOption(name = "Party sparklings", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionPartyPartySparklings = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Party dex", desc = "Look up each member's Sparkling Critterdex as they join.")
        @ConfigEditorBoolean
        public boolean partyDexEnabled;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Post shared to party", desc = "Announce the shared sparklings in party chat when the party fills.")
        @ConfigEditorBoolean
        public boolean partyDexAnnounce;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "!shared command", desc = "Answer !shared in party chat, even with the party dex off.")
        @ConfigEditorBoolean
        public boolean sharedCommand;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Skip shared", desc = "Leave sparklings the whole party has off the missing list and HUD.")
        @ConfigEditorBoolean
        public boolean partyDexSkipShared;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "!shared max", desc = "Most sparklings !shared lists before \"+N more\".")
        @ConfigEditorSlider(minValue = 1f, maxValue = 37f, minStep = 1f)
        public int sharedListMax;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Useful sparklings", desc = "Comma-separated critters !shared reports (e.g. Macaw, Gemzie); !shared all ignores it.")
        @ConfigEditorText
        public String usefulSparklingCritters = "";

        @Expose
        @ConfigOption(name = "Safari Manager", desc = "")
        @ConfigEditorAccordion(id = 3)
        public boolean accordionPartySafariManager = false;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Guard Safari Manager", desc = "Refuse the click until the whole party is here; sneak to bypass.")
        @ConfigEditorBoolean
        public boolean guardSafariManager;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Manager name", desc = "Nameplate text of the NPC to guard.")
        @ConfigEditorText
        public String safariManagerName = "";

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Block on \"kick\"", desc = "Also hold the manager shut when someone says kick in party chat.")
        @ConfigEditorBoolean
        public boolean blockOnKickCall;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Kick hold (s)", desc = "How long a called kick keeps the manager blocked.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 600f, minStep = 1f)
        public int kickBlockSeconds;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Guard ticket item", desc = "Also refuse the ticket itself, which is how a non-leader starts.")
        @ConfigEditorBoolean
        public boolean guardTicketItem;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Ticket name", desc = "Item-name text identifying a Safari ticket.")
        @ConfigEditorText
        public String ticketItemName = "";

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Presence radius", desc = "How close a player must be to count as here.")
        @ConfigEditorSlider(minValue = 2f, maxValue = 64f, minStep = 1f)
        public int presenceRadius;

        @Expose
        @ConfigOption(name = "Call-outs", desc = "")
        @ConfigEditorAccordion(id = 4)
        public boolean accordionPartyCallOuts = false;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Bird food call-outs", desc = "Announce picking up bird food, and running out.")
        @ConfigEditorBoolean
        public boolean announceBirdFood;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Bird food channel", desc = "Chat command for bird food call-outs.")
        @ConfigEditorText
        public String birdFoodChannel = "";

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Announce gems", desc = "Say \"X Gem placed!\" when you place one.")
        @ConfigEditorBoolean
        public boolean announceGems;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Gem channel", desc = "Chat command for gem call-outs.")
        @ConfigEditorText
        public String gemChannel = "";

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Watch NPC trades", desc = "Detect hunter shard offers and relay them.")
        @ConfigEditorBoolean
        public boolean announceShardTrades;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Trade channel", desc = "Chat command for trade call-outs, e.g. pc.")
        @ConfigEditorText
        public String shardTradeChannel = "";

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Title if affordable", desc = "Title you when a called-out trade's cost is in your inventory.")
        @ConfigEditorBoolean
        public boolean titleWhenTradeAffordable;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Out of capsules", desc = "Tell the party when you use your last Critter Capsule.")
        @ConfigEditorBoolean
        public boolean announceOutOfCapsules;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Capsule channel", desc = "Chat command for the out-of-capsules call-out.")
        @ConfigEditorText
        public String capsuleChannel = "";

        @Expose
        @ConfigOption(name = "Hotspots", desc = "")
        @ConfigEditorAccordion(id = 5)
        public boolean accordionPartyHotspots = false;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Relay hotspot", desc = "Announce your hunting hotspot to chat.")
        @ConfigEditorBoolean
        public boolean announceHotspot;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Hotspot channel", desc = "Chat command used to relay, e.g. pc / gc / ac.")
        @ConfigEditorText
        public String hotspotCommand = "";

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Split across party", desc = "Give everyone a different hotspot automatically.")
        @ConfigEditorBoolean
        public boolean coordinateHotspots;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Round resets (min)", desc = "Forget claims this long after the last one.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 120f, minStep = 1f)
        public int hotspotRoundMinutes;

        @Expose
        @ConfigOption(name = "Safari Join Assist", desc = "")
        @ConfigEditorAccordion(id = 6)
        public boolean accordionPartySafariJoinAssist = false;

        @Expose
        @ConfigAccordionId(id = 6)
        @ConfigOption(name = "Your role", desc = "none / joiner (enters the Safari, then warps the party) / inviter (invites them back, gives leader).")
        @ConfigEditorText
        public String safariAssistRole = "";

        @Expose
        @ConfigAccordionId(id = 6)
        @ConfigOption(name = "Other player", desc = "The other player's name. Leave blank to pick it up automatically from who leaves / whose party you join.")
        @ConfigEditorText
        public String safariAssistPartner = "";

        @Expose
        @ConfigAccordionId(id = 6)
        @ConfigOption(name = "Delay (s)", desc = "Seconds to wait before each invite, transfer or party message.")
        @ConfigEditorSlider(minValue = 0.0f, maxValue = 10.0f, minStep = 0.1f)
        public double safariAssistDelaySeconds;

    }

    public static class SafariHelpersCategory {

        @Expose
        @ConfigOption(name = "Bee nests", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionSafariHelpersBeeNests = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Highlight nests", desc = "Outline bee nests in the location below.")
        @ConfigEditorBoolean
        public boolean highlightBeeNests;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Location", desc = "Sidebar zone the highlight is limited to.")
        @ConfigEditorText
        public String beeNestLocation = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Colour (hex)", desc = "Outline colour, e.g. 66D9FF.")
        @ConfigEditorText
        public String beeNestColor = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Search radius", desc = "How far to look for nests, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 256f, minStep = 1f)
        public int beeNestScanRadius;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Through walls", desc = "Draw nest outlines through terrain (nests never move, so this is allowed).")
        @ConfigEditorBoolean
        public boolean beeNestThroughWalls;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Nest done critter", desc = "Once caught, bee nest outlines change colour for the rest of the run.")
        @ConfigEditorText
        public String beeNestStopCritter = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hide nests when done", desc = "Stop outlining nests once that critter is caught, rather than recolouring them.")
        @ConfigEditorBoolean
        public boolean hideNestsWhenDone;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Nest done colour", desc = "Outline colour for nests after that critter is caught, e.g. FF4040.")
        @ConfigEditorText
        public String beeNestDoneColor = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Share emptied", desc = "Announce emptied nests and hide ones the party emptied.")
        @ConfigEditorBoolean
        public boolean announceBeeEmptied;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Emptied channel", desc = "Chat command for emptied call-outs.")
        @ConfigEditorText
        public String beeEmptiedChannel = "";

        @Expose
        @ConfigOption(name = "Snoozling walls", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionSafariHelpersSnoozlingWalls = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Snoozling walls list", desc = "Open the list editor for snoozling walls.")
        @ConfigEditorButton(runnableId = 2, buttonText = "Edit")
        public boolean openSnoozlingWalls = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Wall waypoints", desc = "Mark the Snoozling walls still standing, through terrain, while in the Safari.")
        @ConfigEditorBoolean
        public boolean snoozlingWallWaypoints;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Wall waypoint colour", desc = "Hex colour, e.g. FF55FF.")
        @ConfigEditorText
        public String snoozlingWallColor = "";

        @Expose
        @ConfigOption(name = "Rockmites", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionSafariHelpersRockmites = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Track rockmites", desc = "Count mounds opened and Rockmites found.")
        @ConfigEditorBoolean
        public boolean trackRockmites;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Mounds per run", desc = "How many mounds an instance has.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 200f, minStep = 1f)
        public int rockmiteMoundTotal;

        @Expose
        @ConfigOption(name = "Hideyho & quests", desc = "")
        @ConfigEditorAccordion(id = 3)
        public boolean accordionSafariHelpersHideyhoQuests = false;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Click to accept", desc = "Click anywhere with chat open to press the accept button. Use at your own risk.")
        @ConfigEditorBoolean
        public boolean acceptQuestClicks;

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Mob", desc = "Whose dialogue prompt this applies to.")
        @ConfigEditorText
        public String questMobName = "";

        @Expose
        @ConfigAccordionId(id = 3)
        @ConfigOption(name = "Accept button", desc = "The option button to press.")
        @ConfigEditorText
        public String questAcceptLabel = "";

        @Expose
        @ConfigOption(name = "Ground objects", desc = "")
        @ConfigEditorAccordion(id = 4)
        public boolean accordionSafariHelpersGroundObjects = false;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Highlight objects", desc = "Outline item displays paired with an interaction hitbox.")
        @ConfigEditorBoolean
        public boolean highlightGroundObjects;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Colour (hex)", desc = "Ground object outline colour.")
        @ConfigEditorText
        public String groundObjectColor = "";

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Search radius", desc = "How far to look for ground objects, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 128f, minStep = 1f)
        public int groundObjectScanRadius;

        @Expose
        @ConfigAccordionId(id = 4)
        @ConfigOption(name = "Announce coords", desc = "Print coordinates in chat when one is spotted.")
        @ConfigEditorBoolean
        public boolean groundObjectAnnounce;

        @Expose
        @ConfigOption(name = "Warden", desc = "")
        @ConfigEditorAccordion(id = 5)
        public boolean accordionSafariHelpersWarden = false;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Warden ready title", desc = "Title when the warden's hitbox reaches full height.")
        @ConfigEditorBoolean
        public boolean announceWardenReady;

        @Expose
        @ConfigAccordionId(id = 5)
        @ConfigOption(name = "Watch radius", desc = "How far to watch for wardens, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 128f, minStep = 1f)
        public int wardenScanRadius;

    }

    public static class FloorDropsCategory {

        @Expose
        @ConfigOption(name = "Highlight", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionFloorDropsHighlight = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Highlight drops", desc = "Outline clustered marker displays.")
        @ConfigEditorBoolean
        public boolean highlightFloorDrops;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Item token", desc = "Item id a drop's displays carry.")
        @ConfigEditorText
        public String floorDropItemToken = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Cluster size", desc = "Displays needed together to count as a drop.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 16f, minStep = 1f)
        public int floorDropClusterSize;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Colour (hex)", desc = "Drop outline colour.")
        @ConfigEditorText
        public String floorDropColor = "";

    }

    public static class HoneyhivesContestCategory {

        @Expose
        @ConfigOption(name = "Honeyhives", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionHoneyhivesContestHoneyhives = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive beacons", desc = "Beacon the honeyhives that still have honey, while the contest is unfinished.")
        @ConfigEditorBoolean
        public boolean honeyHiveWaypoints;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Only when incomplete", desc = "Hide the hive beacons once the contest is complete.")
        @ConfigEditorBoolean
        public boolean honeyHiveOnlyWhenIncomplete;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive colour", desc = "Beacon colour as hex, e.g. 55FF55.")
        @ConfigEditorText
        public String honeyHiveColor = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive beam height", desc = "How tall the beacon beam is, in blocks.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 2048f, minStep = 1f)
        public int honeyHiveBeamHeight;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive outline", desc = "Also outline the hive block itself.")
        @ConfigEditorBoolean
        public boolean honeyHiveOutline;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive outline through walls", desc = "Show the box on the hive block through terrain.")
        @ConfigEditorBoolean
        public boolean honeyHiveThroughWalls;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Hive range", desc = "How far away a hive can be and still get a beacon.")
        @ConfigEditorSlider(minValue = 16f, maxValue = 512f, minStep = 1f)
        public int honeyHiveRange;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Refill countdown", desc = "Over a hive you looted, show how long until it refills. Remembered across restarts.")
        @ConfigEditorBoolean
        public boolean honeyHiveShowRefill;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Nest survey", desc = "Record fence+trapdoor bee nests to config/shinyhunter-nests.json.")
        @ConfigEditorBoolean
        public boolean surveyNests;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Survey area", desc = "Where the nest survey runs. Blank means anywhere in Skyblock.")
        @ConfigEditorText
        public String surveyLocation = "";

        @Expose
        @ConfigOption(name = "Miria's Contest", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionHoneyhivesContestMiriaSContest = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Track contest", desc = "Show whether this window's contest is complete.")
        @ConfigEditorBoolean
        public boolean trackContest;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Hide when complete", desc = "Hide the panel once the contest is complete.")
        @ConfigEditorBoolean
        public boolean hideContestWhenComplete;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Contest scope", desc = "Where the contest panel shows: skyblock / hypixel / anywhere.")
        @ConfigEditorText
        public String contestScope = "";

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Show timer", desc = "Show the time left until the contest ends.")
        @ConfigEditorBoolean
        public boolean showContestTimer;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Count unclaimed contests", desc = "HUD count of contest rewards waiting to be claimed. Open the contest menu once to set it up.")
        @ConfigEditorBoolean
        public boolean trackUnclaimedContests;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Warn at unclaimed", desc = "Warn with a title and in chat at this many unclaimed contests.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 500f, minStep = 1f)
        public int unclaimedContestWarnAt;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Announce ticket earned", desc = "Say \"Ticket earned!\" in party chat when the contest reaches Uncommon or higher.")
        @ConfigEditorBoolean
        public boolean announceTicketEarned;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Warn if incomplete", desc = "Ring at each mark below while the contest is not complete.")
        @ConfigEditorBoolean
        public boolean contestWarnEnabled;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Warn at (minutes)", desc = "Minutes-left marks to ring at, e.g. 5, 3, 1.")
        @ConfigEditorText
        public String contestWarnMinutes = "";

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Warning popup", desc = "Flash CONTEST INCOMPLETE! on screen at each warning.")
        @ConfigEditorBoolean
        public boolean contestWarnTitle;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Sound id", desc = "Any sound id, e.g. minecraft:block.bell.use or minecraft:entity.player.levelup.")
        @ConfigEditorText
        public String contestSound = "";

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Sound volume %", desc = "100 = one bell. 300 = three at once. Louder needs more copies, not a bigger number.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 999999f, minStep = 1f)
        public int contestSoundVolume;

    }

    public static class HighlightsCategory {

        @Expose
        @ConfigOption(name = "Entity highlights", desc = "")
        @ConfigEditorAccordion(id = 0)
        public boolean accordionHighlightsEntityHighlights = false;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Highlight entities", desc = "Outline the tracked entities below.")
        @ConfigEditorBoolean
        public boolean highlightEntities;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Name labels", desc = "Float a name above matches that have no nametag of their own.")
        @ConfigEditorBoolean
        public boolean highlightLabels;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Name label mode", desc = "When to draw it: never / default (only if the game shows none) / always.")
        @ConfigEditorText
        public String highlightLabelMode = "";

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Outline padding", desc = "Blocks added on every side of an outline so it stands clear of the mob.")
        @ConfigEditorSlider(minValue = 0.05f, maxValue = 1.0f, minStep = 0.1f)
        public double highlightBoxGrow;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Minimum outline size", desc = "Small mobs get an outline at least this big, in blocks.")
        @ConfigEditorSlider(minValue = 0.0f, maxValue = 3.0f, minStep = 0.1f)
        public double highlightBoxMinSize;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Tint mobs", desc = "Colour outlined mobs toward their outline colour.")
        @ConfigEditorBoolean
        public boolean highlightTintMobs;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Ignore capture UI", desc = "Don't treat the capsule's CAPTURING display as a mob.")
        @ConfigEditorBoolean
        public boolean highlightIgnoreCapturing;

        @Expose
        @ConfigAccordionId(id = 0)
        @ConfigOption(name = "Match NBT too", desc = "Also match item/NBT data for unnamed things like rocks.")
        @ConfigEditorBoolean
        public boolean highlightDeepScan;

        @Expose
        @ConfigOption(name = "Tracked entities", desc = "")
        @ConfigEditorAccordion(id = 1)
        public boolean accordionHighlightsTrackedEntities = false;

        @Expose
        @ConfigAccordionId(id = 1)
        @ConfigOption(name = "Tracked entities list", desc = "Open the list editor for tracked entities.")
        @ConfigEditorButton(runnableId = 3, buttonText = "Edit")
        public boolean openTrackedEntities = false;

        @Expose
        @ConfigOption(name = "Tracked blocks", desc = "")
        @ConfigEditorAccordion(id = 2)
        public boolean accordionHighlightsTrackedBlocks = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Tracked blocks list", desc = "Open the list editor for tracked blocks.")
        @ConfigEditorButton(runnableId = 4, buttonText = "Edit")
        public boolean openTrackedBlocks = false;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Highlight blocks", desc = "Outline the block types listed below.")
        @ConfigEditorBoolean
        public boolean highlightBlocks;

        @Expose
        @ConfigAccordionId(id = 2)
        @ConfigOption(name = "Search radius", desc = "How far to look for tracked blocks, in blocks.")
        @ConfigEditorSlider(minValue = 8f, maxValue = 256f, minStep = 1f)
        public int blockScanRadius;

    }

}