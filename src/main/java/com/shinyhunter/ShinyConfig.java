package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every user-tweakable knob, persisted to {@code config/shinyhunter.json}. Deliberately a plain
 * mutable POJO so Gson can round-trip it without any adapters — a field added here needs no other
 * code to start saving.
 */
public final class ShinyConfig {

    /** Set once the first-join hello has been printed. */
    public boolean welcomeShown = false;

    /** Set once the first-launch "pick a key for Reload chunks" prompt has been answered or skipped. */
    public boolean chunkReloadKeyPrompted = false;

    /**
     * While the Reload chunks key is held, reload again every this many ticks. 1 = every tick; the
     * default of 2 is about as fast as holding F3+A with the keyboard's own repeat.
     */
    public int chunkReloadRepeatTicks = 2;

    /** Skip drawing paintings anywhere in the Critter Safari (they hide critters behind them). */
    public boolean hidePaintingsInSafari = true;

    /** Master switch. {@code /shinyhunter toggle}. */
    public boolean enabled = true;

    /** One hunted term and where it applies. */
    public static class Keyword {
        /** Case-insensitive substring that marks a find. */
        public String token;

        /**
         * Keep hunting this term once outside {@link #huntLocation}. Off by default: shinies spawn
         * in the Safari, so hunting elsewhere is mostly noise.
         */
        public boolean huntOutsideSafari = false;

        public Keyword() {
        }

        public Keyword(String token) {
            this.token = token;
        }
    }

    /**
     * Terms that mark a find. An entity matching any one of them alerts.
     *
     * <p>Blank entries are ignored rather than treated as a wildcard: every string contains the
     * empty string, so an empty keyword would match every entity in the world.
     */
    public List<Keyword> huntKeywords = new ArrayList<>(List.of(new Keyword("SPARKLING")));

    /**
     * @deprecated superseded by {@link #huntKeywords}. Kept under its own name so an existing
     * array-of-strings still parses — reusing the key for a list of objects would throw and take
     * the whole config down with it.
     */
    @Deprecated
    public List<String> keywords;

    /** @deprecated superseded by {@link #huntKeywords}; kept only so old configs can be migrated. */
    @Deprecated
    public String keyword;

    /** @deprecated now set per keyword; kept only to seed migrated entries. */
    @Deprecated
    public Boolean huntOutsideSafari;

    /**
     * When true, alerts only fire while the sidebar says we're in Skyblock — which is what keeps
     * this quiet in the main Hypixel lobby, on other servers, and in single-player.
     */
    public boolean onlyInSkyblock = true;

    /** Draw labelled markers for coordinates picked out of party chat. */
    public boolean waypointsEnabled = true;

    /** Walking this close to a waypoint, in blocks, removes it. */
    public double waypointClearRadius = 3.0;

    /** Waypoint colour as hex, with or without a leading {@code #}. */
    public String waypointColor = "FFDD55";

    /** The area a keyword is confined to unless its own outside-Safari flag is set. */
    public String huntLocation = "Critter Safari";

    /** Play a level-up ding on a find. */
    public boolean playSound = true;

    /** Also show the alert on the action bar, not just in chat. */
    public boolean actionBar = true;

    /** Don't re-announce the same entity again until this many seconds have passed. */
    public int renotifySeconds = 60;

    // ---------------------------------------------------------------- hotspot relay

    /** Relay Hypixel's "Hunting Hotspot" announcement to a chat channel. */
    public boolean announceHotspot = false;

    /** The chat command to relay through — {@code pc} for party, {@code gc} for guild, {@code ac} for all. */
    public String hotspotCommand = "pc";

    /** Relay text. {@code {message}} is the whole original line, {@code {biome}} just the biome name. */
    public String hotspotFormat = "{message}";

    /** Don't relay the same biome again within this many seconds — also the anti-echo backstop. */
    public int hotspotCooldownSeconds = 60;

    /**
     * Split the four hotspots across everyone in the party running this mod, using the party-chat
     * announcements as the shared ordering. Requires {@link #announceHotspot} to be on, or nobody
     * hears your claim.
     */
    public boolean coordinateHotspots = false;

    /** Claims are forgotten this long after the last one, so a new round starts clean. */
    public int hotspotRoundMinutes = 10;

    // ---------------------------------------------------------------- floor drop highlight

    /** Outline floor drops — tight clusters of item displays showing the marker item. */
    public boolean highlightFloorDrops = true;

    /** The item a drop's displays carry, matched against the item's registry id. */
    public String floorDropItemToken = "minecraft:string";

    /** How many displays must sit together before it counts as a drop rather than scenery. */
    public int floorDropClusterSize = 2;

    /** How close displays must be, in blocks, to count as one cluster. */
    public double floorDropClusterRadius = 1.5;

    /** How far out to look for drops. */
    public int floorDropScanRadius = 32;

    /** Outline colour as hex, with or without a leading {@code #} and optional alpha pair. */
    public String floorDropColor = "FF55FF";

    /**
     * Also treat a tight cluster of stationary item displays as a floor drop, whatever item they
     * show. Hypixel builds each drop out of several displays, and those are sent from much further
     * away than the item data is worth reading — so this finds drops well beyond the usual scan,
     * at the cost of matching anything else built the same way.
     */
    public boolean floorDropItemDisplays = false;

    /** How many stationary displays within the cluster radius make a drop. */
    public int floorDropDisplayCount = 3;

