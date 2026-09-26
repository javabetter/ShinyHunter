package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a light-blue box around every bee nest nearby, while the sidebar says we're in the
 * configured location.
 *
 * <p><b>How this draws without a rendering mixin.</b> 26.x has a first-class gizmo API
 * ({@link Gizmos}) for putting shapes in the world, but {@code Gizmos.addGizmo} throws unless a
 * collector is registered on the current thread. {@code Minecraft.runTick} opens one around its call
 * to {@code Minecraft.tick()}, and Fabric fires {@code END_CLIENT_TICK} at that method's return — so
 * a client-tick handler is inside the collector scope and can emit directly. Shapes are re-emitted
 * every tick with a lifetime slightly longer than a tick, which keeps them steady between ticks
 * without accumulating.
 *
 * <p><b>How the search stays cheap.</b> A 48-block radius is roughly 900k block positions, far too
 * many to test individually. Instead this walks chunk sections and asks each one's palette
 * ({@link LevelChunkSection#maybeHas}) whether it could contain a bee nest at all — sections that
 * can't are skipped whole, which is almost all of them.
 */
public final class BeeNestHighlighter {

    /** Re-locate nests every 2s; they don't move, and the player has to walk to reach new ones. */
    private static final int SCAN_INTERVAL_TICKS = 40;

    /**
     * Outlive one tick (50ms) so the highlight doesn't strobe between ticks, but expire quickly
     * enough that boxes vanish promptly when you leave the area or the feature is switched off.
     */
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Sanity cap, so a pathological area can't spawn thousands of boxes. */
    private static final int MAX_NESTS = 256;

    private static final List<BlockPos> NESTS = new ArrayList<>();

    /** Consecutive ticks the location must agree before highlighting starts. */
    private static final int REQUIRED_CONFIRMATIONS = 3;

    private static int tickCounter;
    private static int confirmations;
    private static boolean warnedAboutCollector;

    private BeeNestHighlighter() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        boolean here = config.highlightBeeNests
                && client.level != null
                && client.player != null
                && SkyblockSidebar.isAtLocation(client, config.beeNestLocation);

        if (!here) {
            confirmations = 0;
            NESTS.clear();
            tickCounter = SCAN_INTERVAL_TICKS; // rescan immediately on re-entering the area
            return;
        }

        // The sidebar lags a little behind an area change, so a single matching tick isn't proof
        // we're really here. Turning on needs consecutive agreement; turning off is immediate.
        if (confirmations < REQUIRED_CONFIRMATIONS) {
            confirmations++;
            return;
        }

        if (++tickCounter >= SCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            rescan(client, config);
        }
        emit(config);
    }

    // ------------------------------------------------------------------ finding

    private static void rescan(Minecraft client, ShinyConfig config) {
        NESTS.clear();

        ClientLevel level = client.level;
        BlockPos center = client.player.blockPosition();
        int radius = config.beeNestScanRadius;

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        int minY = center.getY() - radius;
        int maxY = center.getY() + radius;
        long radiusSq = (long) radius * radius;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    int sectionBottomY = chunk.getMinY() + (index << 4);

                    if (sectionBottomY + 15 < minY || sectionBottomY > maxY) {
                        continue;
                    }
                    // Palette check: skips the whole 4096-block section when a nest can't be inside.
                    if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.BEE_NEST))) {
                        continue;
                    }

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (!section.getBlockState(x, y, z).is(Blocks.BEE_NEST)) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(
                                        (chunkX << 4) + x, sectionBottomY + y, (chunkZ << 4) + z);
                                if (center.distSqr(pos) <= radiusSq
                                        && !BeeNestTracker.isEmptied(pos)
                                        && NESTS.size() < MAX_NESTS) {
                                    NESTS.add(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Once the run's Honeybug is in hand the nests are recoloured rather than hidden. They're still
     * worth emptying for what else they drop, so hiding them threw away the highlight at the moment
     * it stopped mattering for the unique — but the colour change says at a glance that the unique
     * is done and the rest is optional.
     */
    private static boolean honeybugDone(ShinyConfig config) {
        return config.trackCritters
                && config.beeNestStopCritter != null
                && !config.beeNestStopCritter.isBlank()
                && CritterTracker.isFound(CritterDex.canonical(config.beeNestStopCritter));
    }

    private static void emit(ShinyConfig config) {
        if (NESTS.isEmpty()) {
            return;
        }
        if (config.hideNestsWhenDone && honeybugDone(config)) {
            return; // the unique is in hand and the rest aren't wanted
        }

        int stroke = honeybugDone(config)
                ? parseColor(config.beeNestDoneColor, 0xFFFF4040)
                : parseColor(config.beeNestColor, 0xFF66D9FF);
        // Same hue as the outline at low alpha, so the box reads as a solid volume from a distance
        // without hiding the nest inside it.
        int fill = (stroke & 0x00FFFFFF) | 0x33000000;
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 2.0f, fill);

        try {
            for (BlockPos pos : NESTS) {
                // Nests are fixed scenery, so their outline may show through walls in either edition.
                HighlightMarkers.add(centreOf(pos), stroke, "Nest", config.beeNestThroughWalls);
                Shaders.place(Gizmos.cuboid(pos, style).persistForMillis(GIZMO_LIFETIME_MILLIS),
                        config.beeNestThroughWalls);
            }
        } catch (IllegalStateException e) {
            // Thrown when no gizmo collector is registered on this thread. That shouldn't happen from
            // a client tick, but if the pipeline ever changes, fail quietly rather than every tick.
            if (!warnedAboutCollector) {
                warnedAboutCollector = true;
                ShinyHunterClient.LOGGER.warn(
                        "Can't emit bee-nest highlights — no gizmo collector on the tick thread", e);
            }
        }
    }

    /** Parses {@code "66D9FF"} or {@code "#66D9FF"} (optionally with a leading alpha pair). */
    static int parseColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            long parsed = Long.parseLong(hex, 16);
            // Opaque unless the config explicitly supplied an alpha channel.
            return hex.length() <= 6 ? (int) (parsed | 0xFF000000L) : (int) parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static net.minecraft.world.phys.Vec3 centreOf(BlockPos pos) {
        return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** How many nests are currently highlighted — for the status/command output. */
    public static int highlightedCount() {
        return NESTS.size();
    }

    /** Drops one nest immediately rather than waiting out the rescan interval. */
    public static void forget(BlockPos pos) {
        NESTS.remove(pos);
    }
}
