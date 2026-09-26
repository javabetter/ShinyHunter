package com.shinyhunter.gui;

import com.shinyhunter.HighlightMarkers;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Draws each highlight as a small on-screen marker, for when a shader pack is swallowing the
 * through-wall outlines.
 *
 * <p><b>Why this is the fallback and not a fix.</b> Vanilla's through-wall gizmos are drawn in a late
 * debug pass inside the world render. A shader pack replaces that pipeline wholesale, so the pass
 * either never runs or gets drawn over — and nothing done from inside the world render can reliably
 * outlast something that owns the pipeline. The HUD, by contrast, is drawn after the world and after
 * the shader has finished, so anything put here is visible no matter what is installed.
 *
 * <p>Positions are projected by hand rather than through the render matrices: taking the offset from
 * the camera, rotating it by the camera's yaw and pitch, and dividing by depth. That needs nothing
 * from the render pipeline, which is the whole point — the pipeline is what can't be trusted here.
 *
 * <p>Switched on automatically while a shader pack is in use ({@link com.shinyhunter.Shaders}),
 * since with no shader the real outlines are better in every way. Only fixed markers are drawn
 * here (see {@link HighlightMarkers.Marker#fixed}).
 */
public final class ShaderMarkerOverlay {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("shinyhunter", "shader_markers");

    /** Anything nearer than this is on top of you; projecting it produces wild coordinates. */
    private static final double MIN_DEPTH = 0.05;

    private ShaderMarkerOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(ID, (graphics, delta) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();

        if (client.player == null || client.options.hideGui || client.screen != null) {
            return;
        }
        // Screen-space markers ignore occlusion by nature, so only the fixed ones are drawn —
        // scenery at known coordinates that may show through walls anyway — and only while a
        // shader pack is actually eating the through-wall outlines.
        boolean wanted = com.shinyhunter.Shaders.active();
        if (!wanted || HighlightMarkers.all().isEmpty()) {
            return;
        }

        var camera = client.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        float yaw = (float) Math.toRadians(camera.yRot());
        float pitch = (float) Math.toRadians(camera.xRot());

        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        // The camera's own field of view rather than the raw option, so zoom and speed effects are
        // already accounted for and the markers don't drift off their targets.
        double fov = camera.getFov();
        if (fov <= 0.0) {
            return;
        }
        double focal = (screenHeight / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);

        for (HighlightMarkers.Marker marker : HighlightMarkers.all()) {
            if (!marker.fixed()) {
                continue;
            }
            Vec3 offset = marker.pos().subtract(eye);

            // Projected against Minecraft's own facing vectors rather than a generic rotation.
            // At yaw 0 the camera looks down +Z and its right hand points down -X, so the forward
            // and right vectors below are what the game actually uses; deriving them any other way
            // put everything "behind the camera" whenever you faced east or west.
            double sinYaw = Math.sin(yaw);
            double cosYaw = Math.cos(yaw);
            double flat = -offset.x * sinYaw + offset.z * cosYaw;   // along forward, ignoring pitch
            double horizontal = -offset.x * cosYaw - offset.z * sinYaw;  // along right
            double vertical = offset.y;

            // Then tilt by pitch in the forward/up plane. Pitch is positive looking down.
            double sinPitch = Math.sin(pitch);
            double cosPitch = Math.cos(pitch);
            double depth = flat * cosPitch - vertical * sinPitch;
            double up = vertical * cosPitch + flat * sinPitch;

            if (depth < MIN_DEPTH) {
                continue; // behind the camera
            }

            int screenX = (int) Math.round(screenWidth / 2.0 + horizontal * focal / depth);
            int screenY = (int) Math.round(screenHeight / 2.0 - up * focal / depth);
            if (screenX < 0 || screenX > screenWidth || screenY < 0 || screenY > screenHeight) {
                continue; // off screen; an edge indicator would be its own feature
            }

            draw(graphics, client.font, screenX, screenY, marker, offset.length());
        }
    }

    private static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y,
                             HighlightMarkers.Marker marker, double distance) {
        int colour = marker.color();
        // No marker box: the outline in the world already says where the thing is, and a second
        // square floating over it was just clutter. The text alone carries the name and range.
        int size = 0;

        String label = marker.label().isEmpty()
                ? Math.round(distance) + "m"
                : marker.label() + " " + Math.round(distance) + "m";
        graphics.text(font, Component.literal(label),
                x - font.width(label) / 2, y + size + 2, colour, true);
    }
}
