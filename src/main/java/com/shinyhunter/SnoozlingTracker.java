package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the five Snoozling walls are, and whether each is still standing.
 *
 * <p>The walls sit at fixed coordinates in every Safari instance, so they're read straight out of
 * the world rather than tracked through chat: a wall still standing is cobbled deepslate or tuff,
 * and a broken one is air.
 *
 * <p><b>Unloaded is not broken.</b> An unloaded chunk reads as air, so a wall is only called broken
 * once its chunk is actually loaded; until then its state is unknown.
 */
public final class SnoozlingTracker {

    /** What one wall is currently known to be. */
    public enum State {
        /** Air in a loaded chunk: somebody has opened it. */
        BROKEN,
        /** Cobbled deepslate or tuff: still standing. */
        STANDING,
        /** Chunk not loaded, or an unexpected block — genuinely no information. */
        UNKNOWN
    }

    private SnoozlingTracker() {
    }

    /** What the wall at this position is right now, read from the loaded world. */
    public static State stateOf(Minecraft client, BlockPos pos) {
        // getChunk(..., false) returns null rather than loading it, which is what makes the
        // "unknown" case distinguishable from air.
        LevelChunk chunk = client.level.getChunkSource()
                .getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk == null) {
            return State.UNKNOWN;
        }
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) {
            return State.BROKEN;
        }
        // Anything but a wall block is not a wall we recognise, so it says nothing either way.
        return isWallBlock(state) ? State.STANDING : State.UNKNOWN;
    }

    /** A wall block is cobbled deepslate or tuff; anything else is not a standing wall. */
    private static boolean isWallBlock(BlockState state) {
        return state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.TUFF);
    }

    /** The configured wall positions, ignoring any that are malformed. */
    public static List<BlockPos> walls(ShinyConfig config) {
        List<BlockPos> positions = new ArrayList<>();
        for (String entry : config.snoozlingWalls) {
            BlockPos pos = parse(entry);
            if (pos != null) {
                positions.add(pos);
            }
        }
        return positions;
    }

    /** "-70 40 68" or "-70, 40, 68" — commas optional, since both get typed. */
    static BlockPos parse(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.trim().replace(",", " ").split("\s+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
