package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles {@code !timer <length>} in party chat — e.g. {@code !timer 5m} — and calls out when it
 * runs down.
 *
 * <p>Only the player who set the timer runs it. Everyone's client sees the same message, so having
 * each of them time it independently would put four identical expiry call-outs in party chat.
 *
 * <p>Several timers can run at once; each announces with the length exactly as it was typed, so
 * "5m timer has expired!" is recognisably the one that was asked for.
 */
public final class TimerWatcher {

    private static final Pattern TIMER = Pattern.compile(
            "^!timer\\s+(\\d{1,5})\\s*([hms])$", Pattern.CASE_INSENSITIVE);

    private record Pending(String label, long expiresAt) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private TimerWatcher() {
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
        if (!ShinyConfig.get().timerCommand) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (!HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!relayed.find()) {
            return;
        }
        String sender = HotspotAnnouncer.senderOf(relayed.group(1));
        if (sender == null || !sender.equalsIgnoreCase(selfName())) {
            return; // somebody else's timer is theirs to run
        }

        Matcher timer = TIMER.matcher(relayed.group(2).trim());
        if (!timer.matches()) {
            return;
        }

        int amount = Integer.parseInt(timer.group(1));
        char unit = Character.toLowerCase(timer.group(2).charAt(0));
        String label = amount + String.valueOf(unit);
        long millis = switch (unit) {
            case 'h' -> amount * 3_600_000L;
            case 'm' -> amount * 60_000L;
            default -> amount * 1_000L;
        };

        PENDING.add(new Pending(label, System.currentTimeMillis() + millis));
        ShinyHunterClient.LOGGER.info("Timer set for {} ({}ms)", label, millis);
    }

    public static void onClientTick(Minecraft client) {
        if (PENDING.isEmpty()) {
            return;
        }
        if (client.level == null || client.player == null) {
            PENDING.clear();
            return;
        }

        long now = System.currentTimeMillis();
        ShinyConfig config = ShinyConfig.get();
        PENDING.removeIf(pending -> {
            if (now < pending.expiresAt()) {
                return false;
            }
            PartyChat.send(config.timerChannel, pending.label() + " timer has expired!");
            return true;
        });
    }

    private static String selfName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "" : client.player.getName().getString();
    }

    public static void reset() {
        PENDING.clear();
    }
}
