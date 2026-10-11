package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Counts Safari tickets used since your last sparkling.
 *
 * <p>A ticket is used when the Safari Manager waves you through — either of his two lines:
 * <pre>
 * [NPC] Safari Manager: Looks good to me. Have fun out there!
 * [NPC] Safari Manager: I already saw your ticket, so you're free to go.
 * </pre>
 * Only the server's own NPC line counts: it has to <i>start</i> with "[NPC] Safari Manager:",
 * which a party, guild or private message never does (those start with their channel), and only
 * system messages are read, never player chat. When anyone in the party catches a sparkling,
 * {@link SparklingHistory} takes the count, shows it, and resets it.
 */
public final class TicketCounter {

    private static final String MANAGER = "[NPC] Safari Manager: ";
    private static final String[] TICKET_LINES = {
            "Looks good to me. Have fun out there!",
            "I already saw your ticket, so you're free to go."};

    private TicketCounter() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
    }

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackTicketsSinceSparkling) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (!text.startsWith(MANAGER)) {
            return;
        }
        String said = text.substring(MANAGER.length()).trim();
        for (String line : TICKET_LINES) {
            if (said.equals(line)) {
                config.ticketsSinceSparkling++;
                config.save();
                RunHistory.onTicket();
                Achievements.onTicket();
                ShinyHunterClient.LOGGER.info("Ticket used — {} since last sparkling", config.ticketsSinceSparkling);
                return;
            }
        }
    }

    /**
     * Called when anyone in the party catches a sparkling — the count is the party's luck, not
     * just yours: says how many tickets it took, resets the count, and returns it for the history
     * entry. Null when the counter is switched off.
     */
    public static Integer takeForCatch(String player) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.trackTicketsSinceSparkling) {
            return null;
        }
        int tickets = config.ticketsSinceSparkling;
        config.ticketsSinceSparkling = 0;
        config.save();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §fTook §e" + tickets
                    + (tickets == 1 ? " §fticket" : " §ftickets") + " to find a §6§lSPARKLING§f critter"
                    + (player.equalsIgnoreCase(client.player.getName().getString()) ? "" : " §7(" + player + ")")));
        }
        return tickets;
    }
}
