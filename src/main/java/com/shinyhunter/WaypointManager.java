package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Labelled markers for coordinates the mod picks out of party chat.
 *
 * <p>Each waypoint draws a box on its block with its name and live distance floating above, visible
 * through terrain — the point is to find something you can't see yet.
 *
 * <p>Two things clear them. Walking within the configured radius removes that one, on the assumption
 * that arriving is what you wanted it for; and changing level — a lobby or server hop — drops them
 * all, since the coordinates belonged to that instance and mean nothing in the next one.
 *
 * <p>Gizmo-based, so it must be driven from {@code END_CLIENT_TICK} — see {@link BeeNestHighlighter}
 * for why that specific hook.
 */
public final class WaypointManager {

    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Sanity cap so a flood of chat can't fill the screen. */
    private static final int MAX_WAYPOINTS = 32;

    public record Waypoint(BlockPos pos, String label, int color) {
    }

    private static final List<Waypoint> WAYPOINTS = new ArrayList<>();

    private static ClientLevel lastLevel;

    private WaypointManager() {
    }

    // ------------------------------------------------------------------ managing

    /** Adds a waypoint, ignoring a duplicate of one already marked at that block. */
    public static void add(BlockPos pos, String label, int color) {
        if (pos == null || !ShinyConfig.get().waypointsEnabled) {
            return;
        }
        for (Waypoint waypoint : WAYPOINTS) {
            if (waypoint.pos().equals(pos)) {
                return;
            }
        }
        if (WAYPOINTS.size() >= MAX_WAYPOINTS) {
            WAYPOINTS.remove(0); // oldest goes first
        }
        WAYPOINTS.add(new Waypoint(pos.immutable(), label == null ? "Waypoint" : label, color));
        ShinyHunterClient.LOGGER.info("Waypoint added: {} at {}", label, pos);
    }

    /** Adds a waypoint in the configured default colour. */
    public static void add(BlockPos pos, String label) {
        add(pos, label, BeeNestHighlighter.parseColor(ShinyConfig.get().waypointColor, 0xFFFFDD55));
    }

    public static void clear() {
        WAYPOINTS.clear();
    }

    public static int count() {
        return WAYPOINTS.size();
    }

    public static List<Waypoint> all() {
        return WAYPOINTS;
    }

    // ------------------------------------------------------------------ tick

    public static void onClientTick(Minecraft client) {
        if (client.level != lastLevel) {
            // Left the lobby: these coordinates belong to the instance we just left.
            lastLevel = client.level;
            if (!WAYPOINTS.isEmpty()) {
                ShinyHunterClient.LOGGER.info("Cleared {} waypoint(s) on leaving the lobby", WAYPOINTS.size());
                WAYPOINTS.clear();
            }
            return;
        }

        ShinyConfig config = ShinyConfig.get();
        if (client.level == null || client.player == null || WAYPOINTS.isEmpty()) {
            return;
        }
        if (!config.waypointsEnabled) {
            WAYPOINTS.clear();
            return;
        }

        Vec3 player = client.player.position();
        double clearSq = config.waypointClearRadius * config.waypointClearRadius;
        WAYPOINTS.removeIf(waypoint -> {
            if (centreOf(waypoint.pos()).distanceToSqr(player) > clearSq) {
                return false;
            }
            ShinyHunterClient.LOGGER.info("Waypoint reached: {} at {}", waypoint.label(), waypoint.pos());
            return true;
        });

        emit(client);
    }

    private static void emit(Minecraft client) {
        try {
            for (Waypoint waypoint : WAYPOINTS) {
                int stroke = waypoint.color();
                int fill = (stroke & 0x00FFFFFF) | 0x33000000;

                Gizmos.cuboid(waypoint.pos(), GizmoStyle.strokeAndFill(stroke, 2.0f, fill))
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);

                // Distance is recomputed every tick so the label counts down as you approach.
                int distance = (int) Math.round(centreOf(waypoint.pos()).distanceTo(client.player.position()));
                Gizmos.billboardTextOverBlock(
                                waypoint.label() + "  " + distance + "m",
                                waypoint.pos(), 0, stroke, 0.8f)
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
            }
        } catch (IllegalStateException e) {
            // No gizmo collector on this thread — see BeeNestHighlighter for when that can happen.
            ShinyHunterClient.LOGGER.debug("Can't emit waypoints", e);
        }
    }

    private static Vec3 centreOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
