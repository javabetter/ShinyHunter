package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safari Join Assist: automates the two-player trick for getting a party into a fresh Safari.
 *
 * <p>The trick: player 1 (the "joiner") leaves the party, enters a Safari instance, gets invited
 * back by player 2 (the "inviter"), is handed party leader, and warps the party to their instance.
 * Each player sets their own role and the other player's name; then:
 * <ul>
 *   <li><b>Inviter</b>: when the joiner leaves → {@code /party invite <joiner>}; when the joiner
 *       joins → {@code /party transfer <joiner>}; when the joiner becomes leader → {@code !w} in
 *       party chat.</li>
 *   <li><b>Joiner</b>: on joining the inviter's party → {@code !pt} in party chat, then
 *       {@code !warp} — the latter only while in the Critter Safari.</li>
 * </ul>
 * Everything goes through {@link PartyChat}'s queue, so the lines are spaced like any other.
 * Only Hypixel's own system lines trigger it, never party chat, so nobody can set it off by typing.
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
    /** {@code The party was transferred to [MVP+] John by [VIP] Me} */
    private static final Pattern TRANSFERRED = Pattern.compile("^The party was transferred to (.+?) by .+$");

    private SafariJoinAssist() {
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
        String role = role(config);
        String partner = config.safariAssistPartner == null ? "" : config.safariAssistPartner.trim();
        if (role.equals(ROLE_NONE) || partner.isEmpty()) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || text.startsWith("[Shiny Hunter]") || HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        if (role.equals(ROLE_INVITER)) {
            if (isPartner(LEFT.matcher(text), partner)) {
                act("invite", "party", "invite " + partner);
            } else if (isPartner(JOINED.matcher(text), partner)) {
                act("transfer", "party", "transfer " + partner);
            } else if (isPartner(TRANSFERRED.matcher(text), partner)) {
                act("warp request", "pc", "!w");
            }
        } else if (role.equals(ROLE_JOINER)) {
            if (isPartner(WE_JOINED.matcher(text), partner)) {
                act("leader request", "pc", "!pt");
                Minecraft client = Minecraft.getInstance();
                if (SkyblockSidebar.isAtOrWithin(client, config.huntLocation)) {
                    act("warp", "pc", "!warp");
                }
            }
        }
    }

    private static boolean isPartner(Matcher matcher, String partner) {
        if (!matcher.matches()) {
            return false;
        }
        String name = PartyDex.nameIn(matcher.group(1));
        return name != null && name.equalsIgnoreCase(partner);
    }

    private static void act(String what, String channel, String line) {
        ShinyHunterClient.LOGGER.info("Safari Join Assist: {} -> /{} {}", what, channel, line);
        PartyChat.send(channel, line);
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
