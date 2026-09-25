package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls out gem placements, so the party can see how far along the podiums are without asking.
 *
 * <p>The colour is captured rather than listed, so a fourth gem needs no change here.
 */
public final class GemWatcher {

    /** "You placed the Purple Gem on its podium!" */
    private static final Pattern PLACED = Pattern.compile(
            "You placed the\\s+(.+?)\\s+Gem on its podium!", Pattern.CASE_INSENSITIVE);

    private GemWatcher() {
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
        if (!config.announceGems) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        // Only the server's own line. A relayed copy is somebody else's call-out already made, and
        // echoing it would put the same placement in chat once per party member running the mod.
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        Matcher matcher = PLACED.matcher(text);
        if (matcher.find()) {
            PartyChat.send(config.gemChannel, matcher.group(1).trim() + " Gem placed!");
        }
    }
}
