package com.shinyhunter.mixin;

import com.shinyhunter.PaintingHider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.painting.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culls paintings in the Critter Safari: {@code shouldRender} is the dispatcher's per-entity
 * visibility test, so answering false here skips the painting exactly as if it were off-screen.
 * Render-only — no packet, no change to the entity itself.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void shinyhunter$hidePaintings(Entity entity, Frustum frustum, double camX, double camY,
            double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Painting && PaintingHider.active()) {
            cir.setReturnValue(false);
        }
    }
}
