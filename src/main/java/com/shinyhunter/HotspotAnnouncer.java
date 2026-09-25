package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches incoming chat for Hypixel's Hunting Hotspot announcement and relays it to party chat.
 *
 * <p><b>The loop hazard.</b> Whatever we send to party chat comes straight back to us as another
 * chat message, and it still contains the hotspot text — so a naive "match, then send" relays its
 * own echo forever. Three independent guards stop that, any one of which is sufficient:
 * <ol>
 *   <li>messages that look like a party/guild echo are ignored outright;</li>
 *   <li>the exact text we last sent is ignored for a few seconds afterwards;</li>
 *   <li>the same biome is never relayed twice inside the cooldown window.</li>
 * </ol>
 */
public final class HotspotAnnouncer {

    /**
     * Loose on purpose: Hypixel decorates the line with colour and bold codes (stripped before we
     * get here) and the biome name varies. The pieces either side of the biome are what's stable.
     */
    private static final Pattern HOTSPOT = Pattern.compile(
            "HOTSPOT!\\s*Your Hunting Hotspot is the\\s+(.+?)\\s+Biome!",
            Pattern.CASE_INSENSITIVE);

    /**
     * A relayed chat line: channel prefix, optional rank tags, sender, then the message. These are
     * never re-relayed (that's the echo loop), but they are the input the coordinator allocates
     * from — including our own message coming back, which is how we learn where the server ordered
     * it relative to everyone else's.
     */
    /** Shared with the other chat features, which need the same sender/body split. */
    static final Pattern RELAYED_PUBLIC = Pattern.compile(
            "(?:Party|Guild|Co-op|Officer)\\s*>\\s*(.*?):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RELAYED = Pattern.compile(
            "(?:Party|Guild|Co-op|Officer)\\s*>\\s*(.*?):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** A Minecraft account name, used to pull the sender out of a relayed line's prefix. */
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** Somebody announcing they were bumped onto a different hotspot. */
    private static final Pattern REASSIGNED = Pattern.compile(
            "Reassigned from (\\w+) to (\\w+)!", Pattern.CASE_INSENSITIVE);

    /** Channel markers. Anything carrying one is somebody's relay and must never be relayed again. */
    private static final String[] CHANNEL_MARKERS = {"Party >", "Guild >", "Co-op >", "Officer >"};

    private static String lastBiome;
    private static long lastRelayAt;
    private static String lastSentText;
    private static long lastSentAt;

    private HotspotAnnouncer() {
    }

    public static void register() {
        // Hypixel sends nearly everything as a system message, but register both so a channel change
        // on their side doesn't silently break this.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handle(message, overlay));
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, timestamp) -> handle(message, false));
    }

    private static void handle(Component message, boolean overlay) {
        if (overlay) {
            return; // action bar, not chat
        }

        ShinyConfig config = ShinyConfig.get();
        if (!config.announceHotspot) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        // Guard 1 — anything on a chat channel is somebody's relay, quite possibly our own. The
        // decision to not re-relay is made on the channel marker ALONE, deliberately: a strict
        // "parse the whole line or treat it as ours" rule meant any prefix we failed to parse —
        // an unexpected rank tag, a guild suffix — fell through and got re-broadcast as our own
        // hotspot, which is how one player's announcement became everyone else's.
        if (isRelayed(text)) {
            if (config.coordinateHotspots) {
                Matcher relayed = RELAYED.matcher(text);
                if (relayed.find()) {
                    String sender = senderOf(relayed.group(1));
                    String body = relayed.group(2);
                    Matcher claim = HOTSPOT.matcher(body);
                    Matcher move = REASSIGNED.matcher(body);
                    if (sender != null && move.find()) {
                        HotspotCoordinator.recordReassign(sender, move.group(2));
                    } else if (sender != null && claim.find()) {
                        HotspotCoordinator.recordClaim(sender, claim.group(1));
                    }
                }
            }
            return;
        }
        // Guard 2 — our own message coming back on a channel we didn't anticipate.
        if (lastSentText != null && now - lastSentAt < 10_000L && text.contains(lastSentText)) {
            return;
        }

        Matcher matcher = HOTSPOT.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String biome = matcher.group(1).trim();

        // Our own hotspot, straight from the server. Register intent now; the claim itself lands
        // when this comes back through party chat.
        if (config.coordinateHotspots) {
            HotspotCoordinator.noteOwnAnnouncement(biome);
        }

        // Guard 3 — the real loop breaker: the same biome never goes out twice in the window.
        if (biome.equalsIgnoreCase(lastBiome) && now - lastRelayAt < config.hotspotCooldownSeconds * 1000L) {
            ShinyHunterClient.LOGGER.info(
                    "Hotspot '{}' already relayed {}s ago, skipping", biome, (now - lastRelayAt) / 1000);
            return;
        }

        lastBiome = biome;
        lastRelayAt = now;
        relay(config, text, biome);
    }

    private static void relay(ShinyConfig config, String fullMessage, String biome) {
        String body = config.hotspotFormat
                .replace("{message}", fullMessage)
                .replace("{biome}", biome)
                .trim();
        if (body.isEmpty()) {
            return;
        }

        lastSentText = body;
        lastSentAt = System.currentTimeMillis();

        Minecraft client = Minecraft.getInstance();
        // Hop to the client thread: chat can arrive off it, and sending touches the connection.
        // The shared queue spaces it from anything else going out.
        client.execute(() -> {
            if (client.player == null || client.getConnection() == null) {
                return;
            }
            PartyChat.send(config.hotspotCommand, body);
            ShinyHunterClient.LOGGER.info("Relayed hotspot '{}'", biome);
        });
    }

    /** True when the line carries any chat-channel marker. */
    static boolean isRelayed(String text) {
        for (String marker : CHANNEL_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pulls the account name out of a relayed line's prefix — everything between the channel arrow
     * and the colon. Rank tags, guild tags and emblems all live in there too, so the last thing in
     * the prefix that looks like an account name wins; bracketed tags are dropped first so a rank
     * like {@code [MVP+]} can't be mistaken for the sender.
     */
    static String senderOf(String prefix) {
        String cleaned = prefix.replaceAll("\\[[^\\]]*\\]", " ").trim();
        String sender = null;
        Matcher matcher = ACCOUNT_NAME.matcher(cleaned);
        while (matcher.find()) {
            sender = matcher.group();
        }
        return sender;
    }

    /** Lets {@code /shinyhunter hotspot test} exercise the parse without waiting for Hypixel. */
    public static String parseBiome(String text) {
        Matcher matcher = HOTSPOT.matcher(EntityDataProbe.stripFormatting(text).trim());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** Clears the cooldown so the next matching message relays immediately. */
    public static void reset() {
        lastBiome = null;
        lastRelayAt = 0L;
        lastSentText = null;
        lastSentAt = 0L;
    }
}
