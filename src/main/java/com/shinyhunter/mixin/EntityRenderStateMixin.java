package com.shinyhunter.mixin;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import com.shinyhunter.TintHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds the tint slot to every render state. 0 means "no tint". */
@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements TintHolder {

    @Unique
    private int shinyhunter$tint;

    @Override
    public int shinyhunter$getTint() {
        return shinyhunter$tint;
    }

    @Override
    public void shinyhunter$setTint(int tint) {
        shinyhunter$tint = tint;
    }
}
