package com.shinyhunter.mixin;

import com.shinyhunter.ParticleWatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes every particle the server sends, before the client spawns it.
 *
 * <p>Particles have no entity and leave no trace once spawned — the particle engine holds them as
 * anonymous sprites with no type you can query back. The packet is the only moment the type and
 * position are both known, so that's where they're recorded. Nothing is changed or cancelled, and
 * nothing is ever sent.
 *
 * <p><b>Injected after the thread hand-off, not at HEAD.</b> Packet handlers are first invoked on
 * the network thread; their opening call re-schedules the packet onto the main thread and throws
 * to abandon the network-thread run. A HEAD injection therefore executes on <em>both</em> threads,
 * and a first version did exactly that — two threads mutating one deque corrupted it, the
 * resulting exception escaped on the network thread, and the connection was dropped as a
 * protocol error. Injecting after {@code ensureRunningOnSameThread} means this runs once, on the
 * main thread, like the handler body itself.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(
            method = "handleParticleEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                            + "Lnet/minecraft/network/protocol/Packet;"
                            + "Lnet/minecraft/network/PacketListener;"
                            + "Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER))
    private void shinyhunter$recordParticle(ClientboundLevelParticlesPacket packet, CallbackInfo info) {
        ParticleWatcher.record(packet);
    }
}
