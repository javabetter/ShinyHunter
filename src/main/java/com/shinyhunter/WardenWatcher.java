package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.HashMap;
import java.util.Map;

/**
 * Announces when the Safari Zone's warden miniboss becomes attackable.
 *
 * <p>A warden spends its emergence animation in a squat 1×1 hitbox and only grows to its full 1×2
 * once it's actually up and vulnerable. That resize is the cleanest signal available client-side —
 * it needs no dialogue parsing and no timing guesswork — so this simply watches each warden's box
 * height and fires when it crosses the threshold upward.
 *
 * <p>The transition is detected per entity id rather than globally, so several wardens emerging near
 * each other each get their own alert instead of the first one swallowing the rest.
 */
public final class WardenWatcher {

    /**
     * Halfway between the ~1-block emergence box and the ~2-block standing box, so neither jitter
     * nor an exact-value assumption can miss the crossing.
     */
    private static final double READY_HEIGHT = 1.5;

    /** entity id -> whether it was already at full height last tick. */
    private static final Map<Integer, Boolean> WAS_READY = new HashMap<>();

    private WardenWatcher() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        if (!config.announceWardenReady || client.level == null || client.player == null) {
            WAS_READY.clear();
            return;
        }

        double radiusSq = (double) config.wardenScanRadius * config.wardenScanRadius;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getType() != EntityType.WARDEN
                    || entity.isRemoved()
                    || client.player.distanceToSqr(entity) > radiusSq) {
                continue;
            }

            boolean ready = entity.getBoundingBox().getYsize() >= READY_HEIGHT;
            Boolean previously = WAS_READY.put(entity.getId(), ready);

            // Only the upward crossing counts. A warden already at full height when it comes into
            // range has no previous state and must not fire, or every approach would alert.
            if (previously != null && !previously && ready) {
                announce(client, entity);
            }
        }

        WAS_READY.keySet().removeIf(id -> client.level.getEntity(id) == null);
    }

    private static void announce(Minecraft client, Entity entity) {
        String name = EntityDataProbe.nameOf(entity).trim();
        int distance = Math.round(client.player.distanceTo(entity));

        client.gui.setTimes(5, 40, 10);
        client.gui.setSubtitle(Component.literal("§7" + (name.isEmpty() ? "Warden" : name)
                + " §8· §7" + distance + "m"));
        client.gui.setTitle(Component.literal("§c§lWARDEN READY"));

        client.player.sendSystemMessage(Component.literal(
                "§b[Shiny Hunter] §c§lWARDEN READY §r§7— " + (name.isEmpty() ? "Warden" : name)
                        + " is vulnerable (" + distance + "m)"));

        if (ShinyConfig.get().playSound) {
            client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.6f);
        }

        ShinyHunterClient.LOGGER.info("Warden ready: {} at {}m", name, distance);
    }
}
