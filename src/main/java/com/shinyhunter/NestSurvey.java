package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surveys the fixture bee nests on an island and writes what it finds to a file.
 *
 * <p>A survey nest is a bee nest with an oak fence under it and an oak trapdoor over it — the
 * build Hypixel uses for the placed nests, which tells them apart from anything decorative. Every
 * one seen is recorded once, at its exact position, and the file accumulates across sessions and
 * server hops: walk the map and the list fills in.
 *
 * <p>Nothing is drawn, announced or sent. This is groundwork for a later feature, so all it does
 * is read the blocks already loaded on the client and keep a list.
 */
public final class NestSurvey {

    /** A survey entry. Mutable and public so Gson round-trips it without adapters. */
    public static final class Nest {
        public int x;
        public int y;
        public int z;
        /** 0-5 as the block state reported it when last seen; 5 is a full nest. */
        public int honeyLevel;
        /** True when the nest was full the last time it was seen. */
        public boolean full;
        /** The zone name the sidebar showed, so a stray find elsewhere is obvious in the file. */
        public String zone;
        /** When it was last seen, as an ISO instant. */
        public String seen;

        /**
         * The API's id for this hive ({@code hive_14}), once a loot here has been matched to a
         * refill time an hour later. Null until then; see {@link HoneyHives}.
         */
        public String hive;

        String key() {
            return x + "," + y + "," + z;
        }
    }

    private static final class Data {
        String note = "Bee nests with an oak fence below and an oak trapdoor above, "
                + "collected by Shiny Hunter's /shiny nestsurvey.";
        Map<String, Nest> nests = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Slower than the highlighter's scan: this is a survey, not something being drawn. */
    private static final int SCAN_INTERVAL_TICKS = 40;

    /** How far around the player to look. The client only has what's loaded anyway. */
    private static final int SCAN_RADIUS = 96;

    private static Data data = new Data();
    private static boolean loaded;
    private static int tickCounter;
    private static int foundLastPass;

    /** The zone last reported as outside the survey area, so it's only said once. */
    private static String lastSkippedZone;

    /** Set once a pass has run this session, so the file exists even before the first find. */
    private static boolean scannedThisSession;