    /** How far away a display-built drop may be and still be outlined. 0 uses the scan radius. */
    public int floorDropDisplayRadius = 0;

    // ---------------------------------------------------------------- named entity highlight

    /** Outline entities whose nameplate matches any of {@link #highlightNames}. */
    public boolean highlightEntities = true;

    /**
     * One tracked thing: what to match, how to draw it, and whether finding one is worth announcing.
     * Managed from the config screen.
     */
    public static class Tracker {
        /** Case-insensitive substring matched against the nameplate and, optionally, item/NBT data. */
        public String token;
        public boolean enabled = true;
        /** Outline colour as hex, with or without a leading {@code #}. */
        public String color = "FFAA33";
        /** Announce the find and its coordinates in chat. */
        public boolean announce = false;
        /** Free-text group name; the config screen bundles same-named trackers under one heading. */
        public String group = DEFAULT_GROUP;

        public Tracker() {
        }

        public Tracker(String token) {
            this.token = token;
        }

        public Tracker(String token, String group) {
            this.token = token;
            this.group = group;
        }
    }

    /** Group used for anything that hasn't been filed elsewhere. */
    public static final String DEFAULT_GROUP = "General";

    /**
     * One tracked block type. Works like {@link Tracker} but matches against the block's registry
     * id, so {@code minecraft:diamond_ore} and the looser {@code ore} both work.
     */
    public static class BlockTracker {
        /** Case-insensitive substring matched against the block's registry id. */
        public String blockId;
        public boolean enabled = true;
        /** Outline colour as hex, with or without a leading {@code #}. */
        public String color = "66D9FF";
        /** Free-text group name; the config screen bundles same-named entries under one heading. */
        public String group = DEFAULT_GROUP;

        public BlockTracker() {
        }

        public BlockTracker(String blockId) {
            this.blockId = blockId;
        }
    }

    /**
     * Extra block types to outline. Bee nests are handled separately — they carry the emptied-nest
     * sharing that these don't need.
     */
    public List<BlockTracker> blockTrackers = new ArrayList<>();

    /** Master switch for the tracked-block outlines. */
    public boolean highlightBlocks = true;

    /** How far from the player to search for tracked blocks, in blocks. */
    public int blockScanRadius = 64;

    /**
     * Everything being tracked. Empty by default: the critters that used to be listed here are
     * already outlined by the missing-critter highlighter, which knows what has been caught — two
     * boxes on the same mob, one of which never goes away, is worse than one.
     */
    public List<Tracker> trackers = new ArrayList<>();

    /** Set once the old default critter trackers have been cleaned out of an existing config. */
    public boolean critterTrackerDefaultsRemoved = false;

    /** @deprecated superseded by {@link #trackers}; kept only so old configs can be migrated. */
    @Deprecated
    public List<String> highlightNames;

    /** Outline colour as hex, with or without a leading {@code #} and optional alpha pair. */
    public String highlightColor = "FFAA33";

    /** Float the matched name above the entity. */
    public boolean highlightLabels = true;

    /**
     * When the floating name is drawn: {@code default} only where the game shows no nameplate of
     * its own (no doubled-up names), {@code always} regardless, {@code never} at all.
     */
    public String highlightLabelMode = "default";

    /** Added on every side of an entity outline, so it stands clear of the mob instead of hugging it. */
    public double highlightBoxGrow = 0.2;

    /** Every side of an entity outline is at least this long, so tiny mobs still get a visible box. */
    public double highlightBoxMinSize = 0.9;

    /** Tint outlined mobs toward their outline colour, so the mob itself stands out too. */
    public boolean highlightTintMobs = true;

    /**
     * Also match watched names against item and NBT data, not just the nameplate — needed for
     * things that carry no visible name, like the rocks that precede a Rockmite.
     */
    public boolean highlightDeepScan = true;

    /** How far out the deep data check runs. Bounded because it's much costlier than name matching. */
    public int highlightDeepRadius = 48;

    /** Skip matches that belong to the capsule's "CAPTURING" display rather than a mob in the world. */
    public boolean highlightIgnoreCapturing = true;

    // ---------------------------------------------------------------- ground objects

    /**
     * Outline interactable ground objects — Rockmite rocks and punchable cases. Both are an
     * item display with an interaction entity paired directly beneath it.
     */
    public boolean highlightGroundObjects = true;

    /** How close the display and its interaction must be, in blocks. */
    public double groundObjectPairDistance = 1.0;

    /** How far out to look for them. */
    public int groundObjectScanRadius = 32;

    /** Outline colour as hex, with or without a leading {@code #}. */
    public String groundObjectColor = "B98A5A";

    /** Print coordinates in chat when one is spotted. */
    public boolean groundObjectAnnounce = false;

    // ---------------------------------------------------------------- shard trades

    /** Watch hunter NPC dialogue for shard trades and relay them to chat. */
    public boolean announceShardTrades = false;

    /** Channel command used for shard trade call-outs. */
    public String shardTradeChannel = "pc";

    /** Show a title when someone calls out a trade whose cost item you're carrying. */
    public boolean titleWhenTradeAffordable = true;

    // ---------------------------------------------------------------- party coordination

    /** Handle {@code !dt [reason]} in party chat: hold the Safari Manager after the run. */
    public boolean downtimeCommand = true;

