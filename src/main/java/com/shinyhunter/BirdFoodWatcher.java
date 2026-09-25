package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps the party posted on bird food.
 *
 * <p>Two call-outs: the first time you pick any up on a given server, and — when the feeder attracts
 * a bird — the moment you've run out.
 *
 * <p>"First time on this server" is tracked against the level instance, which on Hypixel changes on
 * every server hop, so hopping and picking more up announces again as it should.
 */
public final class BirdFoodWatcher {

    /** The three bird foods. Matched against item display names. */
    private static final List<String> BIRD_FOODS = List.of("Yogi Berry", "Bag of Seeds", "Wriggleworm");

    private static final Pattern ATTRACTED = Pattern.compile(
            "was attracted to the Birdfeeder", Pattern.CASE_INSENSITIVE);

    /** Inventory polling interval. Fast enough to feel immediate, cheap enough to ignore. */
    private static final int POLL_INTERVAL_TICKS = 10;

    private static ClientLevel lastLevel;
    private static boolean announcedThisServer;
    private static boolean hasFood;
    private static int tickCounter;

    private BirdFoodWatcher() {
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

    // ------------------------------------------------------------------ inventory

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        if (client.level != lastLevel) {
            // New server: the "first pickup" call-out is due again.
            lastLevel = client.level;
            announcedThisServer = false;
            hasFood = false;
        }
        if (client.level == null || client.player == null || !config.announceBirdFood) {
            return;
        }
        if (++tickCounter < POLL_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        boolean carrying = hasBirdFood(client);
        boolean gained = carrying && !hasFood;
        hasFood = carrying;
        if (!gained) {
            return;
        }

        if (!announcedThisServer) {
            announcedThisServer = true;
            PartyChat.send(config.birdFoodChannel, "I have bird food!");
        }
    }

    /** True when any of the three foods is in the player's inventory. */
    public static boolean hasBirdFood(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                continue;
            }
            String name = EntityDataProbe.stripFormatting(stack.getHoverName().getString());
            for (String food : BIRD_FOODS) {
                if (name.toUpperCase().contains(food.toUpperCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.announceBirdFood) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        // The server's own line only. A relayed copy in party chat carries a channel marker and
        // must not set every client in the party announcing at once.
        if (!ATTRACTED.matcher(text).find() || HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (hasBirdFood(client)) {
            return; // still stocked, nothing to say
        }

        PartyChat.send(config.birdFoodChannel, "No bird food left!");
    }

    public static void reset() {
        announcedThisServer = false;
        hasFood = false;
    }
}
