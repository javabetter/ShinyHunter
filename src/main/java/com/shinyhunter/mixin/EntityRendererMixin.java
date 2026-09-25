package com.shinyhunter.mixin;

import com.shinyhunter.EntityHighlighter;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import com.shinyhunter.TintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps the outline tint onto the render state while the entity is still at hand. States are
 * reused between entities, so it's written every time — including 0 for everything not outlined.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"))
    private void shinyhunter$stampTint(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        ((TintHolder) state).shinyhunter$setTint(EntityHighlighter.tintFor(entity.getId()));
    }
}
