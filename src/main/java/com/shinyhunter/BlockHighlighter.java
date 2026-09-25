package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outlines any block type on the tracked-blocks list, each in its own colour.
 *
 * <p>The counterpart to {@link EntityHighlighter} for blocks. Bee nests keep their own dedicated
 * highlighter because they carry the emptied-nest sharing, which none of these need.
 *
 * <p><b>Matching by name without paying for it.</b> Entries are substrings of a block's registry id,
 * so "ore" catches every ore. Testing that string against each block found would be far too slow, so
 * the id matching happens once per rescan against the block <i>registry</i> — about a thousand
 * entries — producing a block-to-entry map. The scan itself then only does hash lookups.
 *
 * <p>The search walks chunk sections and uses each one's palette
 * ({@link LevelChunkSection#maybeHas}) to skip whole 4096-block sections that can't contain a match,
 * the same trick the bee nest search uses.
 *
 * <p>Gizmo-based, so it must be driven from {@code END_CLIENT_TICK} — see {@link BeeNestHighlighter}.
 */
public final class BlockHighlighter {

    private static final int SCAN_INTERVAL_TICKS = 40;
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Sanity cap; a loose entry like "stone" would otherwise fill the screen. */
    private static final int MAX_HIGHLIGHTS = 512;

    private record Found(BlockPos pos, int color) {
    }

    private static final List<Found> FOUND = new ArrayList<>();

    private static int tickCounter;

    private BlockHighlighter() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        if (!config.highlightBlocks
                || client.level == null
                || client.player == null
                || config.blockTrackers == null
                || config.blockTrackers.isEmpty()
                || (config.onlyInSkyblock && !SkyblockSidebar.inSkyblock(client))) {
            FOUND.clear();
            tickCounter = SCAN_INTERVAL_TICKS;
            return;
        }

        if (++tickCounter >= SCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            rescan(client, config);
        }
        emit(config);
    }

    // ------------------------------------------------------------------ finding

    /** Resolves the configured id substrings to actual blocks, once per rescan. */
    private static Map<Block, Integer> resolveColors(ShinyConfig config) {
        List<ShinyConfig.BlockTracker> active = new ArrayList<>();
        for (ShinyConfig.BlockTracker tracker : config.blockTrackers) {
            if (tracker.enabled && tracker.blockId != null && !tracker.blockId.isBlank()) {
                active.add(tracker);
            }
        }

        Map<Block, Integer> colors = new HashMap<>();
        if (active.isEmpty()) {
            return colors;
        }

        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (ShinyConfig.BlockTracker tracker : active) {
                if (id.contains(tracker.blockId.trim().toLowerCase())) {
                    // First matching entry wins, so overlapping entries stay predictable.
                    colors.putIfAbsent(block, BeeNestHighlighter.parseColor(tracker.color, 0xFF66D9FF));
                    break;
                }
            }
        }
        return colors;
    }

    private static void rescan(Minecraft client, ShinyConfig config) {
        FOUND.clear();

        Map<Block, Integer> colors = resolveColors(config);
        if (colors.isEmpty()) {
            return;
        }

        ClientLevel level = client.level;
        BlockPos center = client.player.blockPosition();
        int radius = config.blockScanRadius;

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
                    if (section.hasOnlyAir()
                            || !section.maybeHas(state -> colors.containsKey(state.getBlock()))) {
                        continue;
                    }

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                Integer color = colors.get(section.getBlockState(x, y, z).getBlock());
                                if (color == null) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(
                                        (chunkX << 4) + x, sectionBottomY + y, (chunkZ << 4) + z);
                                if (center.distSqr(pos) <= radiusSq && FOUND.size() < MAX_HIGHLIGHTS) {
                                    FOUND.add(new Found(pos, color));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private static void emit(ShinyConfig config) {
        if (FOUND.isEmpty()) {
            return;
        }
        try {
            for (Found found : FOUND) {
                int fill = (found.color() & 0x00FFFFFF) | 0x33000000;
                Gizmos
                        .cuboid(found.pos(), GizmoStyle.strokeAndFill(found.color(), 2.0f, fill))
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't emit block highlights", e);
        }
    }

    public static int highlightedCount() {
        return FOUND.size();
    }
}
