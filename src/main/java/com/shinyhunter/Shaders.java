package com.shinyhunter;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gizmos.GizmoProperties;

import java.lang.reflect.Method;

/**
 * Keeps the mod's highlights working under a shader pack (Iris).
 *
 * <p><b>What happens under Iris</b> — read from Iris's source (26.1 branch) and then checked by
 * booting the dev client with Iris + Euphoria Patches and screenshotting test boxes:
 * <ul>
 *   <li>Outline <i>lines</i> draw fine: Iris maps the lines pipeline to the pack's line program.</li>
 *   <li>The see-through <i>fill</i> didn't draw at all: Iris has no program for the game's debug
 *       pipelines ("Missing program minecraft:pipeline/debug_filled_box in override list" in the
 *       log). A thin line on a mob in daylight is easy to miss, so it looked like nothing was
 *       highlighted. {@link #registerWithIris} fixes that through Iris's own API.</li>
 *   <li>"Always on top" shapes go in the game's late debug pass, where Iris skips the depth clear
 *       — so nothing can be drawn through walls in the world while a pack is on. Fixed markers
 *       that are allowed through walls (bee nests, hives, Snoozling walls) are drawn on the HUD
 *       instead, by {@code ShaderMarkerOverlay}.</li>
 *   <li>Real beacon beams are drawn with the pack's own beacon program, which some packs render
 *       translucent and frozen, so the honeyhive beams switch to a drawn column.</li>
 * </ul>
 *
 * <p>Iris is reached through its public API ({@code net.irisshaders.iris.api.v0.IrisApi}) by
 * reflection, so it stays optional.
 */
public final class Shaders {

    private static Method isShaderPackInUse;
    private static Object irisApi;
    private static boolean looked;

    private Shaders() {
    }

    /**
     * Tells Iris which shader-pack program to draw the game's filled debug shapes with.
     *
     * <p>Iris maps every vanilla render pipeline to a pack program, but it has no entry for the
     * debug pipelines — its log says so: "Missing program minecraft:pipeline/debug_filled_box in
     * override list". Without one, the see-through fill of every outline simply doesn't appear
     * under a pack, leaving only the thin lines (lines are mapped). Iris's API has
     * {@code assignPipeline} for exactly this; {@code BASIC} is the pack program for plain
     * position-and-colour geometry, the same format these pipelines use. Called once at startup.
     */
    public static void registerWithIris() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            Object instance = api.getMethod("getInstance").invoke(null);
            Method assign = api.getMethod("assignPipeline",
                    com.mojang.blaze3d.pipeline.RenderPipeline.class, program);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object basic = Enum.valueOf((Class) program, "BASIC");
            for (var pipeline : new com.mojang.blaze3d.pipeline.RenderPipeline[] {
                    net.minecraft.client.renderer.RenderPipelines.DEBUG_FILLED_BOX,
                    net.minecraft.client.renderer.RenderPipelines.DEBUG_QUADS,
                    net.minecraft.client.renderer.RenderPipelines.DEBUG_TRIANGLE_FAN}) {
                try {
                    assign.invoke(instance, pipeline, basic);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // Already assigned — a later Iris has fixed it itself, which is fine.
                    ShinyHunterClient.LOGGER.info("Iris already has a program for {}", pipeline.getLocation());
                }
            }
            ShinyHunterClient.LOGGER.info("Gave Iris a program for the debug shape pipelines");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ShinyHunterClient.LOGGER.warn("Couldn't register the debug shape pipelines with Iris", e);
        }
    }

    /** True while a shader pack is actually in use (not merely Iris installed). */
    public static boolean active() {
        if (!looked) {
            looked = true;
            if (FabricLoader.getInstance().isModLoaded("iris")) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    isShaderPackInUse = api.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    ShinyHunterClient.LOGGER.warn("Couldn't reach the Iris API; treating shaders as off", e);
                }
            }
        }
        if (isShaderPackInUse == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUse.invoke(irisApi);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Sends a gizmo to the late, draw-on-top pass when it's allowed through walls. (Under a shader
     * pack that pass is depth-tested anyway — see the class notes — which is why fixed markers also
     * go to the HUD.)
     */
    public static void place(GizmoProperties properties, boolean throughWalls) {
        if (throughWalls) {
            properties.setAlwaysOnTop();
        }
    }
}