    /** Channel command used for the downtime announcement. */
    public String downtimeChannel = "pc";

    /** Handle {@code !timer <length>} in party chat and call out when it expires. */
    public boolean timerCommand = true;

    /** Channel command used for timer expiry call-outs. */
    public String timerChannel = "pc";

    /** Call out picking up bird food, and running out when the feeder attracts a bird. */
    public boolean announceBirdFood = false;

    /** Channel command used for bird food call-outs. */
    public String birdFoodChannel = "pc";

    /**
     * Outline every critter still outstanding on this run, and stop the moment one is caught.
     * Driven by the critter tracker rather than the tracker list, so all of them count — and a
     * known critter is never also drawn from the tracker list, which would double the box.
     */
    public boolean highlightMissingCritters = true;

    /** Outline colour for outstanding critters, as hex. */
    public String missingCritterColor = "FFC94A";

    /**
     * Critter that, once caught, turns the bee nest outlines to {@link #beeNestDoneColor} for the
     * rest of the run — the unique is secured, the nests are now optional. Blank disables the link.
     */
    public String beeNestStopCritter = "Honeybug";

    /** Stop outlining nests altogether once that critter is caught, instead of recolouring them. */
    public boolean hideNestsWhenDone = false;

    /** Outline colour for nests once that critter has been caught. */
    public String beeNestDoneColor = "FF4040";

    // ---------------------------------------------------------------- miria's contest

    /** Show the contest status panel and track completion. */
    public boolean trackContest = true;

    /** Hide the panel once this window's contest is complete. */
    public boolean hideContestWhenComplete = false;

    /** Show the time left until the contest ends. */
    public boolean showContestTimer = true;

    /**
     * Where the contest panel may show: {@code skyblock}, {@code hypixel} or {@code anywhere}.
     * The schedule is wall-clock, so it stays correct wherever it's drawn.
     */
    public String contestScope = "skyblock";

    /** Ring while the contest is still incomplete at each mark in {@link #contestWarnMinutes}. */
    public boolean contestWarnEnabled = true;

    /** Minutes-left marks to ring at, comma separated. */
    public String contestWarnMinutes = "5, 3, 1";

    /** Also flash "CONTEST INCOMPLETE!" on screen at each warning. */
    public boolean contestWarnTitle = true;

    /** Sound id to ring with, e.g. {@code minecraft:block.bell.use}. */
    public String contestSound = "minecraft:block.bell.use";

    /** Volume as a percentage. Deliberately unclamped. */
    public int contestSoundVolume = 100;

    // ---------------------------------------------------------------- sparkling signatures

    /** Treat a trail of particles around a mob as a sparkling, even with no name visible. */
    public boolean particleDetection = true;

    /** Particle ids that make up a sparkling trail. Confirmed in game as {@code wax_on}. */
    public List<String> sparklingParticles = new ArrayList<>(List.of("minecraft:wax_on"));

    /** How close to the mob a particle has to be to count toward its trail. */
    public double particleRadius = 4.0;

    /**
     * Trail packets needed inside the window before the mob counts as sparkling. A real trail is
     * sparse — a sparkling Litterbug showed 2–3 {@code wax_on} packets per 5 seconds within 5
     * blocks — so this is deliberately low; nothing else in the Safari emits {@code wax_on}.
     */
    public int particleThreshold = 2;

    /** How far back the trail is counted, in seconds. */
    public int particleWindowSeconds = 5;

 // Driftling (entity dump)

    // ---------------------------------------------------------------- party dex

    /** Look up each party member's Sparkling Critterdex as they join, and share the skip list. */
    public boolean partyDexEnabled = true;

    /** Post the shared-sparklings result to party chat automatically when the party fills. */
    public boolean partyDexAnnounce = false;

    /** Answer {@code !shared} in party chat. Independent of the automatic party-dex lookups. */
    public boolean sharedCommand = true;

    /** Leave sparklings the whole party already has off the missing list and HUD. */
    public boolean partyDexSkipShared = true;

    /** Most sparklings {@code !shared} lists before "(+N more)". */
    public int sharedListMax = 15;

    /**
     * The sparklings worth skipping — the ones {@code !shared} (without "all") reports. Names are
     * matched loosely, so plurals and casing don't matter.
     */
    public List<String> usefulSparklings = new ArrayList<>(List.of(
            "Honeybug", "Parakeet", "Bluebird", "Macaw", "Rockmite",
            "Snoozle", "Gemzie", "Gazer", "Wumpa", "Doomspiral"));

    /**
     * Dotted path to the Sparkling Critterdex inside a profile member. Confirmed against a live
     * profile on 2026-09-19; if Hypixel moves it, blank this and the parser searches for it again.
     */
    public String sparklingDexPath = "safari.discovered_sparkling_critters";

    // ---------------------------------------------------------------- party chat

    /**
     * Send a party message immediately when nothing has gone out recently, instead of on the next
     * tick. The spacing between bursts is kept; only the idle case is sped up.
     */
    public boolean instantPartyChat = true;

    // ---------------------------------------------------------------- safari manager

    /** Refuse to click the Safari Manager until the whole party is here. Sneak-click overrides. */
    public boolean guardSafariManager = true;

    /** Nameplate text identifying the NPC to guard. */
    public String safariManagerName = "Safari Manager";

    // ---------------------------------------------------------------- snoozling walls

