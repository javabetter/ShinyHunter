package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Tells the party when you've run out of Critter Capsules, so nobody waits on you to capture.
 *
 * <p>Triggered by the Safari Manager's own line rather than by counting capsules in the inventory —
 * the server says it plainly, and reading it needs no assumptions about item names or stack sizes.
 */
public final class CapsuleAnnouncer {

    private static final Pattern OUT_OF_CAPSULES = Pattern.compile(
            "used up your last Critter Capsule", Pattern.CASE_INSENSITIVE);

    /** One call-out per restock cycle; the NPC can repeat itself on every further attempt. */
    private static final long COOLDOWN_MILLIS = 120_000L;

    private static long lastSentAt;

    private CapsuleAnnouncer() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> handle(message));
    }

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.announceOutOfCapsules) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        // Only the NPC's own line counts. Someone else's relayed call-out carries a channel marker
        // and must not set us off announcing too.
        if (!OUT_OF_CAPSULES.matcher(text).find() || HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSentAt < COOLDOWN_MILLIS) {
            return;
        }
        lastSentAt = now;

        // Through the shared queue, like every other call-out, so it's spaced from whatever else
        // went out and can't be dropped by the server as a burst.
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> PartyChat.send(config.capsuleChannel, "I'm out of capsules!"));
    }
}
