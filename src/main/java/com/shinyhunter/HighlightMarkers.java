package com.shinyhunter;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A record of what the highlighters drew this tick, in world coordinates.
 *
 * <p><b>Why this exists.</b> Vanilla draws through-wall gizmos in a late debug pass
 * ({@code LevelRenderer.addLateDebugPass}). Shader packs replace the frame graph, and that pass
 * either doesn't run or is drawn over — which is why outlines stop showing through blocks the moment
 * Iris is on. Nothing the mod does inside the world render can reliably survive that, because the
 * shader owns the pipeline it happens in.
 *
 * <p>What does survive is the HUD, drawn after the world and untouched by shader packs. So every
 * highlight also records its position here, and {@link com.shinyhunter.gui.ShaderMarkerOverlay}
 * projects them onto the screen. The gizmos remain the primary, better-looking rendering; this is
 * the fallback for when a shader is eating them.
 *
 * <p>Filled fresh each tick, so a marker exists only while its highlighter is still drawing it and
 * nothing can leave a stale one behind.
 */
public final class HighlightMarkers {

    /**
     * @param pos   centre of the highlighted thing
     * @param color ARGB, matching the outline it belongs to
     * @param label short text, or empty for none
     */
    public record Marker(Vec3 pos, int color, String label, boolean fixed) {
    }

    private static final List<Marker> MARKERS = new ArrayList<>();

    private HighlightMarkers() {
    }

    /** Clears last tick's markers. Registered before the highlighters so they refill it. */
    public static void beginTick() {
        MARKERS.clear();
    }

    public static void add(Vec3 pos, int color, String label) {
        add(pos, color, label, false);
    }

    /**
     * @param fixed a marker for fixed scenery (a bee nest, a Snoozling wall) that may be shown
     *              through walls in either edition. The public edition's overlay draws only these.
     */
    public static void add(Vec3 pos, int color, String label, boolean fixed) {
        if (pos != null && MARKERS.size() < 256) {
            MARKERS.add(new Marker(pos, color, label == null ? "" : label, fixed));
        }
    }

    public static List<Marker> all() {
        return MARKERS;
    }
}