    /** Waypoints on the Snoozling walls still standing, visible through terrain, while in the Safari. */
    public boolean snoozlingWallWaypoints = true;

    /** Colour of the Snoozling wall waypoints, as hex. */
    public String snoozlingWallColor = "FF55FF";

    /**
     * The five wall positions, as "x y z". Fixed per instance layout, so they're a setting rather
     * than something discovered — if Hypixel moves them, this is the one thing to edit.
     */
    public List<String> snoozlingWalls = new ArrayList<>(List.of(
            "-70 40 68",
            "-114 40 87",
            "-126 40 74",
            "-95 40 42",
            "-97 40 17"));

    // ---------------------------------------------------------------- rockmites

    /** Count Rockmite mounds opened and Rockmites found. */
    public boolean trackRockmites = true;

    /** How many mounds an instance has, i.e. when to say they're all opened. */
    public int rockmiteMoundTotal = 20;

    // ---------------------------------------------------------------- timers and splits

    /** Show the run clock. */
    public boolean showTimer = true;

    /** Record per-biome splits and compare them against saved bests. */
    public boolean trackSplits = true;

    /** Show the splits panel on the HUD. */
    public boolean showSplitHud = true;

    /**
     * Personal catches in a biome needed before its split counts toward your own best. Several
     * biomes can clear this in one run, which is the point — clearing two is ordinary.
     */
    public int ownBiomeMinCatches = 8;

    /** Say each split in chat as it's taken. */
    public boolean announceSplits = false;

    /** Best time per split for biomes this player was working, in milliseconds. */
    public Map<String, Long> personalBests = new LinkedHashMap<>();

    /** Best time per split for the party as a whole, in milliseconds. */
    public Map<String, Long> partyBests = new LinkedHashMap<>();

    // ---------------------------------------------------------------- safari manager (cont.)

    /** Also refuse the ticket item itself, which is how a non-leader starts a run. */
    public boolean guardTicketItem = true;

    /** Item-name text identifying a Safari ticket. */
    public String ticketItemName = "Critter Safari Ticket";

    /**
     * Count players within this many blocks instead of trusting the tab list alone. The tab list
     * carries party members who are still elsewhere, which is the case the guard exists to catch.
     */
    public int presenceRadius = 10;

    /** Also hold the manager shut when somebody calls for a kick in party chat. */
    public boolean blockOnKickCall = true;

    /** How long a called kick keeps the manager blocked. */
    public int kickBlockSeconds = 30;

    // ---------------------------------------------------------------- hideyho

    /** The critter whose position is remembered for that command. */
    public String hideyhoName = "Hideyho";

    /** Channel command used to answer. */
    public String hideyhoChannel = "pc";

    /** Every spot Hideyho can hide at, as "x,y,z". It's judged settled once within a few blocks of one. */
    public List<String> hideyhoSpots = new ArrayList<>(List.of(
            "13,71,-88", "-4,68,-64", "19,70,-24", "-28,72,-80", "-4,71,-79", "37,70,-14", "-20,71,-80",
            "-6,87,-63", "13,79,-50", "27,71,-53", "12,71,-61", "0,79,-78", "-13,79,-84", "-13,79,-57",
            "0,71,-56", "-7,79,-83", "-17,71,-59", "18,79,-63", "-21,79,-78", "-8,87,-70"));

    /**
     * The mansion's footprint as corners in walking order, "x y z". Only x and z are used — the
     * building isn't a box and its floors sit at different heights, so the outline is what counts.
     */
    public List<String> hideyhoMansion = new ArrayList<>(List.of(
            "-28.5 67 -46.5", "-28.5 70 -75.5", "-23.5 70 -75.5", "-24.5 71 -85.5", "10 69 -87", "10 69 -84",
            "17 69 -84", "17 73 -76", "21 70 -76", "21 69 -55", "17 70 -55", "17 69 -47"));

    // ---------------------------------------------------------------- gems

    /** Call out gem placements to chat. */
    public boolean announceGems = false;

    /** Channel command used for gem call-outs. */
    public String gemChannel = "pc";

    // ---------------------------------------------------------------- quest prompts

    /** Let a click anywhere with chat open answer a mob's dialogue prompt. */
    public boolean acceptQuestClicks = true;

    /** The mob whose prompt this applies to. */
    public String questMobName = "Hideyho";

    /** The option button to press. */
    public String questAcceptLabel = "Sure";

    // ---------------------------------------------------------------- critters

    /** Track which critters have turned up on the current Safari instance. */
    public boolean trackCritters = true;

    /** Show a HUD panel per biome listing the critters still missing. */
    public boolean showCritterHud = true;

    /**
     * Which biomes' missing-critter panels are shown. A biome absent from this map is shown — the
     * default is everything on, and turning one off is the deliberate act.
     */
    public Map<String, Boolean> critterHudBiomes = new LinkedHashMap<>();

    /**
     * Most critters to list per biome panel before collapsing the rest into "+ N others...". Zero
     * means no limit. Keyed by biome so a biome you're working can stay long while the others stay
     * short.
     */
    public Map<String, Integer> critterHudMax = new LinkedHashMap<>();

    /** Announce "All uniques caught!" once every biome is complete. */
    public boolean announceAllUniques = false;

    /**
     * The mark used for a completed biome in the {@code !missing} summary.
     *
     * <p>Configurable because Hypixel filters chat and drops glyphs it doesn't allow without saying
     * so, which turns "done" into an empty slot that reads as missing data. Set it to a word if the
     * tick ever stops coming through.
     */
    public String clearMark = "✔";

