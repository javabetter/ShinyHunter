package com.shinyhunter;

import com.shinyhunter.api.HypixelApi;
import com.shinyhunter.api.PlayerDex;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up each party member's Sparkling Critterdex as they join, and once the party is full works
 * out which sparklings everyone already has — those can be skipped for the run.
 *
 * <p><b>Membership comes from Hypixel's party lines.</b> There's no party API on the client, so
 * the roster is rebuilt from the same messages a person reads: joins, leaves, kicks, disbands, the
 * "You'll be partying with" roll-call, and {@code /p list} output. It's the same approach as the
 * ready-up and hotspot features and it has the same limit — it only knows what it has seen.
 *
 * <p><b>Shared means everybody has it.</b> A sparkling only comes off the list when all members'
 * dexes contain it. Anyone still needing it keeps it on everyone's list, because that's the
 * teammate who'll be hunting it.
 *
 * <p>The skip set is cleared the moment the roster changes. A member leaving may have been the one
 * who had the sparkling, and a member joining certainly hasn't been checked — either way the
 * calculation is stale, and stale here means telling somebody to skip a critter they need.
 */
public final class PartyDex {

    /** Somebody else joining: {@code [MVP+] Name joined the party.} */
    private static final Pattern JOINED = Pattern.compile("^(.+?) joined the party\\.$");

    /** Us joining: {@code You have joined [MVP+] Name's party!} */
    private static final Pattern WE_JOINED = Pattern.compile("^You have joined (.+?)'s party!$");

    /** The roll-call on joining: {@code You'll be partying with: [MVP+] A, [VIP] B} */
    private static final Pattern PARTYING_WITH = Pattern.compile("^You'll be partying with: (.+)$");

    /** Every way one member goes away. */
    private static final Pattern LEFT = Pattern.compile(
            "^(.+?) (?:has left the party|has been removed from the party|was removed from your party"
                    + " because they disconnected|was kicked from the party by .+)\\.?$");

    /** {@code Kicked [MVP+] Name because they were offline.} */
    private static final Pattern KICKED_OFFLINE = Pattern.compile("^Kicked (.+?) because they were offline\\.$");

    /** Every way the whole party goes away for us. */
    private static final Pattern PARTY_OVER = Pattern.compile(
            "^(?:You left the party\\.|The party was disbanded.*|.+? has disbanded the party!"
                    + "|You have been kicked from the party by .+)$");

    /** {@code /p list} rows. */
    private static final Pattern LIST_ROW = Pattern.compile("^Party (?:Leader|Moderators|Members): (.+)$");
    private static final Pattern LIST_HEADER = Pattern.compile("^Party Members \\(\\d+\\)$");

    /** An account name, for pulling names out of rank-decorated text. */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** {@code !shiny Name} in party chat. */
    private static final Pattern SHINY_REQUEST = Pattern.compile(
            "^!shiny\\s+([A-Za-z0-9_]{1,16})$", Pattern.CASE_INSENSITIVE);

    /** {@code !shared} or {@code !shared all} in party chat. */
    private static final Pattern SHARED_REQUEST = Pattern.compile(
            "^!shared(?:\\s+(all))?$", Pattern.CASE_INSENSITIVE);

    /** Longest party line worth sending; Hypixel cuts longer ones off. */
    private static final int MAX_LINE_LENGTH = 240;

    /** A Safari party is at most this many; the automatic shared-sparklings run fires at it. */
    public static final int FULL_PARTY = 4;

    /** Members other than us, in join order. */
    private static final Set<String> MEMBERS = new LinkedHashSet<>();

    /**
     * How many people are in the party, us included, as far as the roster knows. 1 when no party
     * has been seen — which also means "nobody to wait for", so the guard and the ready tally
     * behave as solo until a join or {@code /p list} says otherwise.
     */
    public static int partySize() {
        return MEMBERS.size() + 1;
    }

    /** What we know about each member (and ourselves), by name. */
    private static final Map<String, PlayerDex> DEXES = new LinkedHashMap<>();

    /** Sparklings every member has, once calculated. Empty until then. */
    private static final Set<String> SHARED = new LinkedHashSet<>();

    /** Whether this roster has been calculated already, so a re-full doesn't repeat it. */
    private static boolean calculated;

    /**
     * Set by {@code /sparkling party} while it waits on lookups, so that when those lookups land
     * the result is shown locally rather than falling into the automatic party-chat path.
     */
    private static boolean localRequest;

