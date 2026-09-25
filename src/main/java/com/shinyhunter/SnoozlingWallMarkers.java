package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints on the five Snoozling walls, drawn through terrain while in the Safari.
 *
 * <p>The walls are at fixed, publicly known spots in every instance, so marking them reveals nothing
 * the player couldn't look up — it just saves the walk around. A wall is dropped from the markers
 * once it reads as broken (air, in a loaded chunk); one whose chunk isn't loaded yet is still shown,
 * since not knowing is not the same as open.
 *
 * <p>Must be driven from {@code END_CLIENT_TICK} — see {@link BeeNestHighlighter} for why.
 */
public final class SnoozlingWallMarkers {

    private static final int GIZMO_LIFETIME_MILLIS = 120;
    private static final int RESCAN_INTERVAL_TICKS = 20;

    /** Walls still worth marking, with their 1-based number in the configured order. */
    private record Wall(int number, BlockPos pos) {
    }

    private static List<Wall> shown = List.of();
    private static int tickCounter = RESCAN_INTERVAL_TICKS;

    private SnoozlingWallMarkers() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.snoozlingWallWaypoints
                || client.level == null
                || client.player == null
                || !SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            shown = List.of();
            tickCounter = RESCAN_INTERVAL_TICKS;
            return;
        }

        if (++tickCounter >= RESCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            List<Wall> next = new ArrayList<>();
            List<BlockPos> walls = SnoozlingTracker.walls(config);
            for (int i = 0; i < walls.size(); i++) {
                if (SnoozlingTracker.stateOf(client, walls.get(i)) != SnoozlingTracker.State.BROKEN) {
                    next.add(new Wall(i + 1, walls.get(i)));
                }
            }
            shown = next;
        }
        emit(client, config);
    }

    private static void emit(Minecraft client, ShinyConfig config) {
        if (shown.isEmpty()) {
            return;
        }
        int stroke = BeeNestHighlighter.parseColor(config.snoozlingWallColor, 0xFFFF55FF);
        int fill = (stroke & 0x00FFFFFF) | 0x33000000;
        try {
            for (Wall wall : shown) {
                Vec3 centre = Vec3.atCenterOf(wall.pos());
                Gizmos.cuboid(wall.pos(), GizmoStyle.strokeAndFill(stroke, 2.0f, fill))
                        .persistForMillis(GIZMO_LIFETIME_MILLIS)
                        .setAlwaysOnTop();
                int distance = (int) Math.round(centre.distanceTo(client.player.position()));
                Gizmos.billboardTextOverBlock("Snoozling wall " + wall.number() + "  " + distance + "m",
                                wall.pos(), 0, stroke, 0.8f)
                        .persistForMillis(GIZMO_LIFETIME_MILLIS)
                        .setAlwaysOnTop();
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't emit Snoozling wall markers", e);
        }
    }
}