    /**
     * Outline shulkers in the Safari before their name arrives. The shulker loads from further
     * away than the armour stand naming it, so it's shown as "Shulker" first, named once the
     * stand is in range, and remembered by name for the rest of the run.
     */
    public boolean shulkerIsHideonfloor = true;

    /** Keep shulkers outlined (by name) even after that critter is caught this run. */
    public boolean alwaysHighlightShulkers = false;

    /** Announce "<biome> clear!" once every critter in a biome has been seen. */
    public boolean announceBiomeClear = false;

    /** Announce "Forest 8/9, missing Macaw" the moment the Macaw is the only Forest critter left. */
    public boolean announceMacawOnly = false;

    /** Reply in chat when somebody asks the party with {@code !missing}. */
    public boolean respondToMissing = true;

    /** Channel command used for critter call-outs. */
    public String critterChannel = "pc";

    /** Show the on-screen HUD panels. */
    public boolean showHud = true;

    /** Where one HUD panel has been placed, once it's been moved off the automatic stack. */
    public static class PanelPlacement {
        public int x;
        public int y;
        public float scale = 1.0f;
    }

    /**
     * Per-panel placement, keyed by panel id. Absent means the panel is still in the automatic
     * stack; the JARVIS editor fills these in as panels are dragged or resized.
     */
    public Map<String, PanelPlacement> hudPanels = new LinkedHashMap<>();

    /** HUD distance from the left edge, in pixels. */
    public int hudX = 4;

    /** HUD distance from the top edge, in pixels. */
    public int hudY = 4;

    /** Announce to chat when the Safari Manager says you've used your last Critter Capsule. */
    public boolean announceOutOfCapsules = false;

    /** Channel command used for the out-of-capsules call-out. */
    public String capsuleChannel = "pc";

    /**
     * Set once the shard-trading hunters have been added to the tracker list, so they aren't
     * re-added every load after being deliberately removed.
     */
    public boolean seededShardHunters = false;

    // ---------------------------------------------------------------- warden

    /** Title when the Safari warden's hitbox grows from its spawn animation to full height. */
    public boolean announceWardenReady = true;

    /** How far out to watch for wardens. */
    public int wardenScanRadius = 48;

    // ---------------------------------------------------------------- bee nest highlight

    /** Outline nearby bee nests while standing in {@link #beeNestLocation}. */
    public boolean highlightBeeNests = true;

    /** Sidebar location the highlight is limited to. Matched as a substring, case-insensitively. */
    public String beeNestLocation = "Critter Safari";

    /** Outline colour as hex, with or without a leading {@code #} and optional alpha pair. */
    public String beeNestColor = "66D9FF";

    /**
     * Record fixture bee nests (oak fence below, oak trapdoor above) to
     * {@code config/shinyhunter-nests.json} while walking the survey area.
     */
    public boolean surveyNests = false;

    /**
     * Where the survey runs, matched against the sidebar. Blank — the default — surveys anywhere
     * in Skyblock, which is what you want while walking a map: the zone row names the sub-area
     * you're standing in ("Critter Safari Entrance"), not the island, so naming the island here
     * would quietly switch the survey off for most of it. Every nest records its own zone, so
     * anything picked up elsewhere is easy to tell apart afterwards.
     */
    public String surveyLocation = "";

    /**
     * Beacon over each honeyhive that still has honey in it, while Miria's Contest is unfinished.
     * Reads the block where it can see it, this session's own loots, and Hypixel's refill times.
     */
    public boolean honeyHiveWaypoints = true;

    /** Only while the contest is incomplete; off shows them whenever you're in Skyblock. */
    public boolean honeyHiveOnlyWhenIncomplete = true;

    /** Beacon colour, as hex. */
    public String honeyHiveColor = "55FF55";

    /** How tall the beam is, in blocks. A real beacon reaches the build limit. */
    public int honeyHiveBeamHeight = 320;

    /** Outline the hive block as well as beaming it. */
    public boolean honeyHiveOutline = true;

    /** How far away a hive can be and still get a beacon. */
    public int honeyHiveRange = 256;

    /** Also beacon hives whose state isn't known yet, in a dimmer colour. */
    public boolean honeyHiveShowUnknown = true;

    /**
     * Draw the box on the hive block through terrain.
     *
     * <p>The one outline allowed through walls, alongside the Snoozling wall markers: a hive is
     * a fixed piece of scenery whose position is public knowledge and already written down in the
     * survey file, so the outline shows where a thing that never moves is, not where anything
     * hidden is. The beam above it is a real beacon beam and is occluded like one.
     */
    public boolean honeyHiveThroughWalls = true;

    /** Announce emptied nests to chat and hide ones the party has already announced. */
    public boolean announceBeeEmptied = false;

    /** Channel command used for emptied-nest call-outs. */
    public String beeEmptiedChannel = "pc";

    /**
     * How far from the player to search for nests, in blocks. Defaults to the server's usual 8-chunk
     * block view distance — the search reads chunk data, which is loaded far beyond the much shorter
     * range at which the server bothers to send entities.
     */
    public int beeNestScanRadius = 128;

