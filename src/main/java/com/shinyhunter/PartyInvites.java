package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts party invites by itself, with a chime.
 *
 * <p>Who from is a setting: {@code off}, {@code everyone}, {@code list} (only the names in the list)
 * or {@code friends} (friends, guild members and the list). Hypixel gives a mod no friend or guild
 * list to read, so those names are learned from chat as they turn up: "Friend > Name joined.",
 * "Guild > Name joined.", guild chat itself, "You are now friends with Name", and the output of
 * /friend list and /guild list whenever the player runs them (the mod never runs them itself; it
 * offers a clickable line instead).
 *
 * <p>Optionally it leaves your current party first ({@code /p leave}), and asks to be warped in once
 * you're in ({@code /pc !w}). Everything goes through {@link PartyChat}'s queue, so it's spaced like
 * every other message the mod sends.
 */
public final class PartyInvites {

    /** "[MVP++] Name has invited you to join their party!" (or "Leader's party!"), on one line or two. */
    private static final Pattern INVITE = Pattern.compile(
            "(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) has invited you to join (?:their|[^ ]+'s) party!");
    private static final Pattern JOINED = Pattern.compile("^You have joined (.+?)'s party!$");

    private static final Pattern FRIEND_STATUS = Pattern.compile("^Friend > ([A-Za-z0-9_]{1,16}) (?:joined|left)\\.$");
    private static final Pattern NEW_FRIEND = Pattern.compile(
            "^You are now friends with (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16})$");
    private static final Pattern GUILD_LINE = Pattern.compile(
            "^(?:Guild|Officer) > (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16})(?: \\[[^\\]]+\\])?(?:: | joined\\.| left\\.)");

    /** Leaving takes a moment to go through before an accept will. */
    private static final long LEAVE_GAP_MILLIS = 1_200L;
    /** How long after accepting to wait for "You have joined", for the warp request. */
    private static final long JOIN_WAIT_MILLIS = 15_000L;
    private static final long WARP_DELAY_MILLIS = 1_000L;

    private record Pending(long at, String channel, String line) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long warpWantedUntil;

