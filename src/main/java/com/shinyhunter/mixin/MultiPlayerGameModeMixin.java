package com.shinyhunter.mixin;

import com.shinyhunter.SafariGuard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels the right-click on an entity before it reaches the server.
 *
 * <p><b>Why a mixin and not {@code UseEntityCallback}.</b> That event exists, and registering to it
 * compiles and runs — but Fabric only fires it from its server-side mixins. Its client mixin covers
 * {@code attackBlock}, {@code interactBlock}, {@code interactItem} and {@code attackEntity}, and
 * nothing else. Right-clicking an entity goes through {@link MultiPlayerGameMode#interact}, which
 * has no client-side Fabric hook, so a guard built on the event could never fire against a remote
 * server no matter what it checked. Cancelling here is the only place the packet can be stopped.
 *
 * <p>Returning FAIL rather than letting the method run means no {@code ServerboundInteractPacket} is
 * sent, so Hypixel never learns the click happened.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void shinyhunter$guardEntityInteract(Player player, Entity entity, EntityHitResult hit,
                                                 InteractionHand hand,
                                                 CallbackInfoReturnable<InteractionResult> info) {
        if (SafariGuard.shouldBlockEntity(entity)) {
            info.setReturnValue(InteractionResult.FAIL);
        }
    }
}