    // ---------------------------------------------------------------- persistence

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ShinyConfig instance;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter.json");
    }

    public static ShinyConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ShinyConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                ShinyConfig loaded = GSON.fromJson(json, ShinyConfig.class);
                if (loaded != null) {
                    // A hand-edited file could blank these out; fall back rather than match "".
                    if (loaded.huntKeywords == null) {
                        loaded.huntKeywords = new ArrayList<>();
                    }
                    // Whether hunting continued outside the Safari used to be one global switch.
                    // Migrated keywords inherit it so behaviour doesn't change under the user.
                    boolean inheritedScope = Boolean.TRUE.equals(loaded.huntOutsideSafari);

                    // A list of plain strings, from before each keyword carried its own scope.
                    if (loaded.keywords != null && !loaded.keywords.isEmpty()) {
                        loaded.huntKeywords.clear();
                        for (String token : loaded.keywords) {
                            if (token != null && !token.isBlank()) {
                                Keyword keyword = new Keyword(token.trim());
                                keyword.huntOutsideSafari = inheritedScope;
                                loaded.huntKeywords.add(keyword);
                            }
                        }
                    }
                    loaded.keywords = null;

                    // Older still: a single keyword. Their explicit choice, so it replaces the
                    // default rather than joining it.
                    if (loaded.keyword != null && !loaded.keyword.isBlank()) {
                        loaded.huntKeywords.clear();
                        Keyword keyword = new Keyword(loaded.keyword.trim());
                        keyword.huntOutsideSafari = inheritedScope;
                        loaded.huntKeywords.add(keyword);
                        loaded.keyword = null;
                    }
                    loaded.huntOutsideSafari = null;
                    if (loaded.renotifySeconds < 0) {
                        loaded.renotifySeconds = 60;
                    }
                    if (loaded.hotspotCommand == null || loaded.hotspotCommand.isBlank()) {
                        loaded.hotspotCommand = "pc";
                    }
                    if (loaded.hotspotFormat == null || loaded.hotspotFormat.isBlank()) {
                        loaded.hotspotFormat = "{message}";
                    }
                    if (loaded.hotspotCooldownSeconds < 1) {
                        loaded.hotspotCooldownSeconds = 60;
                    }
                    if (loaded.hotspotRoundMinutes < 1) {
                        loaded.hotspotRoundMinutes = 10;
                    }
                    if (loaded.floorDropItemToken == null || loaded.floorDropItemToken.isBlank()) {
                        loaded.floorDropItemToken = "minecraft:string";
                    }
                    if (loaded.floorDropColor == null || loaded.floorDropColor.isBlank()) {
                        loaded.floorDropColor = "FF55FF";
                    }
                    loaded.floorDropClusterSize = Math.clamp(loaded.floorDropClusterSize, 1, 16);
                    loaded.floorDropClusterRadius = Math.clamp(loaded.floorDropClusterRadius, 0.25, 8.0);
                    loaded.floorDropScanRadius = Math.clamp(loaded.floorDropScanRadius, 8, 128);
                    if (loaded.trackers == null) {
                        loaded.trackers = new ArrayList<>();
                    }
                    if (!loaded.critterTrackerDefaultsRemoved) {
                        // One-time cleanup of the critter trackers older versions shipped with.
                        // Only untouched ones go: a custom colour or an announcing tracker is
                        // something the player set up deliberately and is left alone.
                        loaded.critterTrackerDefaultsRemoved = true;
                        loaded.trackers.removeIf(tracker -> tracker != null
                                && CritterDex.canonical(tracker.token) != null
                                && !tracker.announce
                                && ("FFAA33".equalsIgnoreCase(tracker.color)
                                        || tracker.color == null || tracker.color.isBlank()));
                    }
                    // Old configs stored a flat list of names. Carry them across once so an
                    // existing watch list isn't silently lost when the richer form takes over.
                    // The list is CLEARED first: an old config has no "trackers" key at all, so the
                    // field initializer's defaults are still sitting there, and appending produced
                    // one duplicate of every default.
                    if (loaded.highlightNames != null && !loaded.highlightNames.isEmpty()) {
                        loaded.trackers.clear();
                        for (String name : loaded.highlightNames) {
                            if (name != null && !name.isBlank()) {
                                loaded.trackers.add(new Tracker(name.trim(), DEFAULT_GROUP));
                            }
                        }
                        loaded.highlightNames = null;
                    }
                    for (Tracker tracker : loaded.trackers) {
                        if (tracker.color == null || tracker.color.isBlank()) {
                            tracker.color = "FFAA33";
                        }
                        if (tracker.group == null || tracker.group.isBlank()) {
                            tracker.group = DEFAULT_GROUP;
                        }
                    }
                    if (loaded.blockTrackers == null) {
                        loaded.blockTrackers = new ArrayList<>();
                    }
                    for (BlockTracker tracker : loaded.blockTrackers) {
                        if (tracker.color == null || tracker.color.isBlank()) {
                            tracker.color = "66D9FF";
                        }
                        if (tracker.group == null || tracker.group.isBlank()) {
                            tracker.group = DEFAULT_GROUP;
                        }
                    }
                    loaded.blockScanRadius = Math.clamp(loaded.blockScanRadius, 8, 256);

                    // Repairs configs already written with the duplicated list above.
                    dedupeTrackers(loaded.trackers);
                    seedShardHunters(loaded);
                    if (loaded.highlightColor == null || loaded.highlightColor.isBlank()) {
                        loaded.highlightColor = "FFAA33";
                    }
                    if (loaded.groundObjectColor == null || loaded.groundObjectColor.isBlank()) {
                        loaded.groundObjectColor = "B98A5A";
                    }
                    if (loaded.shardTradeChannel == null || loaded.shardTradeChannel.isBlank()) {
                        loaded.shardTradeChannel = "pc";
                    }
                    if (loaded.beeEmptiedChannel == null || loaded.beeEmptiedChannel.isBlank()) {
                        loaded.beeEmptiedChannel = "pc";
                    }
                    if (loaded.capsuleChannel == null || loaded.capsuleChannel.isBlank()) {
                        loaded.capsuleChannel = "pc";
                    }
                    if (loaded.huntLocation == null || loaded.huntLocation.isBlank()) {
                        loaded.huntLocation = "Critter Safari";
                    }
                    if (loaded.waypointColor == null || loaded.waypointColor.isBlank()) {
                        loaded.waypointColor = "FFDD55";
                    }
                    loaded.waypointClearRadius = Math.clamp(loaded.waypointClearRadius, 0.5, 32.0);
                    if (loaded.downtimeChannel == null || loaded.downtimeChannel.isBlank()) {
                        loaded.downtimeChannel = "pc";
                    }
                    if (loaded.birdFoodChannel == null || loaded.birdFoodChannel.isBlank()) {
                        loaded.birdFoodChannel = "pc";
                    }
                    if (loaded.timerChannel == null || loaded.timerChannel.isBlank()) {
                        loaded.timerChannel = "pc";
                    }
                    if (loaded.critterChannel == null || loaded.critterChannel.isBlank()) {
                        loaded.critterChannel = "pc";
                    }
                    if (loaded.missingCritterColor == null || loaded.missingCritterColor.isBlank()) {
                        loaded.missingCritterColor = "FFC94A";
                    }
                    if (loaded.gemChannel == null || loaded.gemChannel.isBlank()) {
                        loaded.gemChannel = "pc";
                    }
                    if (loaded.safariManagerName == null || loaded.safariManagerName.isBlank()) {
                        loaded.safariManagerName = "Safari Manager";
                    }
                    if (loaded.hideyhoName == null || loaded.hideyhoName.isBlank()) {
                        loaded.hideyhoName = "Hideyho";
                    }
                    if (loaded.hideyhoChannel == null || loaded.hideyhoChannel.isBlank()) {
                        loaded.hideyhoChannel = "pc";
                    }
                    if (loaded.hideyhoSpots == null || loaded.hideyhoSpots.isEmpty()) {
                        loaded.hideyhoSpots = new ArrayList<>(new ShinyConfig().hideyhoSpots);
                    }
                    if (loaded.hideyhoMansion == null || loaded.hideyhoMansion.size() < 3) {
                        loaded.hideyhoMansion = new ArrayList<>(new ShinyConfig().hideyhoMansion);
                    }
                    if (loaded.questMobName == null || loaded.questMobName.isBlank()) {
                        loaded.questMobName = "Hideyho";
                    }
                    if (loaded.questAcceptLabel == null || loaded.questAcceptLabel.isBlank()) {
                        loaded.questAcceptLabel = "Sure";
                    }
                    if (loaded.hudPanels == null) {
                        loaded.hudPanels = new LinkedHashMap<>();
                    }
                    for (PanelPlacement placement : loaded.hudPanels.values()) {
                        // A zero scale would make a panel invisible with no obvious way back.
                        placement.scale = (float) Math.clamp(placement.scale, 0.25, 4.0);
                    }
                    loaded.kickBlockSeconds = Math.clamp(loaded.kickBlockSeconds, 1, 600);
                    loaded.presenceRadius = Math.clamp(loaded.presenceRadius, 2, 64);
                    loaded.ownBiomeMinCatches = Math.clamp(loaded.ownBiomeMinCatches, 1, 20);
                    loaded.rockmiteMoundTotal = Math.clamp(loaded.rockmiteMoundTotal, 1, 200);
                    if (loaded.contestSound == null || loaded.contestSound.isBlank()) {
                        loaded.contestSound = "minecraft:block.bell.use";
                    }
                    if (loaded.contestWarnMinutes == null) {
                        loaded.contestWarnMinutes = "5, 3, 1";
                    }
                    loaded.sharedListMax = Math.clamp(loaded.sharedListMax, 1, 37);
                    if (loaded.sparklingParticles == null) {
                        loaded.sparklingParticles = new ArrayList<>(List.of("minecraft:wax_on"));
                    }
                    // The original defaults (8 in 3s at 2.5 blocks) were a guess that a real
                    // trail never reaches; configs still carrying them get the measured ones.
                    if (loaded.particleThreshold == 8 && loaded.particleWindowSeconds == 3) {
                        loaded.particleThreshold = 2;
                        loaded.particleWindowSeconds = 5;
                        if (loaded.particleRadius == 2.5) {
                            loaded.particleRadius = 4.0;
                        }
                    }
                    loaded.particleRadius = Math.clamp(loaded.particleRadius, 0.5, 16.0);
                    loaded.particleThreshold = Math.clamp(loaded.particleThreshold, 1, 500);
                    loaded.particleWindowSeconds = Math.clamp(loaded.particleWindowSeconds, 1, 5);
                    if (loaded.usefulSparklings == null) {
                        loaded.usefulSparklings = new ArrayList<>(List.of(
                                "Honeybug", "Parakeet", "Bluebird", "Macaw", "Rockmite",
                                "Snoozle", "Gemzie", "Gazer", "Wumpa", "Doomspiral"));
                    }
                    if (loaded.sparklingDexPath == null) {
                        loaded.sparklingDexPath = "safari.discovered_sparkling_critters";
                    }
                    if (loaded.clearMark == null || loaded.clearMark.isBlank()) {
                        loaded.clearMark = "✔";
                    }
                    if (loaded.snoozlingWalls == null || loaded.snoozlingWalls.isEmpty()) {
                        loaded.snoozlingWalls = new ArrayList<>(List.of(
                                "-70 40 68", "-114 40 87", "-126 40 74", "-95 40 42", "-97 40 17"));
                    }
                    if (loaded.critterHudBiomes == null) {
                        loaded.critterHudBiomes = new LinkedHashMap<>();
                    }
                    if (loaded.critterHudMax == null) {
                        loaded.critterHudMax = new LinkedHashMap<>();
                    }
                    if (loaded.personalBests == null) {
                        loaded.personalBests = new LinkedHashMap<>();
                    }
                    if (loaded.partyBests == null) {
                        loaded.partyBests = new LinkedHashMap<>();
                    }
                    if (loaded.ticketItemName == null || loaded.ticketItemName.isBlank()) {
                        loaded.ticketItemName = "Critter Safari Ticket";
                    }
                    loaded.groundObjectPairDistance = Math.clamp(loaded.groundObjectPairDistance, 0.25, 8.0);
                    loaded.groundObjectScanRadius = Math.clamp(loaded.groundObjectScanRadius, 8, 128);
                    if (loaded.beeNestLocation == null) {
                        loaded.beeNestLocation = "Critter Safari";
                    }
                    if (loaded.beeNestDoneColor == null || loaded.beeNestDoneColor.isBlank()) {
                        loaded.beeNestDoneColor = "FF4040";
                    }
                    if (loaded.beeNestColor == null || loaded.beeNestColor.isBlank()) {
                        loaded.beeNestColor = "66D9FF";
                    }
                    // Clamped: the scan cost grows with the cube of this, and beyond render
                    // distance there are no loaded chunks to find anything in anyway.
                    // Configs written before the range was widened carry the old 48-block default,
                    // which silently capped the search well inside the loaded chunks. Anyone who
                    // never changed it gets the new default; a deliberate value is left alone.
                    if (loaded.beeNestScanRadius == 48) {
                        loaded.beeNestScanRadius = 128;
                    }
                    loaded.beeNestScanRadius = Math.clamp(loaded.beeNestScanRadius, 8, 256);
                    return loaded;
                }
            } catch (Exception e) {
                ShinyHunterClient.LOGGER.warn("Couldn't read {}, using defaults", path, e);
            }
        }
        // No file yet (or it was unreadable): start from defaults, seeded the same way a loaded
        // config is so a fresh install gets the shard hunters too.
        ShinyConfig fresh = new ShinyConfig();
        seedShardHunters(fresh);
        return fresh;
    }

    /** The four hunters observed offering shard trades. */
    static final List<String> SHARD_HUNTERS = List.of(
            "Hunter Billy", "Hunter Dennis", "Hunter Harry", "Huntress Melissa");

    /** Group the shard-trading hunters are filed under. */
    public static final String SHARD_HUNTER_GROUP = "Shard hunters";

    /**
     * Adds the shard-trading hunters to the tracker list once. Guarded by a flag rather than by
     * checking whether they're present, so removing one doesn't bring it back on the next load.
     */
    private static void seedShardHunters(ShinyConfig config) {
        if (config.seededShardHunters) {
            return;
        }
        config.seededShardHunters = true;

        for (String hunter : SHARD_HUNTERS) {
            boolean present = false;
            for (Tracker tracker : config.trackers) {
                if (hunter.equalsIgnoreCase(tracker.token == null ? "" : tracker.token.trim())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                Tracker tracker = new Tracker(hunter, SHARD_HUNTER_GROUP);
                tracker.color = "55FF7A";
                config.trackers.add(tracker);
            }
        }
        config.save();
        ShinyHunterClient.LOGGER.info("Added shard-trading hunters to the tracker list");
    }

    /**
     * Drops trackers whose token repeats, keeping the first of each. Matching is case-insensitive
     * and ignores surrounding space, since those all behave identically when matched.
     */
    static void dedupeTrackers(List<Tracker> trackers) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int before = trackers.size();
        trackers.removeIf(tracker -> {
            String token = tracker.token == null ? "" : tracker.token.trim().toUpperCase();
            // Blank rows are kept. They match nothing, so they're harmless, and collapsing them
            // silently ate half-filled rows on the next load — which reads as an invisible cap on
            // how many entries you can add.
            if (token.isEmpty()) {
                return false;
            }
            return !seen.add(token);
        });
        if (trackers.size() != before) {
            ShinyHunterClient.LOGGER.info("Removed {} duplicate tracker(s)", before - trackers.size());
        }
    }

    // Deliberately NOT called from save(): the config screen saves on every keystroke, so a token
    // being cleared for retyping would briefly collide with another and have its row deleted from
    // under the cursor. Load time is the only safe moment.
    public void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't write {}", path, e);
        }
    }
}
