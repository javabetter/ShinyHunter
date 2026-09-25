package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends chat commands one at a time with a gap between them.
 *
 * <p>Several features now send two lines back to back ("No bird food left!" then "!r"). Firing both
 * on the same tick risks Hypixel dropping the second as spam, and there'd be no way to tell from the
 * client that it happened. Queueing with a spacing tick keeps them in order and far enough apart to
 * survive.
 */
public final class PartyChat {

    /**
     * Gap between consecutive messages. Comfortably clear of Hypixel's repeat-message threshold.
     *
     * <p>Wall-clock rather than a tick countdown, because the countdown version had a hole: it only
     * counted down while something was queued, so after any send the cooldown sat at full until the
     * next message arrived — and that message then waited the whole gap, however long it had been.
     * Every reply in a session after the first was delayed by it.
     */
    private static final long SPACING_MILLIS = 1_250L;

    private static final Deque<String> QUEUE = new ArrayDeque<>();

    /**
     * When the last message left this client — from the mod <em>or</em> typed by the player. The
     * server's rate limit doesn't care who composed it: an instant reply to "!missing" fired right
     * behind the player's own typed line trips "Woah, slow down" and is dropped, which is worse
     * than the wait it was meant to save. So the gap is measured from whichever went last.
     */
    private static long lastSentAt;

    private PartyChat() {
    }

    /** Hooks the player's own outgoing chat and commands so the gap counts from those too. */
    public static void register() {
        ClientSendMessageEvents.CHAT.register(message -> lastSentAt = System.currentTimeMillis());
        ClientSendMessageEvents.COMMAND.register(command -> lastSentAt = System.currentTimeMillis());
    }

    /**
     * Queues a message on the given channel command.
     *
     * @param channel chat command such as {@code pc}, with or without a leading slash
     */
    public static void send(String channel, String message) {
        String command = channel == null ? "pc" : channel.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            command = "pc";
        }
        if (containsCoordinates(message)) {
            keepPrivate(message);
            return;
        }

        String line = command + " " + message;

        // The spacing exists to separate bursts. When nothing has gone out recently there's nothing
        // to space from, so waiting for the tick handler only adds latency — a reply to "!missing"
        // should land as fast as a typed one. The cooldown is still set, so a second message right
        // behind this one queues as before.
        Minecraft client = Minecraft.getInstance();
        if (ShinyConfig.get().instantPartyChat && gapElapsed() && QUEUE.isEmpty()
                && client.player != null && client.getConnection() != null) {
            dispatch(client, line);
            return;
        }
        QUEUE.add(line);
    }

    private static boolean gapElapsed() {
        return System.currentTimeMillis() - lastSentAt >= SPACING_MILLIS;
    }

    /**
     * Sends one line now; the next has to wait the gap. The timestamp is set here as well as by
     * the send event, so this works the same whether or not the event fires for a mod-sent command.
     */
    private static void dispatch(Minecraft client, String line) {
        client.getConnection().sendCommand(line);
        lastSentAt = System.currentTimeMillis();
        ShinyHunterClient.LOGGER.info("Sent '/{}'", line);
    }

    /**
     * A ten-letter position code as produced by {@link CoordCodec}.
     *
     * <p>Upper case only, and deliberately case-sensitive. Several critter names are exactly ten
     * letters and happen to decode as valid positions — "Doomspiral", "Chuckwalla", "Cavernfish" —
     * so a case-insensitive check would withhold the {@code !missing} reply, which gives away no
     * location at all. Codes are always generated upper case, so this separates them cleanly.
     */
    private static final Pattern CODE = Pattern.compile("\\b[A-Z]{10}\\b");

    /**
     * A message that gives away a location, or anything else not for strangers: shown only to you,
     * never sent. The party still gets everything harmless — readiness, timers, bird food — so
     * coordinating with strangers keeps working while your finds stay yours.
     */
    public static void sendSensitive(String channel, String message) {
        keepPrivate(message);
    }

    /** Shows a withheld message to the player instead of the party. */
    private static void keepPrivate(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §r" + message));
        }
        ShinyHunterClient.LOGGER.info("Kept local: {}", message);
    }

    /**
     * True when the text carries a position code. A backstop for the explicit
     * {@link #sendSensitive} calls: anything that leaks a location is held back in a public party
     * even if whatever produced it forgot to say so.
     */
    private static boolean containsCoordinates(String message) {
        Matcher matcher = CODE.matcher(message);
        while (matcher.find()) {
            BlockPos decoded = CoordCodec.decode(matcher.group());
            if (decoded != null) {
                return true;
            }
        }
        return false;
    }

    public static void onClientTick(Minecraft client) {
        if (QUEUE.isEmpty()) {
            return;
        }
        if (client.player == null || client.getConnection() == null) {
            // Not connected — drop the backlog rather than firing it into the next server joined.
            QUEUE.clear();
            return;
        }
        if (!gapElapsed()) {
            return;
        }

        dispatch(client, QUEUE.poll());
    }

    /** Drops anything still waiting — used when leaving a world. */
    public static void clear() {
        QUEUE.clear();
        lastSentAt = 0;
    }
}