    /** True between a /p list header and the end of its rows. */
    private static boolean readingList;
    private static final Set<String> LIST_SEEN = new LinkedHashSet<>();

    private PartyDex() {
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
        ShinyConfig config = ShinyConfig.get();
        if (!config.partyDexEnabled && !config.sharedCommand) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || text.startsWith("[Shiny Hunter]")) {
            return;
        }

        // The party commands ARE relayed lines — "Party > Name: !shared" — so they're handled
        // before the relay filter below, which exists to keep chatter out of the roster parsing.
        // (An earlier version filtered first and silently dropped every request.)
        if (HotspotAnnouncer.isRelayed(text)) {
            if (config.partyDexEnabled) {
                handleShinyRequest(text);
            }
            if (config.sharedCommand) {
                handleSharedRequest(text);
            }
            return;
        }

        if (PARTY_OVER.matcher(text).matches()) {
            clear("party over");
            return;
        }

        Matcher m;
        if ((m = WE_JOINED.matcher(text)).matches()) {
            clear("joined a party");
            add(nameIn(m.group(1)));
            return;
        }
        if ((m = PARTYING_WITH.matcher(text)).matches()) {
            for (String name : namesIn(m.group(1))) {
                add(name);
            }
            return;
        }
        if ((m = JOINED.matcher(text)).matches()) {
            add(nameIn(m.group(1)));
            return;
        }
        if ((m = LEFT.matcher(text)).matches() || (m = KICKED_OFFLINE.matcher(text)).matches()) {
            remove(nameIn(m.group(1)));
            return;
        }

