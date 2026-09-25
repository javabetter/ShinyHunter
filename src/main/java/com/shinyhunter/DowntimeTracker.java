package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code !dt [reason]} in party chat: somebody wants a break after this run.
 *
 * <p>The request is banked while the party is still in the Safari and cashed in when the run ends.
 * At that point the requester's own client posts "X has requested downtime" to the party — only
 * theirs, so four clients don't say it four times — and every client holds the Safari Manager shut
 * until either the requester says {@code r} in party chat or somebody sneaks past it once. The
 * hold is a one-shot: a sneak-click clears it for good, not just for that click.
 *
 * <p>Said while already outside the Safari, the request takes effect immediately.
 */
public final class DowntimeTracker {

    private static final Pattern REQUEST = Pattern.compile("^!dt(?:\\s+(.+))?$", Pattern.CASE_INSENSITIVE);

    /** The all-clear: a bare {@code r} from someone who asked for downtime. */
    private static final Pattern RESUME = Pattern.compile("^r$", Pattern.CASE_INSENSITIVE);

    /** requester -> reason (may be empty), in the order they asked. */
    private static final Map<String, String> REQUESTS = new LinkedHashMap<>();

    /** Whether the hold on the manager is currently in force. */
    private static boolean holding;

    private DowntimeTracker() {
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

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        if (!ShinyConfig.get().downtimeCommand) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (!text.contains("Party >")) {
            return;
        }
        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!relayed.find()) {
            return;
        }
        String sender = HotspotAnnouncer.senderOf(relayed.group(1));
        String body = relayed.group(2).trim();
        if (sender == null) {
            return;
        }

        Matcher request = REQUEST.matcher(body);
        if (request.matches()) {
            String reason = request.group(1) == null ? "" : request.group(1).trim();
            REQUESTS.put(sender, reason);
            ShinyHunterClient.LOGGER.info("{} requested downtime{}", sender,
                    reason.isEmpty() ? "" : " (" + reason + ")");
            tell("§e" + sender + "§r wants downtime after this run"
                    + (reason.isEmpty() ? "." : ": §7" + reason));

            Minecraft client = Minecraft.getInstance();
            if (client.level != null && !SkyblockSidebar.isAtLocation(client, ShinyConfig.get().huntLocation)) {
                onLeftSafari(); // already out — there is no "after the run" to wait for
            }
            return;
        }

        if (holding && RESUME.matcher(body).matches() && removeRequester(sender)) {
            ShinyHunterClient.LOGGER.info("{} is back from downtime", sender);
            tell("§a" + sender + "§r is back" + (holding ? "; still waiting on §f"
                    + String.join(", ", REQUESTS.keySet()) : " — manager released."));
        }
    }

    /** Drops one requester; the hold ends when nobody is left. True if they were on the list. */
    private static boolean removeRequester(String sender) {
        String key = null;
        for (String requester : REQUESTS.keySet()) {
            if (requester.equalsIgnoreCase(sender)) {
                key = requester;
                break;
            }
        }
        if (key == null) {
            return false;
        }
        REQUESTS.remove(key);
        if (REQUESTS.isEmpty()) {
            holding = false;
        }
        return true;
    }

    // ------------------------------------------------------------------ run boundary

    /** Called when the player leaves the Safari: turns banked requests into a hold. */
    public static void onLeftSafari() {
        if (REQUESTS.isEmpty() || holding) {
            return;
        }
        holding = true;
        ShinyHunterClient.LOGGER.info("Downtime hold on — requested by {}", REQUESTS.keySet());

        Minecraft client = Minecraft.getInstance();
        String me = client.player == null ? "" : client.player.getName().getString();
        for (Map.Entry<String, String> request : REQUESTS.entrySet()) {
            // Only the requester's client speaks; everyone else's just holds the manager.
            if (!request.getKey().equalsIgnoreCase(me)) {
                continue;
            }
            String reason = request.getValue();
            PartyChat.send(ShinyConfig.get().downtimeChannel, me + " has requested downtime"
                    + (reason.isEmpty() ? "" : " for reason: " + reason));
        }
        tell("Safari Manager held for downtime — say §fr§r in party chat when ready (sneak-click to skip).");
    }

    // ------------------------------------------------------------------ guard

    public static boolean holding() {
        return holding;
    }

    /** Who's holding the manager, for the refusal message. */
    public static String holders() {
        return String.join(", ", REQUESTS.keySet());
    }

    /** The one-time bypass: a sneak-click on the manager ends the hold. */
    public static void clearHold(String why) {
        if (!holding && REQUESTS.isEmpty()) {
            return;
        }
        ShinyHunterClient.LOGGER.info("Downtime hold cleared ({})", why);
        holding = false;
        REQUESTS.clear();
    }

    public static void reset() {
        holding = false;
        REQUESTS.clear();
    }

    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §r" + message));
        }
    }
}
