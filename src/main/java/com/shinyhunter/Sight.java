package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the player could actually see. Shiny Hunter only reacts to a name that's on your screen or
 * a mob in plain view: an entity behind you, out of range, or underground on its way up is off
 * limits even though the server has sent it.
 */
public final class Sight {

    /** Beyond this the player couldn't make out a nametag anyway. */
    private static final double MAX_DISTANCE = 64.0;

    /** Entity id -> when the game last drew its nametag. Filled in by the render mixin. */
    private static final java.util.Map<Integer, Long> NAMETAG_DRAWN = new java.util.HashMap<>();

    /** A nametag drawn within this long ago counts as on screen now (a few frames of slack). */
    private static final long NAMETAG_FRESH_MILLIS = 500;

    private Sight() {
    }

    /**
     * Called from the entity renderer whenever the game fills in a nametag for this frame. The game
     * only does that for an entity inside the camera's view (not behind you), within nametag range
     * (64 blocks), whose name it would show — so this is exactly "the name is on your screen".
     */
    public static void markNametagDrawn(int entityId) {
        NAMETAG_DRAWN.put(entityId, System.currentTimeMillis());
    }

    /** True when this entity's nametag is on the player's screen right now. */
    public static boolean nametagOnScreen(Entity entity) {
        Long at = NAMETAG_DRAWN.get(entity.getId());
        return at != null && System.currentTimeMillis() - at < NAMETAG_FRESH_MILLIS;
    }

    /**
     * Whether something may be reacted to: its nametag is on your screen, or the mob itself is in
     * plain view. A name you can read yourself is fair game even through a wall — vanilla draws
     * nametags through walls — but a name that's behind you, too far off, or hidden is not.
     */
    public static boolean canDetect(Minecraft client, Entity entity) {
        if (NAMETAG_DRAWN.size() > 4096) {
            long cutoff = System.currentTimeMillis() - NAMETAG_FRESH_MILLIS;
            NAMETAG_DRAWN.values().removeIf(at -> at < cutoff);
        }
        return nametagOnScreen(entity) || canSee(client, entity);
    }

    /**
     * True when a straight line from the player's eyes to some part of the entity's box is clear of
     * blocks. The centre and the top of the box are both tried, so a mob peeking over a ledge still
     * counts; a mob fully behind a wall never does.
     */
    public static boolean canSee(Minecraft client, Entity entity) {
        if (client.player == null || client.level == null || entity == null) {
            return false;
        }
        if (client.player.distanceToSqr(entity) > MAX_DISTANCE * MAX_DISTANCE) {
            return false;
        }
        Vec3 eye = client.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        return clear(client, eye, box.getCenter())
                || clear(client, eye, new Vec3(box.getCenter().x, box.maxY, box.getCenter().z));
    }

    /**
     * True when this exact point — a label hovering over a mob, say — is in plain view. Doesn't
     * depend on the render pipeline, which is the point: a shader pack can leave the world's depth
     * out of the pass that draws labels, and then "depth-tested" text shows through everything.
     */
    public static boolean canSeePoint(Minecraft client, Vec3 point) {
        if (client.player == null || client.level == null) {
            return false;
        }
        Vec3 eye = client.gameRenderer.getMainCamera().position();
        if (eye.distanceToSqr(point) > MAX_DISTANCE * MAX_DISTANCE) {
            return false;
        }
        return clear(client, eye, point);
    }

    private static boolean clear(Minecraft client, Vec3 from, Vec3 to) {
        HitResult hit = client.level.clip(new ClipContext(
                from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }
}