        // /p list: header, then rows; a full rebuild of the roster from what the server says.
        if (LIST_HEADER.matcher(text).matches()) {
            readingList = true;
            LIST_SEEN.clear();
            return;
        }
        if ((m = LIST_ROW.matcher(text)).matches()) {
            // A roster row starts the read even with no header: Hypixel doesn't always print one,
            // and without this the whole list was ignored and the roster stayed empty.
            if (!readingList) {
                readingList = true;
                LIST_SEEN.clear();
            }
            LIST_SEEN.addAll(namesIn(m.group(1)));
            return;
        }
        if (readingList && text.startsWith("---")) {
            readingList = false;
            rebuild(LIST_SEEN);
        }
    }

    // ------------------------------------------------------------------ roster

    private static void add(String name) {
        if (name == null || name.equalsIgnoreCase(self())) {
            return;
        }
        if (MEMBERS.add(name)) {
            ShinyHunterClient.LOGGER.info("Party: {} joined ({} others now)", name, MEMBERS.size());
            invalidate();
            lookup(name);
            checkFull();
        }
    }

    private static void remove(String name) {
        if (name != null && MEMBERS.remove(name)) {
            ShinyHunterClient.LOGGER.info("Party: {} left ({} others now)", name, MEMBERS.size());
            DEXES.remove(name);
            invalidate();
        }
    }

    private static void rebuild(Set<String> listed) {
        Set<String> others = new LinkedHashSet<>();
        for (String name : listed) {
            if (!name.equalsIgnoreCase(self())) {
                others.add(name);
            }
        }
        if (others.equals(MEMBERS)) {
            return;
        }
        ShinyHunterClient.LOGGER.info("Party: roster rebuilt from /p list — {}", others);
        MEMBERS.clear();
        MEMBERS.addAll(others);
        // Keep our own dex: it isn't in `others`, and dropping it left every later calculation
        // silently waiting on a lookup for ourselves.
        DEXES.keySet().removeIf(name -> !name.equalsIgnoreCase(self()) && !contains(others, name));
        invalidate();
        for (String name : others) {
            if (!DEXES.containsKey(name)) {
                lookup(name);
            }
        }
        checkFull();
    }

    private static boolean contains(Set<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void clear(String why) {
        if (!MEMBERS.isEmpty() || !SHARED.isEmpty()) {
            ShinyHunterClient.LOGGER.info("Party: cleared ({})", why);
        }
        MEMBERS.clear();
        DEXES.clear();
        invalidate();
    }

    /** Any roster change makes the shared set untrustworthy. */
    private static void invalidate() {
        if (!SHARED.isEmpty()) {
            Debug.log("party changed - skip list cleared");
        }
        SHARED.clear();
        calculated = false;
        localRequest = false;
    }

    private static String self() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "" : client.player.getName().getString();
    }

    // ------------------------------------------------------------------ lookups

    private static void lookup(String name) {
        if (!HypixelApi.hasKey()) {
            tell("§cPlayer lookups aren't available§r — can't show " + name + "'s sparklings.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
            if (error != null) {
                String reason = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                ShinyHunterClient.LOGGER.warn("Lookup failed for {}: {}", name, reason);
                tell("§cCouldn't look up " + name + "§r — " + reason);
                return;
            }
            PlayerDex dex = PlayerDex.from(name, result.uuid(), result.profiles());
            DEXES.put(name, dex);
            popup(dex);
            if (localRequest) {
                checkFull(true, false);
            } else {
                checkFull();
            }
            answerSharedIfReady();
        }));
    }

    /**
     * The join card: name, Hunting level, dex progress, with the full caught/missing lists behind
     * a hover. Chat rather than a HUD panel because hover is what makes "expand" possible.
     */
    private static void popup(PlayerDex dex) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        MutableComponent hover = Component.literal("");
        hover.append(Component.literal("Sparklings caught (" + dex.caught().size() + ")\n")
                .withStyle(ChatFormatting.GREEN));
        hover.append(Component.literal(dex.caught().isEmpty() ? "none\n" : String.join(", ", dex.caught()) + "\n")
                .withStyle(ChatFormatting.GRAY));
        List<String> missing = dex.missing();
        hover.append(Component.literal("\nStill needed (" + missing.size() + ")\n")
                .withStyle(ChatFormatting.RED));
        hover.append(Component.literal(missing.isEmpty() ? "none" : String.join(", ", missing))
                .withStyle(ChatFormatting.GRAY));
        if (dex.dexPath().isEmpty()) {
            hover.append(Component.literal("\n\nNo Sparkling Critterdex found in this profile — run /shiny dex "
                    + dex.name()).withStyle(ChatFormatting.DARK_GRAY));
        }

        // One message per line rather than one message with newlines in it: a chat copy mod
        // copies whole messages, so a single block would hand over the entire card when all that
        // was wanted was the tickets line.
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§b§l" + dex.name()));
        lines.add(Component.literal("§7Hunting level §f" + dex.levelText()));
        lines.add(Component.literal("§f" + dex.caught().size() + "§7/" + dex.total()
                + " uniques §8(hover to expand)"));
        addIfPresent(lines, dex.totalsLine());
        if (!dex.tickets().isEmpty()) {
            addIfPresent(lines, dex.ticketsLine());
        }
        addIfPresent(lines, dex.essenceLine());
        addIfPresent(lines, dex.capturesLine());

        client.player.sendSystemMessage(Component.literal("§b§m                    "));
        for (Component line : lines) {
            // The hover rides on every line, so the full caught/missing lists are one hover away
            // wherever the cursor happens to be.
            client.player.sendSystemMessage(((MutableComponent) line)
                    .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
        }
        client.player.sendSystemMessage(Component.literal("§b§m                    "));
    }

    private static void addIfPresent(List<Component> lines, String line) {
        if (line != null && !line.isEmpty()) {
            lines.add(Component.literal(line));
        }
    }

    // ------------------------------------------------------------------ the calculation

    /** Once everyone including us has a dex, and the party is full, announce the shared set. */
    private static void checkFull() {
        checkFull(false, ShinyConfig.get().partyDexAnnounce);
    }

    /**
     * @param force    calculate for the current roster even if it isn't a full party
     * @param toParty  post the result to party chat (the automatic full-party path) rather than
     *                 only to this client (the {@code /sparkling party} command)
     */
    private static void checkFull(boolean force, boolean toParty) {
        ShinyConfig config = ShinyConfig.get();
        int size = MEMBERS.size() + 1;
        if (calculated || (!force && size < FULL_PARTY)) {
            return;
        }
        String me = self();
        if (!DEXES.containsKey(me)) {
            lookup(me);
            return;
        }
        for (String name : MEMBERS) {
            if (!DEXES.containsKey(name)) {
                return; // still waiting on a lookup
            }
        }
        calculated = true;

        SHARED.clear();
        SHARED.addAll(intersection());
        ShinyHunterClient.LOGGER.info("Party dex: {} shared — {}", SHARED.size(), SHARED);

        String result = SHARED.isEmpty()
                ? "No sparklings are shared by everyone — nothing to skip!"
                : SHARED.size() + " sparkling" + (SHARED.size() == 1 ? " is" : "s are")
                        + " shared! You can skip " + String.join(", ", SHARED) + "!";

        localRequest = false;
        if (toParty) {
            PartyChat.send(config.critterChannel,
                    "Calculating completed sparklings across everyone in the party!");
            PartyChat.send(config.critterChannel, result);
        } else {
            // Shown only here; nothing leaves this client. Copy it into chat if you want it shared.
            tell("§7Sparklings across §f" + size + "§7 players:");
            tell(result);
            tell("§8(shown only to you — paste it into party chat yourself if you want)");
        }
    }

    /**
     * Sparklings every looked-up member (us included) has, in dex order. Computed from whatever
     * dexes are currently held — callers decide whether that's everyone.
     */
    private static Set<String> intersection() {
        Set<String> shared = null;
        for (PlayerDex dex : DEXES.values()) {
            if (shared == null) {
                shared = new LinkedHashSet<>(dex.caught());
            } else {
                shared.retainAll(dex.caught());
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        if (shared != null) {
            // Keep dex order rather than whichever member's order came first.
            for (String biome : CritterDex.biomes()) {
                for (String critter : CritterDex.critters(biome)) {
                    if (shared.contains(critter)) {
                        ordered.add(critter);
                    }
                }
            }
        }
        return ordered;
    }

    // ------------------------------------------------------------------ !shared

    /** Which !shared answer is owed once every member's dex is in, or null when none is. */
    private static String pendingShared;

    /**
     * {@code !shared} lists the sparklings every member has, filtered to the ones worth knowing
     * about; {@code !shared all} lists all of them. Only the requester's client answers, for the
     * same reason as {@code !shiny}. If a member hasn't been looked up yet the answer waits for
     * that lookup rather than reporting an intersection over half the party.
     */
    private static void handleSharedRequest(String text) {
        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!text.contains("Party >") || !relayed.find()) {
            return;
        }
        Matcher request = SHARED_REQUEST.matcher(relayed.group(2).trim());
        if (!request.matches()) {
            return;
        }
        String sender = HotspotAnnouncer.senderOf(relayed.group(1));
        if (sender == null || !sender.equalsIgnoreCase(self())) {
            return;
        }
        if (!HypixelApi.hasKey()) {
            tell("§cPlayer lookups aren't available§r — can't answer !shared.");
            return;
        }
        pendingShared = request.group(1) == null ? "useful" : "all";
        answerSharedIfReady();
    }

    /** Answers a waiting !shared once every member's dex is held, looking up any that aren't. */
    private static void answerSharedIfReady() {
        if (pendingShared == null) {
            return;
        }
        boolean waiting = false;
        String me = self();
        if (!DEXES.containsKey(me)) {
            lookup(me);
            waiting = true;
        }
        for (String name : MEMBERS) {
            if (!DEXES.containsKey(name)) {
                lookup(name);
                waiting = true;
            }
        }
        if (waiting) {
            return; // each lookup's completion calls back here
        }

        boolean all = "all".equals(pendingShared);
        pendingShared = null;
        ShinyConfig config = ShinyConfig.get();
        int players = MEMBERS.size() + 1;

        List<String> everything = new ArrayList<>(intersection());
        List<String> shared = new ArrayList<>(everything);
        if (!all) {
            Set<String> useful = usefulSparklings(config);
            shared.removeIf(critter -> !useful.contains(critter));
        }

        // The total is always stated, so a filtered list can't read as the whole story — "6
        // shared" when the party actually has 17 in common is exactly the confusion to avoid.
        String count = all
                ? everything.size() + " shared"
                : everything.size() + " shared (" + shared.size() + " useful)";

        if (shared.isEmpty()) {
            PartyChat.send(config.critterChannel, "Sparklings all " + players + " players have: "
                    + count + (all ? "." : " - none of the useful ones."));
            return;
        }
        int max = Math.max(1, config.sharedListMax);
        List<String> shown = shared.size() > max ? shared.subList(0, max) : shared;
        String tail = shared.size() > max ? " (+" + (shared.size() - max) + " more)" : "";
        PartyChat.send(config.critterChannel, "Sparklings all " + players + " players have, "
                + count + ": " + String.join(", ", shown) + tail);
    }

    /**
     * The configured "worth skipping" set as canonical names. Matched loosely — letters only, and
     * a trailing "s" forgiven — so "gemzies", "Gemzie" and "mantis shrimp" all resolve.
     */
    private static Set<String> usefulSparklings(ShinyConfig config) {
        Set<String> useful = new LinkedHashSet<>();
        for (String entry : config.usefulSparklings) {
            String canonical = looseCritter(entry);
            if (canonical != null) {
                useful.add(canonical);
            }
        }
        return useful;
    }

    static String looseCritter(String raw) {
        String squashed = raw.toLowerCase().replaceAll("[^a-z]", "");
        if (squashed.isEmpty()) {
            return null;
        }
        String singular = squashed.endsWith("s") ? squashed.substring(0, squashed.length() - 1) : squashed;
        for (String biome : CritterDex.biomes()) {
            for (String critter : CritterDex.critters(biome)) {
                String key = critter.toLowerCase().replaceAll("[^a-z]", "");
                if (key.equals(squashed) || key.equals(singular)) {
                    return critter;
                }
            }
        }
        return null;
    }

    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §r" + message));
        }
    }

    // ------------------------------------------------------------------ names

    /** The account name in rank-decorated text like {@code [MVP+] Name} or {@code Name ●}. */
    static String nameIn(String decorated) {
        List<String> names = namesIn(decorated);
        return names.isEmpty() ? null : names.get(0);
    }

    /** Every account name in a comma-separated, rank-decorated list. */
    static List<String> namesIn(String decorated) {
        List<String> names = new ArrayList<>();
        // Commas separate the roll-call; ● separates /p list rows. Split on both, then strip tags.
        String stripped = decorated.replaceAll("\\[[^\\]]*\\]", " ");
        for (String part : stripped.split("[,●]")) {
            Matcher m = ACCOUNT.matcher(part.trim());
            // The last account-shaped token: guild tags and emblems can precede the name.
            String last = null;
            while (m.find()) {
                last = m.group();
            }
            if (last != null) {
                names.add(last);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ state

    /** Sparklings the whole party already has — safe to leave off the list. */
    public static Set<String> shared() {
        return SHARED;
    }

    public static boolean isShared(String critter) {
        return ShinyConfig.get().partyDexSkipShared && SHARED.contains(critter);
    }

    public static Set<String> members() {
        return MEMBERS;
    }

    public static Map<String, PlayerDex> dexes() {
        return DEXES;
    }

    /**
     * {@code /sparkling A B C}: look several players up and print what they all have, locally.
     * The same intersection as {@code !shared all}, but for any names and only to you.
     */
    public static void compare(List<String> names) {
        compare(names, false);
    }

    /**
     * @param partyStyle word the result as the party's skip list rather than a plain comparison
     */
    public static void compare(List<String> names, boolean partyStyle) {
        if (!HypixelApi.hasKey()) {
            tell("§cPlayer lookups aren't available.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        tell("Looking up §f" + String.join(", ", names) + "§r...");

        Map<String, PlayerDex> results = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        int[] remaining = {names.size()};

        for (String name : names) {
            HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
                if (error != null) {
                    failed.add(name);
                } else {
                    results.put(name, PlayerDex.from(name, result.uuid(), result.profiles()));
                }
                if (--remaining[0] > 0 || client.player == null) {
                    return;
                }
                for (PlayerDex dex : results.values()) {
                    client.player.sendSystemMessage(Component.literal("  §f" + dex.name()
                            + " §7- Hunting §f" + dex.levelText() + "§7, §f" + dex.caught().size()
                            + "§7/" + dex.total()));
                    if (!dex.tickets().isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("    " + dex.ticketsLine()));
                    }
                    String essence = dex.essenceLine();
                    if (!essence.isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("    " + essence));
                    }
                }
                if (!failed.isEmpty()) {
                    tell("§cCouldn't look up: §f" + String.join(", ", failed));
                }
                if (results.size() < 2) {
                    if (partyStyle) {
                        tell("§7Nobody else to compare against yet.");
                    }
                    return;
                }
                Set<String> shared = null;
                for (PlayerDex dex : results.values()) {
                    if (shared == null) {
                        shared = new LinkedHashSet<>(dex.caught());
                    } else {
                        shared.retainAll(dex.caught());
                    }
                }
                List<String> ordered = new ArrayList<>();
                for (String biome : CritterDex.biomes()) {
                    for (String critter : CritterDex.critters(biome)) {
                        if (shared.contains(critter)) {
                            ordered.add(critter);
                        }
                    }
                }
                Set<String> useful = usefulSparklings(ShinyConfig.get());
                List<String> usefulShared = new ArrayList<>(ordered);
                usefulShared.removeIf(c -> !useful.contains(c));

                if (partyStyle) {
                    // Remembered as the party's skip list, the same as the automatic run does.
                    SHARED.clear();
                    SHARED.addAll(ordered);
                    calculated = true;
                }
                tell("Sparklings all §f" + results.size() + "§r have (§f" + ordered.size() + "§r): "
                        + (ordered.isEmpty() ? "§8none" : "§f" + String.join(", ", ordered)));
                tell("Useful ones: " + (usefulShared.isEmpty() ? "§8none" : "§a" + String.join(", ", usefulShared)));
                if (partyStyle) {
                    tell("§8(shown only to you — paste it into party chat yourself if you want)");
                }
            }));
        }
    }

    /**
     * {@code /sparkling party}: the same calculation that runs when the party fills, on demand —
     * for a party that formed before the mod was watching, or to re-check after somebody's dex
     * changed. Shown only to this client; nothing is posted.
     */
    public static void announcePartyNow() {
        List<String> names = new ArrayList<>();
        names.add(self());
        names.addAll(MEMBERS);
        if (names.size() == 1) {
            tell("§7No party members known — showing just you. Run §f/p list§7 to read the roster.");
        }
        // Deliberately the same path as /sparkling <a> <b>: it looks everyone up itself, waits for
        // all of them, and always prints something. The old version handed off to the full-party
        // calculation, which returned in silence whenever one lookup hadn't landed.
        compare(names, true);
    }

    /** For {@code /shiny dex <name>}: look one player up and print the card plus every candidate path. */
    public static void inspect(String name) {
        if (!HypixelApi.hasKey()) {
            tell("§cPlayer lookups aren't available.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        tell("Looking up §f" + name + "§r...");
        HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
            if (error != null) {
                String reason = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                tell("§cLookup failed§r — " + reason);
                return;
            }
            PlayerDex dex = PlayerDex.from(name, result.uuid(), result.profiles());
            popup(dex);
            // The path diagnostics only matter when the dex wasn't found, or when debugging.
            if (dex.dexPath().isEmpty()) {
                tell("§cNo Sparkling Critterdex found§r in this profile — turn on /shiny debug and retry "
                        + "to see the paths considered.");
            }
            if (Debug.isEnabled()) {
                Debug.log("dex read from:", dex.dexPath().isEmpty() ? "nothing" : dex.dexPath());
                for (String candidate : dex.candidates()) {
                    Debug.log("  ", candidate);
                }
            }
        }));
    }

    /**
     * Answers {@code !shiny <name>} in party chat with a one-line summary, posted to the party.
     *
     * <p>Only the client that <em>typed</em> the request answers. Every member's mod sees the same
     * party line, and the answer is the same for all of them — so unlike {@code !missing}, where each
     * client has its own tally to report, four identical replies would be pure spam.
     */
    private static void handleShinyRequest(String text) {
        Matcher relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!text.contains("Party >") || !relayed.find()) {
            return;
        }
        Matcher request = SHINY_REQUEST.matcher(relayed.group(2).trim());
        if (!request.matches()) {
            return;
        }
        String sender = HotspotAnnouncer.senderOf(relayed.group(1));
        if (sender == null || !sender.equalsIgnoreCase(self())) {
            return; // somebody else asked; their own client answers
        }
        String name = request.group(1);
        if (!HypixelApi.hasKey()) {
            tell("§cPlayer lookups aren't available§r — can't answer !shiny.");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
            if (error != null) {
                String reason = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                tell("§cCouldn't look up " + name + "§r — " + reason);
                return;
            }
            PlayerDex dex = PlayerDex.from(name, result.uuid(), result.profiles());
            PartyChat.send(ShinyConfig.get().critterChannel, summary(dex));
        }));
    }

    /** {@code Name: Hunting 50, 28/37 sparklings, missing A, B, C} — trimmed to fit one chat line. */
    static String summary(PlayerDex dex) {
        String head = dex.name() + ": Hunting " + dex.levelText() + ", " + dex.caught().size() + "/"
                + dex.total() + " sparklings";
        List<String> missing = dex.missing();
        if (missing.isEmpty()) {
            return head + " - all caught!";
        }
        String line = head + ", missing " + String.join(", ", missing);
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return head + ", missing " + missing.size();
    }
}