    private PartyInvites() {
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
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        learn(config, text);
        if (listing != Listing.NONE) {
            return;
        }
        if (text.startsWith("Party >") || text.startsWith("Guild >") || text.startsWith("Officer >")
                || text.startsWith("From ") || text.startsWith("To ")) {
            return; // somebody quoting an invite isn't one
        }
        Matcher joined = JOINED.matcher(text);
        if (joined.matches() && System.currentTimeMillis() < warpWantedUntil) {
            warpWantedUntil = 0;
            schedule(WARP_DELAY_MILLIS, "pc", "!w");
            return;
        }
        Matcher invite = INVITE.matcher(text.replace('\n', ' '));
        if (!invite.find()) {
            return;
        }
        String inviter = invite.group(1);
        String mode = config.autoJoinMode == null ? "off" : config.autoJoinMode.trim().toLowerCase(Locale.ROOT);
        if (!allowed(config, mode, inviter)) {
            ShinyHunterClient.LOGGER.info("Party invite from {} — not auto-joining (mode {})", inviter, mode);
            return;
        }

        // The invite's own "Click here" runs the right accept, even when a member invited on the
        // leader's behalf; the name is the fallback.
        String accept = clickCommand(message);
        if (accept == null) {
            accept = "party accept " + inviter;
        }
        long delay = 0;
        if (config.autoJoinLeaveFirst) {
            schedule(0, "p", "leave");
            delay = LEAVE_GAP_MILLIS;
        }
        int space = accept.indexOf(' ');
        schedule(delay, accept.substring(0, space), accept.substring(space + 1));
        if (config.autoJoinAskWarp) {
            warpWantedUntil = System.currentTimeMillis() + delay + JOIN_WAIT_MILLIS;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            if (config.autoJoinChime) {
                client.player.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0f, 1.2f);
            }
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §aJoining " + inviter + "'s party"
                    + (config.autoJoinLeaveFirst ? " §7(leaving yours first)" : "") + "§a."));
        }
        ShinyHunterClient.LOGGER.info("Party invite from {} — auto-joining with '/{}'", inviter, accept);
    }

    static boolean allowed(ShinyConfig config, String mode, String inviter) {
        String name = inviter.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "everyone", "all" -> true;
            case "list" -> onList(config, name);
            case "friends", "friends/guild", "guild" -> onList(config, name) || contains(config.knownFriends, name)
                    || contains(config.knownGuild, name);
            default -> false;
        };
    }

    private static boolean onList(ShinyConfig config, String name) {
        if (config.autoJoinList == null) {
            return false;
        }
        for (String entry : config.autoJoinList.split("[,\\s]+")) {
            if (entry.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(List<String> names, String name) {
        return names != null && names.contains(name);
    }

    /** The command behind the invite's "Click here to join!", without its slash. */
    private static String clickCommand(Component message) {
        for (Component part : message.toFlatList()) {
            if (part.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run) {
                String command = run.command().trim();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.toLowerCase(Locale.ROOT).startsWith("party accept ") || command.toLowerCase(Locale.ROOT).startsWith("p accept ")) {
                    return command;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ friends and guild

    /** "<< Friends (Page 1 of 3) >>" opens a /friend list page; each line after names one friend. */
    private static final Pattern FRIEND_PAGE = Pattern.compile("Friends \\(Page \\d+ of \\d+\\)");
    private static final Pattern FRIEND_ROW = Pattern.compile("^(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) is ");
    /** One name in a /guild list rank row: "[MVP+] Name ●  [VIP] Other ●". */
    private static final Pattern GUILD_MEMBER = Pattern.compile("^(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16})$");

    private enum Listing { NONE, FRIENDS, GUILD }

    private static Listing listing = Listing.NONE;
    private static final List<String> guildRead = new ArrayList<>();
    private static boolean hintShown;

    /**
     * Reads /friend list and /guild list as they arrive, a line at a time (Hypixel sends each as
     * one message with line breaks, but this works if they come split too). A full /guild list
     * replaces the guild members known; friends pages only add, since each shows just eight.
     */
    private static boolean readListing(ShinyConfig config, String message) {
        boolean any = false;
        for (String raw : message.split("\n")) {
            String line = raw.trim();
            if (FRIEND_PAGE.matcher(line).find()) {
                listing = Listing.FRIENDS;
                any = true;
                continue;
            }
            if (line.startsWith("Guild Name:")) {
                listing = Listing.GUILD;
                guildRead.clear();
                any = true;
                continue;
            }
            if (listing == Listing.NONE) {
                continue;
            }
            any = true;
            if (line.startsWith("---") && line.replace("-", "").isEmpty()) {
                listing = Listing.NONE; // the closing rule
                continue;
            }
            if (listing == Listing.FRIENDS) {
                String name = group(FRIEND_ROW, line);
                if (name != null) {
                    remember(config, config.knownFriends, name);
                }
            } else if (line.startsWith("Total Members:")) {
                if (!guildRead.isEmpty()) {
                    config.knownGuild.clear();
                    config.knownGuild.addAll(guildRead);
                    config.save();
                    chat("§aLearned " + guildRead.size() + " guild members for auto-join.");
                }
            } else if (!line.startsWith("Online Members:") && !line.startsWith("--")) {
                for (String part : line.split("●")) {
                    String name = group(GUILD_MEMBER, part.trim());
                    if (name != null && !guildRead.contains(name.toLowerCase(Locale.ROOT))) {
                        guildRead.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return any;
    }

    /** A clickable "run /friend list · /guild list" line; the click is the player's own command. */
    public static Component learnHint() {
        return Component.literal("§b[Shiny Hunter] §7Teach auto-join your friends and guild: ")
                .append(runLink("/friend list"))
                .append(Component.literal(" §8· "))
                .append(runLink("/guild list"))
                .append(Component.literal(" §8(and /friend list 2, 3… for more pages)"));
    }

    private static Component runLink(String command) {
        return Component.literal("§e§n" + command).withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to run " + command))));
    }

    private static void chat(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] " + text));
        }
    }

    private static void learn(ShinyConfig config, String text) {
        if (readListing(config, text)) {
            return;
        }
        String friend = group(FRIEND_STATUS, text);
        if (friend == null) {
            friend = group(NEW_FRIEND, text);
        }
        if (friend != null) {
            remember(config, config.knownFriends, friend);
            return;
        }
        String member = group(GUILD_LINE, text);
        if (member != null) {
            remember(config, config.knownGuild, member);
        }
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void remember(ShinyConfig config, List<String> names, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (names != null && !names.contains(key)) {
            names.add(key);
            config.save();
        }
    }

    // ------------------------------------------------------------------ timing

    private static void schedule(long delay, String channel, String line) {
        PENDING.add(new Pending(System.currentTimeMillis() + delay, channel, line));
    }

    public static void onClientTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        // Once a session, in friends mode with nothing learned yet, say how to fill the lists.
        ShinyConfig config = ShinyConfig.get();
        if (!hintShown && client.player.tickCount > 100 && "friends".equalsIgnoreCase(String.valueOf(config.autoJoinMode).trim())
                && (config.knownFriends.isEmpty() || config.knownGuild.isEmpty())) {
            hintShown = true;
            client.player.sendSystemMessage(learnHint());
        }
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending next = it.next();
            if (now >= next.at()) {
                it.remove();
                PartyChat.send(next.channel(), next.line());
            }
        }
    }

    /** {@code /shiny invites}: the setting and the friends and guild members learned so far. */
    public static List<String> status() {
        ShinyConfig config = ShinyConfig.get();
        List<String> lines = new ArrayList<>();
        lines.add("§b§lAuto-join invites §7— mode §f" + config.autoJoinMode + "§7, chime " + onOff(config.autoJoinChime)
                + "§7, leave first " + onOff(config.autoJoinLeaveFirst) + "§7, ask to be warped " + onOff(config.autoJoinAskWarp));
        lines.add("§7List: §f" + (config.autoJoinList == null || config.autoJoinList.isBlank() ? "-" : config.autoJoinList));
        lines.add("§7Friends learned (" + config.knownFriends.size() + "): §f" + String.join(", ", config.knownFriends));
        lines.add("§7Guild members learned (" + config.knownGuild.size() + "): §f" + String.join(", ", config.knownGuild));
        return lines;
    }

    private static String onOff(boolean on) {
        return on ? "§aon" : "§coff";
    }
}
