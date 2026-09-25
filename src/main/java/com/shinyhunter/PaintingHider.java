package com.shinyhunter;

import net.minecraft.client.Minecraft;

/**
 * Stops paintings being drawn while in the Critter Safari.
 *
 * <p>The actual skip happens in {@code EntityRenderDispatcherMixin}, which runs for every entity on
 * every frame — far too often to read the sidebar each time. So the location is decided here, a few
 * times a second, and the mixin only reads a boolean.
 *
 * <p>Purely a rendering change: the paintings stay in the world (and stay hittable), nothing is
 * sent to the server.
 */
public final class PaintingHider {

    private static final int CHECK_INTERVAL_TICKS = 10;

    private static volatile boolean active;
    private static int countdown;

    private PaintingHider() {
    }

    /** Read by the render mixin. */
    public static boolean active() {
        return active;
    }

    public static void onClientTick(Minecraft client) {
        if (client.level == null || client.player == null) {
            active = false;
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = CHECK_INTERVAL_TICKS;

        ShinyConfig config = ShinyConfig.get();
        active = config.hidePaintingsInSafari
                && SkyblockSidebar.isAtOrWithin(client, config.huntLocation);
    }
}
