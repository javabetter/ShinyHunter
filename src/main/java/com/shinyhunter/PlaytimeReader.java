package com.shinyhunter;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your all-time Safari playtime, read from Skyblock's playtime menu ({@code /playtime}, then click
 * the result): the "Safari" item says "Playtime: 2,251 minutes". That covers time from before this
 * mod recorded runs, so the hours achievements and stats use it. Read whenever the menu is open.
 */
public final class PlaytimeReader {

    private static final Pattern PLAYTIME = Pattern.compile("Playtime: ([\\d,]+) minutes?", Pattern.CASE_INSENSITIVE);
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static int tickCounter;

    private PlaytimeReader() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlaytimeReader::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen) || ++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !EntityDataProbe.stripFormatting(stack.getHoverName().getString()).trim().equals("Safari")) {
                continue;
            }
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) {
                continue;
            }
            for (Component line : lore.lines()) {
                Matcher m = PLAYTIME.matcher(EntityDataProbe.stripFormatting(line.getString()));
                if (m.find()) {
                    record(client, Long.parseLong(m.group(1).replace(",", "")));
                    return;
                }
            }
        }
    }

    private static void record(Minecraft client, long minutes) {
        ShinyConfig config = ShinyConfig.get();
        if (config.safariPlaytimeMinutes == minutes) {
            return;
        }
        boolean first = config.safariPlaytimeMinutes <= 0;
        config.safariPlaytimeMinutes = minutes;
        config.safariPlaytimeReadAt = System.currentTimeMillis();
        config.save();
        ShinyHunterClient.LOGGER.info("Safari playtime read: {} minutes", minutes);
        if (first && client.player != null) {
            client.gui.getChat().addClientSystemMessage(Component.literal(String.format(java.util.Locale.ROOT,
                    "§b[Shiny Hunter] §fSafari playtime saved: §e%.1f hours§7 — it counts toward your stats and achievements.",
                    minutes / 60.0)));
        }
        Achievements.recheckHistory();
    }

    /**
     * Your Safari time in milliseconds: the playtime menu's figure plus the runs since it was read,
     * or the saved runs alone if that's more (or the menu was never opened).
     */
    public static long safariMillis() {
        ShinyConfig config = ShinyConfig.get();
        long runs = 0;
        long runsSinceRead = 0;
        for (RunHistory.Run run : RunHistory.runs()) {
            runs += run.length;
            if (run.start > config.safariPlaytimeReadAt) {
                runsSinceRead += run.length;
            }
        }
        if (config.safariPlaytimeMinutes <= 0) {
            return runs;
        }
        return Math.max(runs, config.safariPlaytimeMinutes * 60_000L + runsSinceRead);
    }
}
