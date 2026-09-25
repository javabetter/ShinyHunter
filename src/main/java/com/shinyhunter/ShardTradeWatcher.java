package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spots a hunter NPC offering a shard trade, calls it out to the party, and tells anyone who can
 * actually take the trade.
 *
 * <p><b>Why two patterns rather than one.</b> The offer is split across separate chat lines and each
 * hunter words it differently — Dennis even stutters through his — so the shard and the cost are
 * captured independently, from whichever line each happens to appear on, and paired up afterwards.
 * A single pattern per NPC would break the moment Hypixel adds a hunter.
 *
 * <p>The call-out is compressed before sending: names only, with the position packed into ten
 * letters by {@link CoordCodec}, so it fits one readable party line. On receipt every client decodes
 * it and checks its own inventory — if you're carrying the cost item, you get a title, because
 * you're the one who can act on it.
 */
public final class ShardTradeWatcher {

    /** Any hunter NPC line: "[NPC] Hunter Billy: ...". */
    private static final Pattern NPC_LINE = Pattern.compile(
            "^\\[NPC]\\s*([^:]+):\\s*(.*)$");

    /** The shard being offered, across all four observed phrasings. */
    private static final Pattern[] SHARD_PATTERNS = {
            Pattern.compile("really cool (.+?) on the floor", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I've got a (.+?) you can h-h-have", Pattern.CASE_INSENSITIVE),
            Pattern.compile("use for a (.+?)\\?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Do you want this (.+?)\\?", Pattern.CASE_INSENSITIVE),
    };

    /** What the hunter wants for it. */
    private static final Pattern[] COST_PATTERNS = {
            Pattern.compile("in exchange for(?:,\\s*say,)?\\s+an?\\s+(.+?)[.!?]*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("give m-m-me an?\\s+(.+?)[.!?…]*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you give me an?\\s+(.+?)[.!?]*$", Pattern.CASE_INSENSITIVE),
    };

    /** Our own compressed call-out, for decoding on every client including the sender's. */
    private static final Pattern CALLOUT = Pattern.compile(
            "SHARD: (.+?) for (.+?) @ ([A-Za-z]{10})(?: \\((.+?)\\))?!", Pattern.CASE_INSENSITIVE);

    /** How long a half-collected offer stays open before it's abandoned. */
    private static final long DIALOGUE_WINDOW_MILLIS = 20_000L;

    private static final long RESEND_COOLDOWN_MILLIS = 60_000L;

    private static String pendingNpc;
    private static String pendingShard;
    private static String pendingCost;
    private static long pendingSince;

    private static String lastSentKey;
    private static long lastSentAt;

    private ShardTradeWatcher() {
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
        ShinyConfig config = ShinyConfig.get();
        if (!config.announceShardTrades) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }

        // Someone's call-out (possibly our own echo) — decode and see if we can take it.
        Matcher callout = CALLOUT.matcher(text);
        if (callout.find()) {
            onCallout(callout.group(1), callout.group(2), callout.group(3));
            return;
        }

        Matcher npc = NPC_LINE.matcher(text);
        if (!npc.matches()) {
            return;
        }
        collect(npc.group(1).trim(), npc.group(2).trim());
    }

    // ------------------------------------------------------------------ collecting

    private static void collect(String npcName, String line) {
        long now = System.currentTimeMillis();

        // A different hunter, or a stale conversation, starts the collection over.
        if (!npcName.equalsIgnoreCase(pendingNpc) || now - pendingSince > DIALOGUE_WINDOW_MILLIS) {
            pendingNpc = npcName;
            pendingShard = null;
            pendingCost = null;
        }
        pendingSince = now;

        if (pendingShard == null) {
            pendingShard = firstGroup(SHARD_PATTERNS, line);
        }
        if (pendingCost == null) {
            pendingCost = firstGroup(COST_PATTERNS, line);
        }

        if (pendingShard != null && pendingCost != null) {
            announce(pendingNpc, pendingShard, pendingCost);
            pendingShard = null;
            pendingCost = null;
            pendingNpc = null;
        }
    }

    private static String firstGroup(Pattern[] patterns, String line) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                // Trim trailing clause punctuation the patterns can pick up on longer sentences.
                value = value.replaceAll("[,.!?…]+$", "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ announcing

    private static void announce(String npcName, String shard, String cost) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        String key = shard + "|" + cost;
        long now = System.currentTimeMillis();
        if (key.equals(lastSentKey) && now - lastSentAt < RESEND_COOLDOWN_MILLIS) {
            return;
        }
        lastSentKey = key;
        lastSentAt = now;

        // The NPC's own position, not ours. Dialogue finishes wherever the player happens to be
        // standing — often several blocks away, and after walking on — so using the player's
        // position produced a waypoint that didn't point at the hunter.
        BlockPos pos = locateNpc(client, npcName);
        String code = CoordCodec.encode(pos);
        if (code == null) {
            return;
        }

        // Coordinates alone identify the hunter; the biome was redundant noise in party chat.
        String body = "SHARD: " + shard + " for " + cost + " @ " + code + "!";

        ShinyConfig config = ShinyConfig.get();
        // Carries a position code, so a public party keeps it to us.
        PartyChat.sendSensitive(config.shardTradeChannel, body);
        ShinyHunterClient.LOGGER.info("Shard trade from {}: {} for {} at {}", npcName, shard, cost, pos);
    }

    /**
     * The nearest entity whose nameplate carries the NPC's name, falling back to the player's own
     * position when the hunter isn't among the loaded entities.
     */
    private static BlockPos locateNpc(Minecraft client, String npcName) {
        if (client.level == null || npcName == null || npcName.isBlank()) {
            return client.player.blockPosition();
        }

        String needle = npcName.trim().toUpperCase();
        Entity closest = null;
        double closestSq = Double.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }
            if (!EntityDataProbe.nameOf(entity).toUpperCase().contains(needle)) {
                continue;
            }
            double distanceSq = client.player.distanceToSqr(entity);
            if (distanceSq < closestSq) {
                closestSq = distanceSq;
                closest = entity;
            }
        }

