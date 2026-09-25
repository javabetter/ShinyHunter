package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shares which bee nests have already been emptied, so a party doesn't re-walk each other's nests.
 *
 * <p>Clicking a nest announces it to party chat with the position packed into a ten-letter code (see
 * {@link CoordCodec}). Every client that sees the message — including the sender's own copy coming
 * back — decodes it and drops that nest from the highlight.
 *
 * <p>The emptied set is deliberately memory-only and cleared whenever the level instance changes,
 * which on Hypixel is exactly a server hop. Nests refill across a hop, so carrying the list over
 * would hide nests that are worth visiting again.
 */
public final class BeeNestTracker {

    /** Matches our own call-out, wherever it appears — direct, party echo, or relayed by someone else. */
    private static final Pattern EMPTIED = Pattern.compile(
            "Beehive emptied at ([A-Za-z]{10})!", Pattern.CASE_INSENSITIVE);

    /** Nests known to be emptied on this server. */
    private static final Set<BlockPos> EMPTIED_NESTS = new HashSet<>();

    /** Suppresses repeat announcements from clicking the same nest several times. */
    private static final Map<BlockPos, Long> RECENTLY_SENT = new HashMap<>();

    private static final long RESEND_COOLDOWN_MILLIS = 30_000L;

    private static ClientLevel lastLevel;

    private BeeNestTracker() {
    }

    public static void register() {
        // Both click types are watched. Which one empties a nest isn't something the client can
        // know — the server decides — and registering only the right-click meant nothing fired if
        // it's actually punched. Neither callback consumes the interaction.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (level.isClientSide() && player == client.player) {
                onUse(client, hit.getBlockPos(), "use");
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            Minecraft client = Minecraft.getInstance();
            if (level.isClientSide() && player == client.player) {
                onUse(client, pos, "attack");
            }
            return InteractionResult.PASS;
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChat(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> onChat(message));
    }

    // ------------------------------------------------------------------ clicking

    private static void onUse(Minecraft client, BlockPos pos, String via) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.announceBeeEmptied || client.level == null) {
            return;
        }
        if (!client.level.getBlockState(pos).is(Blocks.BEE_NEST)) {
            return;
        }
        // Only call out nests in the hunting grounds. Clicking one in the hub or on a private
        // island is nothing the party needs to hear about, and the coordinates would be
        // meaningless to them anyway. Receiving stays ungated so a call-out still lands if you
        // happen to read it from outside.
        if (!SkyblockSidebar.isAtLocation(client, config.beeNestLocation)) {
            ShinyHunterClient.LOGGER.info("Bee nest {} clicked outside {} — not announcing",
                    pos, config.beeNestLocation);
            return;
        }

        long now = System.currentTimeMillis();
        Long sentAt = RECENTLY_SENT.get(pos);
        if (sentAt != null && now - sentAt < RESEND_COOLDOWN_MILLIS) {
            ShinyHunterClient.LOGGER.info(
                    "Bee nest {} clicked ({}) but announced {}s ago — skipping",
                    pos, via, (now - sentAt) / 1000);
            return;
        }
        ShinyHunterClient.LOGGER.info("Bee nest {} clicked via {}", pos, via);
        RECENTLY_SENT.put(pos, now);

        String code = CoordCodec.encode(pos);
        if (code == null) {
            ShinyHunterClient.LOGGER.warn("Bee nest at {} is outside the encodable range", pos);
            return;
        }

        // Hide it locally straight away — if we're not in a party the message never comes back, and
        // the nest we just emptied should still stop being highlighted.
        BlockPos immutable = pos.immutable();
        EMPTIED_NESTS.add(immutable);
        BeeNestHighlighter.forget(immutable);

        // Shown only to you: an emptied nest is a location, and locations never go to party chat.
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §7Beehive emptied at §f"
                    + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        }
    }

    // ------------------------------------------------------------------ listening

    private static void onChat(Component message) {
        if (!ShinyConfig.get().announceBeeEmptied) {
            return;
        }
        Matcher matcher = EMPTIED.matcher(EntityDataProbe.stripFormatting(message.getString()));
        if (!matcher.find()) {
            return;
        }
        BlockPos pos = CoordCodec.decode(matcher.group(1));
        if (pos != null && EMPTIED_NESTS.add(pos)) {
            // Drop it now rather than leaving it lit until the next rescan.
            BeeNestHighlighter.forget(pos);
            ShinyHunterClient.LOGGER.info("Nest marked emptied from chat: {}", pos);
        }
    }

    // ------------------------------------------------------------------ state

    /** Clears the set when the level changes, which on Hypixel means a server hop. */
    public static void onClientTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            EMPTIED_NESTS.clear();
            RECENTLY_SENT.clear();
        }
    }

    public static boolean isEmptied(BlockPos pos) {
        return EMPTIED_NESTS.contains(pos);
    }

    public static int emptiedCount() {
        return EMPTIED_NESTS.size();
    }

    /** Brings back every hidden nest without waiting for a server hop. */
    public static void clearEmptied() {
        EMPTIED_NESTS.clear();
        RECENTLY_SENT.clear();
    }
}
