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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps count of Miria's Contest rewards waiting to be claimed.
 *
 * <p>The count is read from Miria's menu ("You have N unclaimed award!") or her contest list, goes
 * up by one every time {@link ContestTracker} sees a contest completed, and drops back when Hypixel
 * says rewards were claimed. Until it has been read once the counter is unknown, and the player is
 * told (once per session) to open Miria's menu.
 *
 * <p>At or above the warning level it says so in chat and with a "Claim contests!" title: when a
 * contest completion pushes it there, and once per session on joining.
 */
public final class ContestClaims {

    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int JOIN_DELAY_TICKS = 120;

    /** Miria's chest: "You have 1 unclaimed award!" (or "no unclaimed awards"). */
    private static final Pattern AWARDS = Pattern.compile(
            "You have (\\d+|no) unclaimed awards?", Pattern.CASE_INSENSITIVE);

    /** Hypixel's claim line, e.g. "STARLYN CONTEST REWARDS CLAIMED" (server message only). */
    private static final Pattern CLAIMED = Pattern.compile("^[A-Z' ]*CONTEST REWARDS CLAIMED$");

    private static int tickCounter;
    private static int joinCountdown = -1;
    private static boolean toldThisSession;
    private static boolean loggedLore;

    private ContestClaims() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ContestClaims::onClientTick);
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChat(message);
            }
        });
    }

    private static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackUnclaimedContests || client.player == null) {
            joinCountdown = -1;
            return;
        }
        if (client.screen instanceof AbstractContainerScreen<?> screen && ++tickCounter >= SCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            readMenu(screen, config);
        }
        onJoinTick(client, config);
    }

    // ------------------------------------------------------------------ reading the menu

    /**
     * Reads the count from whichever Miria menu is open.
     * <ul>
     *   <li><b>Miria's main menu</b>: the "Claim your rewards!" chest says
     *       "You have N unclaimed award!" — the exact number, so it wins.</li>
     *   <li><b>The contest list</b> ("Click to view contests"): one item per contest, the
     *       unclaimed ones saying "click to claim". Counted directly; if the list has a next page,
     *       the count can only go up from it, since the other pages aren't visible.</li>
     * </ul>
     */
    private static void readMenu(AbstractContainerScreen<?> screen, ShinyConfig config) {
        int stated = -1;
        boolean listMenu = false;
        boolean morePages = false;
        int claimable = 0;
        List<String> sampleLore = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue; // the player's own inventory, shown under every chest menu
            }
            List<String> lore = loreOf(slot.getItem());
            if (lore.isEmpty()) {
                continue;
            }
            if (lore.get(0).trim().equalsIgnoreCase("Next Page")) {
                morePages = true;
            }
            for (String line : lore) {
                Matcher award = AWARDS.matcher(line);
                if (award.find()) {
                    stated = award.group(1).equalsIgnoreCase("no") ? 0 : Integer.parseInt(award.group(1));
                }
            }
            String joined = String.join("\n", lore).toLowerCase();
            if (joined.contains("miria's contest") || joined.contains("miria\u2019s contest")) {
                listMenu = true;
                if (joined.contains("click to claim")) {
                    claimable++;
                    if (sampleLore == null) {
                        sampleLore = lore;
                    }
                }
            }
        }

        int count;
        if (stated >= 0) {
            count = stated;
        } else if (listMenu) {
            count = morePages ? Math.max(claimable, config.unclaimedContests) : claimable;
            if (!loggedLore && sampleLore != null) {
                loggedLore = true;
                ShinyHunterClient.LOGGER.info("Contest list: {} claimable item(s); first one's lore: {}",
                        claimable, sampleLore);
            }
        } else {
            return;
        }
        set(config, count, stated >= 0 ? "Miria's menu" : "the contest list");
    }

    private static void set(ShinyConfig config, int count, String source) {
        if (count == config.unclaimedContests) {
            return;
        }
        boolean first = config.unclaimedContests < 0;
        config.unclaimedContests = count;
        config.save();
        ShinyHunterClient.LOGGER.info("Unclaimed contests read from {}: {}", source, count);
        if (first) {
            tell("§fFound §e" + count + " §funclaimed contest" + (count == 1 ? "" : "s")
                    + ". §7The counter keeps itself up to date from now on.");
        }
    }

    /**
     * "STARLYN CONTEST REWARDS CLAIMED" — Hypixel's line when rewards are claimed. Counts drop to
     * zero; if the contest list is still open with some left, the next read puts the rest back.
     */
    private static void onChat(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackUnclaimedContests) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (CLAIMED.matcher(text).matches() && config.unclaimedContests != 0) {
            config.unclaimedContests = 0;
            config.save();
            ShinyHunterClient.LOGGER.info("Contest rewards claimed — unclaimed count reset");
        }
    }

    private static List<String> loreOf(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return lines;
        }
        lines.add(EntityDataProbe.stripFormatting(stack.getHoverName().getString()));
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(EntityDataProbe.stripFormatting(line.getString()));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ counting up

    /** Called by {@link ContestTracker} when it sees a contest completed. */
    public static void onContestCompleted() {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackUnclaimedContests || config.unclaimedContests < 0) {
            return;
        }
        config.unclaimedContests++;
        config.save();
        ShinyHunterClient.LOGGER.info("Contest completed — {} unclaimed", config.unclaimedContests);
        if (config.unclaimedContests >= config.unclaimedContestWarnAt) {
            warn(config);
        }
    }

    private static void onJoinTick(Minecraft client, ShinyConfig config) {
        if (toldThisSession || client.level == null || !SkyblockSidebar.inSkyblock(client)) {
            return;
        }
        if (joinCountdown < 0) {
            joinCountdown = JOIN_DELAY_TICKS;
            return;
        }
        if (--joinCountdown > 0) {
            return;
        }
        toldThisSession = true;
        if (config.unclaimedContests < 0) {
            tell("§fTo count your unclaimed Miria's Contests, open §eMiria's menu§f once. "
                    + "§7After that the count keeps itself up to date.");
        } else if (config.unclaimedContests >= config.unclaimedContestWarnAt) {
            warn(config);
        }
    }

    private static void warn(ShinyConfig config) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        tell("§c§lYou have " + config.unclaimedContests + " unclaimed contests! §fClaim them in the contest menu.");
        client.gui.setTimes(5, 60, 15);
        client.gui.setSubtitle(Component.literal("§f" + config.unclaimedContests + " unclaimed"));
        client.gui.setTitle(Component.literal("§c§lClaim contests!"));
    }

    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §r" + message));
        }
    }
}