        if (closest == null) {
            ShinyHunterClient.LOGGER.info("Couldn't locate {} — using player position", npcName);
            return client.player.blockPosition();
        }
        return closest.blockPosition();
    }

    // ------------------------------------------------------------------ receiving

    /** A trade seen this run, for the HUD. */
    public record Offer(String shard, String cost, BlockPos pos) {
    }

    private static final List<Offer> OFFERS = new ArrayList<>();

    /** Trades called out this run, oldest first. */
    public static List<Offer> offers() {
        return OFFERS;
    }

    public static void clearOffers() {
        OFFERS.clear();
    }

    /** Titles the trade for anyone actually carrying the cost item. */
    private static void onCallout(String shard, String cost, String code) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        BlockPos located = CoordCodec.decode(code);
        // Recorded for the HUD whoever called it out — the whole party wants the list, not just
        // whoever can afford it.
        boolean known = false;
        for (Offer offer : OFFERS) {
            if (offer.shard().equalsIgnoreCase(shard) && offer.cost().equalsIgnoreCase(cost)) {
                known = true;
                break;
            }
        }
        if (!known) {
            OFFERS.add(new Offer(shard, cost, located));
            // Mark the hunter so it can be walked to; the waypoint clears itself on arrival.
            WaypointManager.add(located, shard);
        }

        if (!ShinyConfig.get().titleWhenTradeAffordable) {
            return;
        }
        if (!hasItem(client, cost)) {
            return;
        }

        String where = located == null
                ? ""
                : " §8· " + located.getX() + ", " + located.getY() + ", " + located.getZ();

        client.gui.setTimes(5, 60, 15);
        client.gui.setSubtitle(Component.literal("§fyou have the §b" + cost));
        client.gui.setTitle(Component.literal("§a§lTRADE AVAILABLE"));

        client.player.sendSystemMessage(Component.literal(
                "§b[Shiny Hunter] §a§lTRADE §r§f" + shard + " §7for §f" + cost
                        + " §7— you have it" + where));

        if (ShinyConfig.get().playSound) {
            client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.4f);
        }
    }

    /** True when any inventory slot's name matches the cost text. */
    private static boolean hasItem(Minecraft client, String cost) {
        String needle = cost.trim().toUpperCase();
        if (needle.isEmpty()) {
            return false;
        }
        List<ItemStack> items = client.player.getInventory().getNonEquipmentItems();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            String name = EntityDataProbe.stripFormatting(stack.getHoverName().getString()).toUpperCase();
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
