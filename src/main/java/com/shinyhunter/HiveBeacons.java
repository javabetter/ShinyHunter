package com.shinyhunter;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draws the honeyhive markers as actual beacon beams, using the game's own beacon renderer rather
 * than a tall thin box pretending to be one.
 *
 * <p>This has to happen inside the level render, not from a client tick: the beam is submitted to
 * the frame's render-node collector with the pose translated to the hive, exactly as a beacon
 * block entity submits its own. {@link HoneyHives} decides which hives are lit and in what colour
 * and leaves the positions here; this class only draws them.
 */
public final class HiveBeacons {

    /** The beam's own texture and radii, so it matches a real beacon rather than approximating it. */
    private static final float BEAM_RADIUS_SCALE = 1.0f;

    /** Vanilla's animation cycle: the texture scrolls over forty ticks. */
    private static final int ANIMATION_TICKS = 40;

    /** position -> colour, refreshed each tick by {@link HoneyHives}. */
    private static final Map<Vec3, Integer> BEAMS = new LinkedHashMap<>();

    /**
     * Set if submitting a beam ever throws. This is the one piece of the mod that draws inside the
     * level render rather than through the gizmo API, so if a future version moves the beacon
     * renderer out from under it, the marker falls back to a drawn column instead of vanishing.
     */
    private static boolean broken;

    /** True once the real beam has failed and the fallback column should be drawn instead. */
    public static boolean broken() {
        return broken;
    }

    private HiveBeacons() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(HiveBeacons::collect);
    }

    /** Replaces the set of beams to draw. Called from the tick that works out which hives are full. */
    public static void set(Map<Vec3, Integer> beams) {
        BEAMS.clear();
        BEAMS.putAll(beams);
    }

    public static void clear() {
        BEAMS.clear();
    }

    private static void collect(LevelRenderContext context) {
        if (BEAMS.isEmpty() || broken) {
            return;
        }
        try {
            submit(context);
        } catch (Throwable e) {
            broken = true;
            BEAMS.clear();
            ShinyHunterClient.LOGGER.warn("Beacon beams unavailable — falling back to drawn columns", e);
        }
    }

    private static void submit(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        ShinyConfig config = ShinyConfig.get();
        int height = Math.clamp(config.honeyHiveBeamHeight, 1, BeaconRenderer.MAX_RENDER_Y);

        // The beam texture scrolls with the world clock, the same as a real beacon's.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float animation = Math.floorMod(client.level.getGameTime(), ANIMATION_TICKS) + partialTick;

        Vec3 camera = client.gameRenderer.getMainCamera().position();
        PoseStack poses = context.poseStack();

        for (Map.Entry<Vec3, Integer> beam : BEAMS.entrySet()) {
            Vec3 pos = beam.getKey();
            poses.pushPose();
            // The collector works in camera space, so the pose goes to the hive's own corner —
            // where a beacon block entity's pose would already be.
            poses.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);
            BeaconRenderer.submitBeaconBeam(poses, context.submitNodeCollector(),
                    BeaconRenderer.BEAM_LOCATION, BEAM_RADIUS_SCALE, animation, 0, height,
                    beam.getValue() & 0x00FFFFFF,
                    BeaconRenderer.SOLID_BEAM_RADIUS, BeaconRenderer.BEAM_GLOW_RADIUS);
            poses.popPose();
        }
    }
}
