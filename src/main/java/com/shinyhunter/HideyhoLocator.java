package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers where Hideyho was last seen and hands the position to the party on request.
 *
 * <p>Sent as plain coordinates rather than the packed code the other call-outs use: this one is
 * asked for by a person mid-run and read by people, so it needs to be legible in chat without a
 * decoder on the other end.
 *
 * <p>Forgotten when the run ends — a position from the last instance would send the party to an
 * empty patch of ground.
 */
public final class HideyhoLocator {

    /** {@code !hidey} or {@code !hideyho}. */
    private static final Pattern REQUEST = Pattern.compile("^!hidey(?:ho)?$", Pattern.CASE_INSENSITIVE);

    /** How often the loaded entities are searched. It doesn't move, so this can be lazy. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** Gap between answers, so four people asking at once doesn't produce four bursts. */
    private static final long REPLY_COOLDOWN_MILLIS = 5_000L;

    private static BlockPos lastSeen;
    private static long lastReplyAt;
    private static int tickCounter;

    private HideyhoLocator() {
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

    // ------------------------------------------------------------------ finding

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.hideyhoCommand
                || client.level == null
                || client.player == null
                || config.hideyhoName.isBlank()) {
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        String needle = config.hideyhoName.trim().toUpperCase();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.isRemoved()) {
                continue;
            }
            if (EntityDataProbe.nameOf(entity).toUpperCase().contains(needle)) {
                BlockPos pos = entity.blockPosition();
                if (!pos.equals(lastSeen)) {
                    lastSeen = pos;
                    ShinyHunterClient.LOGGER.info("{} seen at {}", config.hideyhoName, pos);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.hideyhoCommand) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        // Party chat only, like the other party commands.
        if (!text.contains("Party >")) {
            return;
        }
        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!relayed.find() || !REQUEST.matcher(relayed.group(2).trim()).matches()) {
            return;
        }

        if (lastSeen == null) {
            return; // nothing to offer; someone who has seen it will answer
        }
        long now = System.currentTimeMillis();
        if (now - lastReplyAt < REPLY_COOLDOWN_MILLIS) {
            return;
        }
        lastReplyAt = now;
        share(config);
    }

    /**
     * Posts the last-seen position to the party. Shared by the chat trigger and the slash commands;
     * the chat trigger rate-limits itself first, the commands don't — a typed command is deliberate.
     *
     * @return false when there's no position to share yet
     */
    public static boolean share(ShinyConfig config) {
        if (lastSeen == null) {
            return false;
        }
        // "x: -662, y: 98, z: 213" — the labelled form, which reads unambiguously in chat and is
        // what the coordinate-parsing mods in the party already understand.
        PartyChat.sendSensitive(config.hideyhoChannel, "x: " + lastSeen.getX()
                + ", y: " + lastSeen.getY() + ", z: " + lastSeen.getZ());
        return true;
    }

    public static boolean hasSeen() {
        return lastSeen != null;
    }

    // ------------------------------------------------------------------ state

    /** Cleared at the end of a run — the position belongs to that instance. */
    public static void reset() {
        if (lastSeen != null) {
            ShinyHunterClient.LOGGER.info("Forgot the saved Hideyho position");
        }
        lastSeen = null;
        lastReplyAt = 0L;
    }
}
