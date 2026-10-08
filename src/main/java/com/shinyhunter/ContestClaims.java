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
 * <p>The count is read once from the game's contest menu — any open container whose items mention
 * "Miria's Contest" — and from then on goes up by one every time {@link ContestTracker} sees a
 * contest completed. Opening the menu again re-reads it, which also picks up claims. Until it has
 * been read once the counter is unknown, and the player is told (once per session) to open the menu.
 *
 * <p>Two menu layouts are handled, since either is plausible: one item per unclaimed contest
 * (each saying "click to claim"), or one item whose lore states the count ("12 unclaimed ...").
 * The lore of what matched is logged, so the reading can be checked against the real menu.
 *
 * <p>At or above the warning level it says so in chat and with a "Claim contests!" title: when a
 * contest completion pushes it there, and once per session on joining.
 */
public final class ContestClaims {

    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int JOIN_DELAY_TICKS = 120;

    /** A count in a lore line: "You have 12 unclaimed", "Unclaimed: 12", "12 contests". */
    private static final Pattern COUNT = Pattern.compile("(\\d[\\d,]*)");

    private static int tickCounter;
    private static int joinCountdown = -1;
    private static boolean toldThisSession;
    private static boolean loggedLore;

    private ContestClaims() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ContestClaims::onClientTick);
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

    private static void readMenu(AbstractContainerScreen<?> screen, ShinyConfig config) {
        boolean contestMenu = false;
        int claimable = 0;
        int stated = -1;
        List<String> sampleLore = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue; // the player's own inventory, shown under every chest menu
            }
            List<String> lore = loreOf(slot.getItem());
            String joined = String.join("\n", lore).toLowerCase();
            if (!joined.contains("miria's contest") && !joined.contains("miria’s contest")) {
                continue;
            }
            contestMenu = true;
            if (!joined.contains("click to claim")) {
                continue;
            }
            claimable++;
            if (sampleLore == null) {
                sampleLore = lore;
            }
            for (String line : lore) {
                String lower = line.toLowerCase();
                if (lower.contains("unclaimed")) {
                    Matcher m = COUNT.matcher(line);
                    if (m.find()) {
                        stated = Math.max(stated, Integer.parseInt(m.group(1).replace(",", "")));
                    }
                }
            }
        }
        if (!contestMenu) {
            return;
        }
        if (!loggedLore && sampleLore != null) {
            loggedLore = true;
            ShinyHunterClient.LOGGER.info("Contest menu: {} claimable item(s); first one's lore: {}",
                    claimable, sampleLore);
        }
        int count = stated >= 0 ? stated : claimable;
        if (count != config.unclaimedContests) {
            boolean first = config.unclaimedContests < 0;
            config.unclaimedContests = count;
            config.save();
            ShinyHunterClient.LOGGER.info("Unclaimed contests read from the menu: {}", count);
            if (first) {
                tell("§fFound §e" + count + " §funclaimed contest" + (count == 1 ? "" : "s")
                        + ". §7The counter keeps itself up to date from now on.");
            }
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
            tell("§fTo count your unclaimed Miria's Contests, open the §eMiria's Contest rewards menu§f once. "
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
