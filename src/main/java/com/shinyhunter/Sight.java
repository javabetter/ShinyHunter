package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Line-of-sight tests. Shiny Hunter only reacts to what the player could actually see: an entity
 * behind terrain, or underground on its way up, is off limits even though the server has sent it.
 */
public final class Sight {

    /** Beyond this the player couldn't make out a nametag anyway. */
    private static final double MAX_DISTANCE = 64.0;

    private Sight() {
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