    /**
     * Whether the survey should run here. A blank area means anywhere in Skyblock. Otherwise the
     * zone row is checked first, then every other sidebar row — an island's name often only
     * appears on one of those once you're standing in a named sub-area of it.
     */
    private static boolean inSurveyArea(Minecraft client, ShinyConfig config) {
        if (config.surveyLocation == null || config.surveyLocation.isBlank()) {
            return true;
        }
        if (SkyblockSidebar.isAtOrWithin(client, config.surveyLocation)) {
            return true;
        }
        String needle = config.surveyLocation.trim().toUpperCase();
        for (String line : SkyblockSidebar.lines(client)) {
            if (line.toUpperCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private NestSurvey() {
    }

    // ------------------------------------------------------------------ scanning

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.surveyNests || client.level == null || client.player == null) {
            return;
        }
        load();
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        if (!SkyblockSidebar.inSkyblock(client)) {
            return;
        }
        String zone = SkyblockSidebar.zoneName(client);
        if (!inSurveyArea(client, config)) {
            // Said once per zone rather than every pass: a survey that silently does nothing is
            // indistinguishable from a broken one, which is exactly how this looked the first time.
            if (!zone.equals(lastSkippedZone)) {
                lastSkippedZone = zone;
                ShinyHunterClient.LOGGER.info("Nest survey idle — zone reads \"{}\", survey area is \"{}\"",
                        zone, config.surveyLocation);
            }
            return;
        }
        lastSkippedZone = null;
        scan(client, zone);
    }

    private static void scan(Minecraft client, String zone) {
        ClientLevel level = client.level;
        BlockPos center = client.player.blockPosition();

        int minChunkX = (center.getX() - SCAN_RADIUS) >> 4;
        int maxChunkX = (center.getX() + SCAN_RADIUS) >> 4;
        int minChunkZ = (center.getZ() - SCAN_RADIUS) >> 4;
        int maxChunkZ = (center.getZ() + SCAN_RADIUS) >> 4;
        long radiusSq = (long) SCAN_RADIUS * SCAN_RADIUS;

        int added = 0;
        int seen = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    int sectionBottomY = chunk.getMinY() + (index << 4);
                    // The palette check skips whole 4096-block sections with no nest in them.
                    if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.BEE_NEST))) {
                        continue;
                    }

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (!state.is(Blocks.BEE_NEST)) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(
                                        (chunkX << 4) + x, sectionBottomY + y, (chunkZ << 4) + z);
                                if (center.distSqr(pos) > radiusSq || !isSurveyNest(level, pos)) {
                                    continue;
                                }
                                seen++;
                                if (record(state, pos, zone)) {
                                    added++;
                                }
                            }
                        }
                    }
                }
            }
        }

        foundLastPass = seen;
        if (!scannedThisSession) {
            // Write the file on the first pass even with nothing in it, so "is this thing on?" is
            // answered by the file existing rather than by finding a nest first.
            scannedThisSession = true;
            save();
            ShinyHunterClient.LOGGER.info("Nest survey scanning — {} nest(s) known", data.nests.size());
        }
        if (added > 0) {
            save();
            ShinyHunterClient.LOGGER.info("Nest survey: {} new ({} in the file)", added, data.nests.size());
            Debug.log("nest survey:", added, "new,", data.nests.size(), "total");
        }
    }

    /**
     * The fixture build: oak fence below, oak trapdoor above. Fence state is ignored — the
     * connections differ from nest to nest — and so is the trapdoor's facing and open state.
     */
    private static boolean isSurveyNest(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(Blocks.OAK_FENCE)
                && level.getBlockState(pos.above()).is(Blocks.OAK_TRAPDOOR);
    }

    /** @return true when this position wasn't in the file yet */
    private static boolean record(BlockState state, BlockPos pos, String zone) {
        Nest nest = new Nest();
        nest.x = pos.getX();
        nest.y = pos.getY();
        nest.z = pos.getZ();
        nest.honeyLevel = state.hasProperty(BeehiveBlock.HONEY_LEVEL)
                ? state.getValue(BeehiveBlock.HONEY_LEVEL)
                : -1;
        nest.full = nest.honeyLevel >= BeehiveBlock.MAX_HONEY_LEVELS;
        nest.zone = zone;
        nest.seen = java.time.Instant.now().toString();

        Nest previous = data.nests.put(nest.key(), nest);
        return previous == null;
    }

    // ------------------------------------------------------------------ commands

    /** {@code /shiny nestsurvey}: how the survey is going, and where the file is. */
    public static List<String> status() {
        load();
        List<String> lines = new ArrayList<>();
        lines.add("§b§lNest survey §7— " + (ShinyConfig.get().surveyNests ? "§aon" : "§coff"));
        lines.add("§7Recorded: §f" + data.nests.size() + "§7 nest(s), §f" + full() + "§7 full when last seen");
        lines.add("§7Within " + SCAN_RADIUS + " blocks right now: §f" + foundLastPass);
        Minecraft client = Minecraft.getInstance();
        ShinyConfig config = ShinyConfig.get();
        String area = config.surveyLocation == null || config.surveyLocation.isBlank()
                ? "anywhere in Skyblock"
                : config.surveyLocation;
        boolean here = client.level != null && SkyblockSidebar.inSkyblock(client) && inSurveyArea(client, config);
        lines.add("§7Area: §f" + area + "§7 — here? " + (here ? "§ayes" : "§cno")
                + " §8(zone reads \"" + SkyblockSidebar.zoneName(client) + "\")");
        lines.add("§8" + path());
        return lines;
    }

    /** Every surveyed nest, keyed by "x,y,z". Live map: {@link HoneyHives} reads it each tick. */
    public static Map<String, Nest> nests() {
        load();
        return data.nests;
    }

    /** Records which API hive id a surveyed position turned out to be. */
    public static void bindHive(String key, String hive) {
        load();
        Nest nest = data.nests.get(key);
        if (nest == null || hive.equals(nest.hive)) {
            return;
        }
        nest.hive = hive;
        save();
    }

    /** True when this hive id is already bound to a different position. */
    public static boolean hiveTaken(String hive, String exceptKey) {
        load();
        for (Map.Entry<String, Nest> entry : data.nests.entrySet()) {
            if (hive.equals(entry.getValue().hive) && !entry.getKey().equals(exceptKey)) {
                return true;
            }
        }
        return false;
    }

    public static int count() {
        load();
        return data.nests.size();
    }

    private static int full() {
        int n = 0;
        for (Nest nest : data.nests.values()) {
            if (nest.full) {
                n++;
            }
        }
        return n;
    }

    /** Empties the file — for starting a fresh survey. */
    public static void clear() {
        load();
        data.nests.clear();
        save();
        ShinyHunterClient.LOGGER.info("Nest survey cleared");
    }

    static void tell(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(message));
        }
    }

    // ------------------------------------------------------------------ storage

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-nests.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = path();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.nests == null) {
                    read.nests = new LinkedHashMap<>();
                }
                read.nests.values().removeIf(java.util.Objects::isNull);
                data = read;
                ShinyHunterClient.LOGGER.info("Nest survey: {} nest(s) loaded", data.nests.size());
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read the nest survey — starting empty", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save the nest survey", e);
        }
    }
}
