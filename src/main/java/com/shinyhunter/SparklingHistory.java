package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shinyhunter.api.HypixelApi;
import com.shinyhunter.api.PlayerDex;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A permanent log of every sparkling caught while this client was watching — yours and anyone's
 * whose catch was announced in chat — for {@code /shiny history}.
 *
 * <p>Alongside the entries it remembers two things the history line needs and chat only shows in
 * passing: each player's rank (from the rank tag on any chat line they send) and each critter's
 * colour (from the rarity colour Hypixel gives its name in capture and loot-share lines). Both are
 * learned as they go by, so the first catch by a stranger may render with the default rank colour
 * and be corrected once they say something.
 *
 * <p>Stored in {@code config/shinyhunter-history.json}, separate from the settings so a settings
 * reset never wipes it.
 */
public final class SparklingHistory {

    /** Hypixel's announcement, the same wording for your own catch and a party member's. */
    private static final Pattern CAUGHT = Pattern.compile(
            "^SPARKLING! ([A-Za-z0-9_]{1,16}) caught a SPARKLING (.+?)!$");

    /** A rank tag ahead of an account name, anywhere in a line: {@code [MVP++] Name}. */
    private static final Pattern RANK_TAG = Pattern.compile(
            "\\[(VIP\\+?|MVP\\+{0,2}|YOUTUBE|ADMIN|GM|MOD|HELPER)\\] ([A-Za-z0-9_]{1,16})");

    /**
     * The critter name with its colour code, in the two server lines that carry it:
     * "CAPTURE! You caught a §6§lSPARKLING §aLitterbug§7 ..." and
     * "LOOT SHARE! ... from §bflluh§7 catching a §6§lSPARKLING §aBluebird§7!".
     */
    private static final Pattern COLOURED_CRITTER = Pattern.compile(
            "SPARKLING §(?:l)?§?([0-9a-f])([A-Z][A-Za-z' ]+?)§");

    /** Also learn colours from plain captures: "You caught a §aLitterbug§7 and gained". */
    private static final Pattern COLOURED_PLAIN = Pattern.compile(
            "You caught a §([0-9a-f])([A-Z][A-Za-z' ]+?)§7");

    /** No year: the hover carries the full date, and every entry is from this year anyway. */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM dd", Locale.ENGLISH);

    /** The full moment, shown when the date is hovered. */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("EEEE, MMM dd yyyy 'at' h:mm:ss a", Locale.ENGLISH);

    private static final int PAGE_SIZE = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Rank -> name colour. Unknown ranks fall back to MVP+'s aqua, as asked. */
    private static final Map<String, String> RANK_COLOURS = Map.of(
            "VIP", "§a", "VIP+", "§a",
            "MVP", "§b", "MVP+", "§b", "MVP++", "§6",
            "YOUTUBE", "§c", "ADMIN", "§c", "GM", "§2",
            "MOD", "§2", "HELPER", "§9");
    private static final String DEFAULT_NAME_COLOUR = "§b";

    /** One caught sparkling. Mutable and public for Gson. */
    public static final class Entry {
        public long at;
        public String player;
        public String critter;
        public boolean unique;
    }

    /** The whole file. */
    private static final class Data {
        List<Entry> entries = new ArrayList<>();
        Map<String, String> ranks = new LinkedHashMap<>();
        Map<String, String> colours = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static boolean loaded;

    private SparklingHistory() {
    }

    public static void register() {
        load();
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
        String styled = legacy(message);
        String text = EntityDataProbe.stripFormatting(styled).trim();
        if (text.isEmpty() || text.startsWith("[Shiny Hunter]") || text.startsWith("[dbg]")) {
            return;
        }

        learnRank(text);
        learnColour(styled);

        // A catch relayed into party chat by another mod carries a channel marker; only the
        // server's own announcement counts, or a friend pasting it would log a second catch.
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }
        Matcher caught = CAUGHT.matcher(text);
        if (caught.matches()) {
            record(caught.group(1), caught.group(2).trim());
        }
    }

    private static void learnRank(String text) {
        Matcher tag = RANK_TAG.matcher(text);
        boolean changed = false;
        while (tag.find()) {
            String previous = data.ranks.put(tag.group(2), tag.group(1));
            changed |= !tag.group(1).equals(previous);
        }
        if (changed) {
            save();
        }
    }

