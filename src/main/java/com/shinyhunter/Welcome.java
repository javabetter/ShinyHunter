package com.shinyhunter;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * A one-time hello on the first join after installing: where the settings are, and where the
 * commands are listed. Somebody who found the mod on their own has nothing else to go on.
 */
public final class Welcome {

    /** A short pause after joining so the line lands after the server's own join spam. */
    private static final int DELAY_TICKS = 60;

    private static int countdown = -1;
    private static boolean shownThisSession;

    private Welcome() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Welcome::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        if (shownThisSession || client.player == null || client.level == null) {
            countdown = -1;
            return;
        }
        ShinyConfig config = ShinyConfig.get();
        if (config.welcomeShown) {
            shownThisSession = true;
            return;
        }
        if (countdown < 0) {
            countdown = DELAY_TICKS;
            return;
        }
        if (--countdown > 0) {
            return;
        }
        shownThisSession = true;
        config.welcomeShown = true;
        config.save();

        client.player.sendSystemMessage(Component.literal(
                "§b§m                                        "));
        client.player.sendSystemMessage(Component.literal(
                "§b[Shiny Hunter] §fThanks for installing §b" + Edition.NAME + "§f!"));
        client.player.sendSystemMessage(Component.literal(
                "§7  §f/shiny §8- §7settings"));
        client.player.sendSystemMessage(Component.literal(
                "§7  §f/shiny help §8- §7every command and party command"));
        client.player.sendSystemMessage(Component.literal(
                "§7  §f/shiny hud §8- §7move the on-screen panels"));
        client.player.sendSystemMessage(Component.literal(
                "§7Party features post to party chat; each one has its own switch in settings."));
        client.player.sendSystemMessage(Component.literal(
                "§b§m                                        "));
    }
}
