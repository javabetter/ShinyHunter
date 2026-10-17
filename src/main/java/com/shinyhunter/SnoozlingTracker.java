package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts how many of the five Snoozling walls have been broken this run.
 *
 * <p>The walls sit at fixed coordinates in every Safari instance, so they're read straight out of
 * the world rather than tracked through chat: a wall still standing is cobbled deepslate or tuff,
 * and a broken one is air. Reading the block is the ground truth — it survives relogging, it's right
 * when a teammate breaks one out of sight, and it needs no message to have been seen.
 *
 * <p><b>Unloaded is not broken.</b> The walls are spread far enough apart that several are outside
 * the loaded chunks most of the time, and an unloaded chunk reads as air. Counting that as broken
 * would show 5/5 from the far side of the map. So each wall is only counted once its chunk is
 * actually loaded, and the panel says how many are still unseen rather than quietly guessing.
 */
public final class SnoozlingTracker {

    /** Only worth re-reading occasionally; block reads are cheap but this runs every tick. */
    private static final int RESCAN_INTERVAL_TICKS = 20;

    /** What one wall is currently known to be. */
    public enum State {
        /** Air in a loaded chunk: somebody has opened it. */
        BROKEN,
        /** Cobbled deepslate or tuff: still standing. */
        STANDING,
        /** Chunk not loaded, or an unexpected block — genuinely no information. */
        UNKNOWN
    }

    private static int tickCounter;

    /**
     * Each wall's state, in the order they're configured. Kept per wall rather than as a tally so
     * the checklist can say <em>which</em> ones are open, which is what tells you where to go.
     */
    private static State[] states = new State[0];

    private SnoozlingTracker() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackSnoozlingWalls
                || client.level == null
                || client.player == null
                || !SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            reset();
            return;
        }

        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan(client, config);
    }

    private static void rescan(Minecraft client, ShinyConfig config) {
        List<BlockPos> walls = walls(config);
        State[] next = new State[walls.size()];
        int previousBroken = broken();

        for (int i = 0; i < walls.size(); i++) {
            next[i] = stateOf(client, walls.get(i));
        }

        states = next;
        if (broken() != previousBroken) {
            Debug.log("snoozling walls:", checklist(), "-", broken() + "/" + walls.size(), "open");
        }
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
        String[] parts = entry.trim().replace(",", " ").split("\\s+");
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

    // ------------------------------------------------------------------ state

    public static int broken() {
        return count(State.BROKEN);
    }

    public static int unknown() {
        return count(State.UNKNOWN);
    }

    public static int standing() {
        return count(State.STANDING);
    }

    private static int count(State wanted) {
        int found = 0;
        for (State state : states) {
            if (state == wanted) {
                found++;
            }
        }
        return found;
    }

    public static int total() {
        return walls(ShinyConfig.get()).size();
    }

    /**
     * The walls as a left-to-right checklist in configured order — {@code "- | ✔ | - | - | -"}.
     * A wall whose chunk isn't loaded shows as {@code ?}, so an unread wall is never mistaken for
     * one still standing.
     */
    public static String checklist() {
        int total = total();
        if (total == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                out.append(" §7| ");
            }
            State state = i < states.length ? states[i] : State.UNKNOWN;
            out.append(switch (state) {
                case BROKEN -> "§a✔";
                case STANDING -> "§7-";
                case UNKNOWN -> "§8?";
            });
        }
        return out.toString();
    }

    public static void reset() {
        states = new State[0];
        tickCounter = RESCAN_INTERVAL_TICKS; // read immediately next time it's active
    }
}