    private static void learnColour(String styled) {
        boolean changed = false;
        for (Pattern pattern : List.of(COLOURED_CRITTER, COLOURED_PLAIN)) {
            Matcher m = pattern.matcher(styled);
            while (m.find()) {
                String colour = "§" + m.group(1);
                String name = m.group(2).trim();
                if (name.isEmpty() || name.equalsIgnoreCase("SPARKLING")) {
                    continue;
                }
                changed |= !colour.equals(data.colours.put(name, colour));
            }
        }
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------------ recording

    private static void record(String player, String critter) {
        Entry entry = new Entry();
        entry.at = System.currentTimeMillis();
        entry.player = player;
        entry.critter = critter;
        data.entries.add(0, entry);
        save();
        ShinyHunterClient.LOGGER.info("History: {} caught a SPARKLING {}", player, critter);

        // "Unique" is judged against the player's Sparkling Critterdex from the API, which is the
        // only place that says whether they'd had this one before. The cached copy is preferred:
        // it predates the catch, so it can't already include it. A fresh lookup can race the
        // server writing the catch, in which case the catch simply isn't flagged.
        PlayerDex cached = cachedDex(player);
        if (cached != null) {
            markUnique(entry, cached);
        } else if (HypixelApi.hasKey() && ShinyConfig.get().partyDexEnabled) {
            Minecraft client = Minecraft.getInstance();
            HypixelApi.profiles(player).whenComplete((result, error) -> client.execute(() -> {
                if (error == null) {
                    markUnique(entry, PlayerDex.from(player, result.uuid(), result.profiles()));
                }
            }));
        }
    }

    private static void markUnique(Entry entry, PlayerDex dex) {
        if (dex.dexPath().isEmpty()) {
            return; // no dex found — nothing to compare against
        }
        boolean hadBefore = false;
        for (String known : dex.caught()) {
            if (known.equalsIgnoreCase(entry.critter)) {
                hadBefore = true;
                break;
            }
        }
        // A second catch of the same critter in one session must not read as unique too, whatever
        // the (possibly stale) dex says.
        for (Entry other : data.entries) {
            if (other != entry && other.player.equalsIgnoreCase(entry.player)
                    && other.critter.equalsIgnoreCase(entry.critter) && other.at < entry.at) {
                hadBefore = true;
                break;
            }
        }
        if (!hadBefore) {
            entry.unique = true;
            save();
        }
    }

    private static PlayerDex cachedDex(String player) {
        for (Map.Entry<String, PlayerDex> known : PartyDex.dexes().entrySet()) {
            if (known.getKey().equalsIgnoreCase(player)) {
                return known.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ output

    /** Lines for {@code /shiny history [page]}, most recent first. */
    public static List<Component> lines(int page) {
        List<Component> lines = new ArrayList<>();
        int total = data.entries.size();
        if (total == 0) {
            lines.add(Component.literal("§7No sparklings caught yet."));
            return lines;
        }
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.clamp(page, 1, pages);
        lines.add(header(page, pages, total));
        int from = (page - 1) * PAGE_SIZE;
        for (Entry entry : data.entries.subList(from, Math.min(total, from + PAGE_SIZE))) {
            lines.add(format(entry));
        }
        return lines;
    }

    /**
     * {@code << Sparkling History (102 catches, page 10/11) >>}, the arrows clickable.
     *
     * <p>The arrows run {@code /shiny history <n>}, which is one of this mod's own client
     * commands: Fabric intercepts it and cancels the send, so clicking one puts nothing on the
     * wire — exactly as if the command had been typed.
     */
    private static Component header(int page, int pages, int total) {
        MutableComponent line = Component.literal("");
        line.append(arrow("«", page - 1, page > 1, "Newer"));
        line.append(Component.literal(" §b§lSparkling History §7(" + total + " catch"
                + (total == 1 ? "" : "es") + ", page " + page + "/" + pages + ") "));
        line.append(arrow("»", page + 1, page < pages, "Older"));
        return line;
    }

    private static Component arrow(String glyph, int target, boolean enabled, String what) {
        if (!enabled) {
            return Component.literal("§8§l" + glyph); // nothing that way; shown but dead
        }
        MutableComponent button = Component.literal("§b§l" + glyph);
        return button.withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/shiny history " + target))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("§f" + what + " §7(page " + target + ")"))));
    }

    /**
     * {@code Sep 20 2026 §bName §7caught a §6§lSPARKLING §aLitterbug §e§lUNIQUE}, with the exact
     * time of the catch on the date's hover.
     */
    static Component format(Entry entry) {
        var when = Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault());
        String rank = data.ranks.get(entry.player);
        String nameColour = rank == null ? DEFAULT_NAME_COLOUR
                : RANK_COLOURS.getOrDefault(rank, DEFAULT_NAME_COLOUR);
        String critterColour = data.colours.getOrDefault(entry.critter, "§f");

        MutableComponent date = Component.literal("§7" + DATE.format(when));
        Component hover = Component.literal("§f" + TIMESTAMP.format(when));
        date.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));

        MutableComponent line = date.append(Component.literal(" " + nameColour + entry.player
                + " §7caught a §6§lSPARKLING " + critterColour + entry.critter));
        if (entry.unique) {
            line.append(Component.literal(" §e§lNEW!"));
        } else {
            line.append(Component.literal(" §b#" + catchNumber(entry)));
        }
        return line;
    }

    /**
     * Which catch of this critter this is, counting every one in the file whoever made it. Worked
     * out at display time rather than stored, so the numbers stay right when older catches are
     * added — pasting them in from a log renumbers everything below it automatically.
     */
    private static int catchNumber(Entry entry) {
        int number = 0;
        for (Entry other : data.entries) {
            if (other.critter.equalsIgnoreCase(entry.critter) && other.at <= entry.at) {
                number++;
            }
        }
        return number;
    }

    public static int count() {
        return data.entries.size();
    }

    // ------------------------------------------------------------------ styled text

    /**
     * The component flattened to legacy {@code §}-coded text. Hypixel sends some lines as styled
     * components rather than code-laden strings, and {@code getString()} drops the styling — which
     * is exactly the part the colour learning needs.
     */
    private static String legacy(Component component) {
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            out.append(codes(style)).append(text);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static String codes(Style style) {
        StringBuilder codes = new StringBuilder();
        TextColor colour = style.getColor();
        if (colour != null) {
            ChatFormatting named = ChatFormatting.getByName(colour.serialize());
            codes.append(named != null ? "§" + named.getChar() : "§r");
        }
        if (style.isBold()) {
            codes.append("§l");
        }
        return codes.toString();
    }

    // ------------------------------------------------------------------ storage

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-history.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = path();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.entries == null) {
                    read.entries = new ArrayList<>();
                }
                if (read.ranks == null) {
                    read.ranks = new LinkedHashMap<>();
                }
                if (read.colours == null) {
                    read.colours = new LinkedHashMap<>();
                }
                read.entries.removeIf(e -> e == null || e.player == null || e.critter == null);
                read.entries.sort((a, b) -> Long.compare(b.at, a.at));
                data = read;
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read sparkling history — starting empty", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save sparkling history", e);
        }
    }
}
