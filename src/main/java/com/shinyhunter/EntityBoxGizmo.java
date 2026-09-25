package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.CuboidGizmo;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A box that follows an entity smoothly.
 *
 * <p>Every built-in gizmo stores a fixed {@code AABB} captured when it was created, so a box emitted
 * once per tick sits at the position the entity held at that tick and jumps on the next one — the
 * mob visibly slides out of its own outline between ticks.
 *
 * <p>{@link Gizmo#emit} is called <i>every frame</i> during level rendering, not once per tick, so
 * storing the entity id instead of a position and resolving the box inside {@code emit} lets it be
 * recomputed at frame rate. Position is interpolated with the current partial tick, exactly as the
 * entity renderer interpolates the model, which keeps outline and mob locked together.
 *
 * <p>Drawing is delegated to a throwaway {@link CuboidGizmo} so the geometry is vanilla's own.
 *
 * @param inflate added on every side of the hitbox. Anything above zero also keeps the box's faces
 *                off the mob's own surface, which is what made shulker boxes z-fight.
 * @param minSize each side is grown to at least this, around the centre, so tiny hitboxes (a
 *                Gazer) are still easy to see — and a disguised Duplico's box pokes out of the
 *                block it's hiding in instead of being buried by it.
 */
public record EntityBoxGizmo(int entityId, double inflate, double minSize, GizmoStyle style) implements Gizmo {

    @Override
    public void emit(GizmoPrimitives primitives, float alpha) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Entity entity = client.level.getEntity(entityId);
        if (entity == null || entity.isRemoved()) {
            // Despawned since the box was queued — draw nothing rather than a stale outline.
            return;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // The bounding box tracks the entity's ticked position; shifting it by the difference
        // between the interpolated and ticked positions puts it exactly where the model is drawn.
        Vec3 offset = entity.getPosition(partialTick).subtract(entity.position());
        AABB box = atLeast(entity.getBoundingBox().move(offset).inflate(inflate), minSize);

        new CuboidGizmo(box, style, false).emit(primitives, alpha);
    }

    /** Grows each side of the box to at least {@code size}, keeping it centred. */
    static AABB atLeast(AABB box, double size) {
        double growX = Math.max(0, size - box.getXsize()) / 2;
        double growY = Math.max(0, size - box.getYsize()) / 2;
        double growZ = Math.max(0, size - box.getZsize()) / 2;
        return box.inflate(growX, growY, growZ);
    }
}
