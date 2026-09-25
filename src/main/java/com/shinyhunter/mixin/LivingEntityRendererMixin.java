package com.shinyhunter.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import com.shinyhunter.TintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints an outlined mob's model toward its outline colour. {@code getModelTint} is the colour the
 * model is multiplied by (white normally), so multiplying our tint in keeps any tint the game
 * already applies. Render-only, and depth-tested like the model itself — nothing through walls.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void shinyhunter$tint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
        int tint = ((TintHolder) state).shinyhunter$getTint();
        if (tint != 0) {
            cir.setReturnValue(multiply(cir.getReturnValueI(), tint));
        }
    }

    private static int multiply(int a, int b) {
        int alpha = (a >>> 24) * (b >>> 24) / 255;
        int red = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int green = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int blue = (a & 0xFF) * (b & 0xFF) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}
