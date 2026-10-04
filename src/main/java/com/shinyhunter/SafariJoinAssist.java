package com.shinyhunter;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safari Join Assist: automates the two-player trick for getting a party into a fresh Safari.
 *
 * <p>The trick: player 1 (the "joiner") leaves the party, enters a Safari instance, gets invited
 * back by player 2 (the "inviter"), is handed party leader, and warps the party to their instance.
 * Each player sets their own role; then:
 * <ul>
 *   <li><b>Inviter</b>: when the joiner leaves → {@code /party invite <joiner>}; when the joiner
 *       joins → {@code /party transfer <joiner>}; when the joiner becomes leader → {@code !w} in
 *       party chat.</li>
 *   <li><b>Joiner</b>: on joining the inviter's party → {@code !pt} in party chat, then
 *       {@code !warp} — the latter only while in the Critter Safari.</li>
 * </ul>
 *
 * <p><b>The other player's name is optional.</b> Left blank, it's learned: the inviter takes
 * whoever just left the party as the joiner, and the joiner takes whoever's party they just
 * joined as the inviter. A typed name pins it to that one player instead.
 *
 * <p>A joiner who is party leader when they leave doesn't produce "has left the party" — Hypixel
 * says "The party was transferred to X because Y left" instead, so both count as leaving.
 *
 * <p>Actions wait the configured delay, then go through {@link PartyChat}'s queue so they're spaced
 * like any other line. Only Hypixel's own system lines trigger it, never party chat, so nobody can
 * set it off by typing.
 */
public final class SafariJoinAssist {

    public static final String ROLE_NONE = "none";
    public static final String ROLE_JOINER = "joiner";
    public static final String ROLE_INVITER = "inviter";

    private static final Pattern JOINED = Pattern.compile("^(.+?) joined the party\\.$");
    private static final Pattern WE_JOINED = Pattern.compile("^You have joined (.+?)'s party!$");
    private static final Pattern LEFT = Pattern.compile(
            "^(.+?) (?:has left the party|has been removed from the party|was removed from your party"
                    + " because they disconnected)\\.?$");
    /** The leader leaving: {@code The party was transferred to [MVP+] Me because [MVP+] John left} */
    private static final Pattern LEADER_LEFT = Pattern.compile(
            "^The party was transferred to .+? because (.+?) left\\.?$");
    /** {@code The party was transferred to [MVP+] John by [VIP] Me} */
    private static final Pattern TRANSFERRED = Pattern.compile("^The party was transferred to (.+?) by .+$");

    /** An action waiting out the configured delay. */
    private record Pending(long dueAt, String what, String channel, String line) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    /** The other player as learned from party messages, when no name is configured. */
    private static String learnedPartner;

    private SafariJoinAssist() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> drain());
    }

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        String role = role(config);
        if (role.equals(ROLE_NONE)) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || text.startsWith("[Shiny Hunter]") || HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        if (role.equals(ROLE_INVITER)) {
            String leaver = nameFrom(LEFT.matcher(text));
            if (leaver == null) {
                leaver = nameFrom(LEADER_LEFT.matcher(text));
            }
            if (leaver != null && !leaver.equalsIgnoreCase(self()) && accepts(config, leaver, true)) {
                schedule(config, "invite", "party", "invite " + leaver);
                return;
            }
            String joiner = nameFrom(JOINED.matcher(text));
            if (joiner != null && isPartner(config, joiner)) {
                schedule(config, "transfer", "party", "transfer " + joiner);
                return;
            }
            String leader = nameFrom(TRANSFERRED.matcher(text));
            if (leader != null && isPartner(config, leader)) {
                schedule(config, "warp request", "pc", "!w");
            }
        } else if (role.equals(ROLE_JOINER)) {
            String host = nameFrom(WE_JOINED.matcher(text));
            if (host != null && accepts(config, host, true)) {
                schedule(config, "leader request", "pc", "!pt");
                Minecraft client = Minecraft.getInstance();
                if (SkyblockSidebar.isAtOrWithin(client, config.huntLocation)) {
                    schedule(config, "warp", "pc", "!warp");
                }
            }
        }
    }

    /**
     * Whether this player is the other half of the trick. With a configured name only that player
     * is; with none, {@code learn} makes this player the partner from now on.
     */
    private static boolean accepts(ShinyConfig config, String name, boolean learn) {
        String configured = configuredPartner(config);
        if (!configured.isEmpty()) {
            return name.equalsIgnoreCase(configured);
        }
        if (learn) {
            learnedPartner = name;
            ShinyHunterClient.LOGGER.info("Safari Join Assist: partner is now {}", name);
        }
        return true;
    }

    /** The configured partner, or the learned one when none is configured. */
    private static boolean isPartner(ShinyConfig config, String name) {
        String configured = configuredPartner(config);
        String partner = configured.isEmpty() ? learnedPartner : configured;
        return partner != null && name.equalsIgnoreCase(partner);
    }

    private static String configuredPartner(ShinyConfig config) {
        return config.safariAssistPartner == null ? "" : config.safariAssistPartner.trim();
    }

    private static String nameFrom(Matcher matcher) {
        return matcher.matches() ? PartyDex.nameIn(matcher.group(1)) : null;
    }

    private static String self() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "" : client.player.getName().getString();
    }

    private static void schedule(ShinyConfig config, String what, String channel, String line) {
        long delay = Math.max(0, Math.round(config.safariAssistDelaySeconds * 1000));
        // Later actions keep their order behind earlier ones.
        long due = System.currentTimeMillis() + delay;
        if (!PENDING.isEmpty()) {
            due = Math.max(due, PENDING.get(PENDING.size() - 1).dueAt());
        }
        PENDING.add(new Pending(due, what, channel, line));
    }

    private static void drain() {
        long now = System.currentTimeMillis();
        while (!PENDING.isEmpty() && PENDING.get(0).dueAt() <= now) {
            Pending next = PENDING.remove(0);
            ShinyHunterClient.LOGGER.info("Safari Join Assist: {} -> /{} {}", next.what(), next.channel(), next.line());
            PartyChat.send(next.channel(), next.line());
        }
    }

    /** The configured role, normalised; anything unrecognised is "none". */
    static String role(ShinyConfig config) {
        String role = config.safariAssistRole == null ? "" : config.safariAssistRole.trim().toLowerCase();
        return switch (role) {
            case ROLE_JOINER, "1", "player1", "player 1", "joins" -> ROLE_JOINER;
            case ROLE_INVITER, "2", "player2", "player 2", "invites" -> ROLE_INVITER;
            default -> ROLE_NONE;
        };
    }
}
