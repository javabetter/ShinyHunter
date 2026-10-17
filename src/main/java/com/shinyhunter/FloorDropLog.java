package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logs which floor drop gave what, per run, for working out how drops are chosen.
 *
 * <p>A floor drop is three {@code item_display} entities with consecutive entity ids on one spot.
 * While you're in the Safari every loaded item display is read and grouped into those triples. When
 * the server says {@code FLOOR DROP! You found <item> on the ground!}, the drop is matched to the
 * group you clicked just before (or, failing that, the group nearest you) and written down with its
 * ids, position and biome. Every group seen is kept too, picked up or not, so a run's file shows all
 * its drops. Everything goes to {@code config/shinyhunter-floor-drops.json}, one entry per run.
 *
 * <p><b>Telling what a drop is before it's picked up.</b> Logged runs showed the server spawns a run's
 * floor drops in a fixed order, so sorted by entity id they are always: 6 gems (Lime, Purple,
 * Orange, twice) in the Cavern, then 3 Wriggleworms, 3 Yogi Berries and 3 Bags of Seeds in the
 * Forest, then 5 Soothing Incense and the Shining Coins (3 to 5) in Haunted, then 5 Icebreakers in
 * Icy. Everything after that is random: Hunting XP, Safari Essence, or a shard of a critter from the
 * biome it lies in. The biome of each drop marks where one block ends and the next begins
 * ({@link #predict}). Each live drop gets a label, and each pickup logs what was predicted.
 *
 * <p>Reads entities and chat only; the click callbacks watch and never consume an interaction.
 */
public final class FloorDropLog {

    private static final Pattern FLOOR_DROP = Pattern.compile("^FLOOR DROP! You found (.+?) on the ground!$");
    private static final Pattern AMOUNT_AFTER = Pattern.compile("^(.+?) x(\\d+)$");
    private static final Pattern AMOUNT_BEFORE = Pattern.compile("^(\\d+)x (.+)$");

    private static final int SCAN_INTERVAL_TICKS = 5;
    /** How close a triple's parts have to be to each other to count as one drop. */
    private static final double PART_SPREAD = 1.5;
    /** How long after a click the message still belongs to it, and how near the click the drop must be. */
    private static final long CLICK_WINDOW_MILLIS = 3_000L;
    private static final double CLICK_RADIUS = 3.0;
    /** Without a usable click: the nearest group within this distance of you. */
    private static final double NEAR_RADIUS = 5.0;
    /** A group that vanished this recently can still be the one just picked up. */
    private static final long GONE_GRACE_MILLIS = 3_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ------------------------------------------------------------------ saved shape

    /** One item display group, as seen. */
    public static final class Seen {
        public int[] ids;
        public double x;
        public double y;
        public double z;
        public String biome;
        /** What each part displays: item id and name. */
        public List<String> items = new ArrayList<>();
        public String firstSeen;
        public String lastSeen;
        /** What it gave, once picked up. */
        public String gave;
        /** What the spawn order said it would be. */
        public String predicted;
    }

    /** One pickup. */
    public static final class Drop {
        public String time;
        public String message;
        public String item;
        public int amount;
        public int[] ids;
        public double x;
        public double y;
        public double z;
        public String biome;
        public List<String> items = new ArrayList<>();
        /** "click" when matched to the thing you clicked, "nearest" otherwise. */
        public String matchedBy;
        /** What was clicked, and how long before the message. */
        public String clicked;
        public long clickedMillisBefore = -1;
        /** What the spawn order said it would be, and whether that was right. */
        public String predicted;
        public Boolean predictedRight;
    }

    public static final class RunLog {
        public long start;
        public String started;
        public Integer run;
        public List<Drop> drops = new ArrayList<>();
        public List<String> unmatched = new ArrayList<>();
        public List<Seen> groups = new ArrayList<>();
    }

    private static final class Data {
        String note = "Floor drops per run: each drop's item_display entity ids (three consecutive), position and"
                + " what it gave. Written by Shiny Hunter.";
        List<RunLog> runs = new ArrayList<>();
    }

    // ------------------------------------------------------------------ state

    private static final class Live {
        final Seen seen;
        long goneAt;

        Live(Seen seen) {
            this.seen = seen;
        }
    }

    private static Data data;
    private static RunLog current;
    /** Group key (first id) -> the group, this run. */
    private static final Map<Integer, Live> GROUPS = new LinkedHashMap<>();
    private static int tickCounter;
    private static Vec3 clickedAt;
    private static String clickedWhat;
    private static long clickedTime;

    private FloorDropLog() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                BlockPos pos = hit.getBlockPos();
                clicked(Vec3.atCenterOf(pos), "use block " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                        + " @" + pos.toShortString());
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                clicked(Vec3.atCenterOf(pos), "attack block " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                        + " @" + pos.toShortString());
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                clicked(entity.position(), "use entity " + EntityType.getKey(entity.getType()) + " #" + entity.getId());
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                clicked(entity.position(), "attack entity " + EntityType.getKey(entity.getType()) + " #" + entity.getId());
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
    }

    private static void clicked(Vec3 where, String what) {
        if (!ShinyConfig.get().floorDropLog) {
            return;
        }
        clickedAt = where;
        clickedWhat = what;
        clickedTime = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ scanning

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.floorDropLog || client.level == null || client.player == null) {
            return;
        }
        RunHistory.Run run = RunHistory.current();
        if (run == null || !SkyblockSidebar.isAtLocation(client, config.huntLocation)) {
            return;
        }
        if (current == null || current.start != run.start) {
            startRun(run.start);
        }
        activeAt = System.currentTimeMillis();
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(client);
    }

    private static void startRun(long start) {
        load();
        current = new RunLog();
        current.start = start;
        current.started = java.time.Instant.ofEpochMilli(start).toString();
        data.runs.add(current);
        GROUPS.clear();
        fillRunNumbers();
        save();
    }

    private static void scan(Minecraft client) {
        List<Display.ItemDisplay> displays = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Display.ItemDisplay display && !entity.isRemoved()) {
                displays.add(display);
            }
        }
        displays.sort(Comparator.comparingInt(Entity::getId));

        long now = System.currentTimeMillis();
        String stamp = java.time.Instant.ofEpochMilli(now).toString();
        java.util.Set<Integer> present = new java.util.HashSet<>();
        boolean added = false;
        for (int i = 0; i + 2 < displays.size(); i++) {
            Display.ItemDisplay a = displays.get(i);
            Display.ItemDisplay b = displays.get(i + 1);
            Display.ItemDisplay c = displays.get(i + 2);
            if (b.getId() != a.getId() + 1 || c.getId() != a.getId() + 2
                    || a.distanceTo(b) > PART_SPREAD || a.distanceTo(c) > PART_SPREAD || b.distanceTo(c) > PART_SPREAD) {
                continue;
            }
            present.add(a.getId());
            Live live = GROUPS.get(a.getId());
            if (live == null) {
                Seen seen = new Seen();
                seen.ids = new int[] {a.getId(), b.getId(), c.getId()};
                Vec3 centre = a.position().add(b.position()).add(c.position()).scale(1.0 / 3);
                seen.x = round(centre.x);
                seen.y = round(centre.y);
                seen.z = round(centre.z);
                seen.biome = biomeAt(centre.x, centre.z);
                for (Display.ItemDisplay part : List.of(a, b, c)) {
                    seen.items.add(describe(part.getItemStack()));
                }
                seen.firstSeen = stamp;
                live = new Live(seen);
                GROUPS.put(a.getId(), live);
                current.groups.add(seen);
                added = true;
            }
            live.seen.lastSeen = stamp;
            live.goneAt = 0;
            i += 2;
        }
        for (Map.Entry<Integer, Live> entry : GROUPS.entrySet()) {
            if (!present.contains(entry.getKey()) && entry.getValue().goneAt == 0) {
                entry.getValue().goneAt = now;
            }
        }
        if (added) {
            repredict();
            save();
        }
    }

    // ------------------------------------------------------------------ prediction

    static final String RANDOM = "XP / Essence / Shard";
    private static final String[] GEMS = {"Lime Gem", "Purple Gem", "Orange Gem"};
    private static final String[] PHASE_BIOMES = {"Cavern", "Forest", "Haunted", "Icy"};

    /** The Safari's biomes by position, as the run tracker splits them. */
    static String biomeAt(double x, double z) {
        if (x < -200 || x > 100 || z < -150 || z > 150) {
            return "?";
        }
        if (x >= -50) {
            return z >= 0 ? "Forest" : "Haunted";
        }
        return z >= 0 ? "Cavern" : "Icy";
    }

    /**
     * What each floor drop group will give, from the spawn order: groups sorted by id walk through
     * the fixed blocks (gems, Forest foods, Incense and Coins, Icebreakers), moving to the next
     * block when the biome changes, then everything else is random. A slot is counted from the
     * block's first id, so a group someone picked up before it was seen doesn't shift the rest.
     *
     * @return first id -> predicted item
     */
    static Map<Integer, String> predict(List<Seen> groups) {
        List<Seen> drops = new ArrayList<>();
        for (Seen seen : groups) {
            // A floor drop's three displays all show one item; scenery triples mix items.
            if (seen.items.size() == 3 && seen.items.stream().distinct().count() == 1) {
                drops.add(seen);
            }
        }
        drops.sort(Comparator.comparingInt(seen -> seen.ids[0]));
        Map<Integer, String> out = new LinkedHashMap<>();
        int phase = 0;
        int phaseStart = -1;
        boolean random = false;
        for (Seen seen : drops) {
            String biome = biomeAt(seen.x, seen.z);
            if (!random) {
                while (phase < PHASE_BIOMES.length && !biome.equals(PHASE_BIOMES[phase])) {
                    phase++;
                    phaseStart = -1;
                }
                random = phase >= PHASE_BIOMES.length;
            }
            String label = null;
            if (!random) {
                if (phaseStart < 0) {
                    phaseStart = seen.ids[0];
                }
                int slot = (seen.ids[0] - phaseStart) / 3;
                label = switch (phase) {
                    case 0 -> slot < 6 ? GEMS[slot % 3] : null;
                    case 1 -> slot < 3 ? "Wriggleworm" : slot < 6 ? "Yogi Berry" : slot < 9 ? "Bag of Seeds" : null;
                    case 2 -> slot < 5 ? "Soothing Incense" : "Shining Coin";
                    default -> slot < 5 ? "Icebreaker" : null;
                };
                random = label == null;
            }
            out.put(seen.ids[0], label == null ? RANDOM : label);
        }
        return out;
    }

    private static void repredict() {
        Map<Integer, String> predicted = predict(current.groups);
        for (Seen seen : current.groups) {
            seen.predicted = predicted.get(seen.ids[0]);
        }
    }

    /** A predicted drop still on the ground, for {@link FloorDropView}. */
    public record OnGround(int key, double x, double y, double z, String predicted) {
    }

    private static long activeAt;

    /** True while a run's drops are being tracked (in the Safari, in a run, logging on). */
    public static boolean active() {
        return current != null && System.currentTimeMillis() - activeAt < 1_000L;
    }

    /** Every drop with a prediction that hasn't been picked up or vanished. */
    public static List<OnGround> onGround() {
        List<OnGround> out = new ArrayList<>();
        for (Map.Entry<Integer, Live> entry : GROUPS.entrySet()) {
            Seen seen = entry.getValue().seen;
            if (seen.predicted != null && seen.gave == null && entry.getValue().goneAt == 0) {
                out.add(new OnGround(entry.getKey(), seen.x, seen.y, seen.z, seen.predicted));
            }
        }
        return out;
    }

    /** How many of this item you picked up this run. */
    public static int pickedUp(String item) {
        if (current == null) {
            return 0;
        }
        int n = 0;
        for (Drop drop : current.drops) {
            if (item.equals(drop.item)) {
                n++;
            }
        }
        return n;
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " \"" + EntityDataProbe.stripFormatting(stack.getHoverName().getString()) + "\"";
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ------------------------------------------------------------------ pickups

    private static void handle(Component message) {
        if (!ShinyConfig.get().floorDropLog || current == null) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        Matcher m = FLOOR_DROP.matcher(text);
        if (!m.matches()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        scan(client); // the freshest picture of what's still there
        long now = System.currentTimeMillis();

        Drop drop = new Drop();
        drop.time = java.time.Instant.ofEpochMilli(now).toString();
        drop.message = text;
        String found = m.group(1).trim();
        Matcher after = AMOUNT_AFTER.matcher(found);
        Matcher before = AMOUNT_BEFORE.matcher(found);
        if (after.matches()) {
            drop.item = after.group(1);
            drop.amount = Integer.parseInt(after.group(2));
        } else if (before.matches()) {
            drop.item = before.group(2);
            drop.amount = Integer.parseInt(before.group(1));
        } else {
            drop.item = found;
            drop.amount = 1;
        }

        boolean clickFresh = clickedAt != null && now - clickedTime <= CLICK_WINDOW_MILLIS;
        if (clickedAt != null) {
            drop.clicked = clickedWhat;
            drop.clickedMillisBefore = now - clickedTime;
        }
        Live match = null;
        if (clickFresh) {
            match = nearest(clickedAt, CLICK_RADIUS, now);
            drop.matchedBy = "click";
        }
        if (match == null) {
            match = nearest(client.player.position(), NEAR_RADIUS, now);
            drop.matchedBy = "nearest";
        }
        if (match == null) {
            current.unmatched.add(drop.time + " " + text + (drop.clicked == null ? "" : " (clicked " + drop.clicked + ")"));
            ShinyHunterClient.LOGGER.info("Floor drop not matched to an item display group: {}", text);
            save();
            return;
        }
        Seen seen = match.seen;
        seen.gave = drop.item + (drop.amount > 1 ? " x" + drop.amount : "");
        drop.ids = seen.ids;
        drop.x = seen.x;
        drop.y = seen.y;
        drop.z = seen.z;
        drop.biome = seen.biome;
        drop.items = seen.items;
        drop.predicted = seen.predicted;
        if (seen.predicted != null) {
            drop.predictedRight = RANDOM.equals(seen.predicted)
                    ? !isFixedItem(drop.item)
                    : seen.predicted.equals(drop.item);
        }
        current.drops.add(drop);
        save();
        ShinyHunterClient.LOGGER.info("Floor drop {} <- item displays {}-{} at {}, {}, {} ({}), predicted {}", seen.gave, seen.ids[0], seen.ids[2],
                seen.x, seen.y, seen.z, drop.matchedBy, seen.predicted);
        if (ShinyConfig.get().floorDropLogChat) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §7Floor drop §f" + seen.gave + "§7 ← #"
                    + seen.ids[0] + "-" + seen.ids[2] + " §8(" + drop.matchedBy + ")"));
        }
    }

    private static boolean isFixedItem(String item) {
        return switch (item) {
            case "Lime Gem", "Purple Gem", "Orange Gem", "Wriggleworm", "Yogi Berry", "Bag of Seeds",
                    "Soothing Incense", "Shining Coin", "Icebreaker" -> true;
            default -> false;
        };
    }

    /** The unclaimed group nearest a point within a radius: still there, or gone only just now. */
    private static Live nearest(Vec3 point, double radius, long now) {
        Live best = null;
        double bestSq = radius * radius;
        for (Live live : GROUPS.values()) {
            if (live.seen.gave != null || (live.goneAt != 0 && now - live.goneAt > GONE_GRACE_MILLIS)) {
                continue;
            }
            double dx = live.seen.x - point.x;
            double dy = live.seen.y - point.y;
            double dz = live.seen.z - point.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestSq) {
                bestSq = d;
                best = live;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ command

    /** {@code /shiny floordrops}: this run so far, and where the file is. */
    public static List<String> status() {
        load();
        List<String> lines = new ArrayList<>();
        lines.add("§b§lFloor drop log §7— " + (ShinyConfig.get().floorDropLog ? "§aon" : "§coff")
                + "§7, " + data.runs.size() + " run(s) logged");
        if (current != null) {
            lines.add("§7This run: §f" + current.groups.size() + "§7 groups seen, §f" + current.drops.size()
                    + "§7 picked up, §f" + current.unmatched.size() + "§7 unmatched");
            int checked = 0;
            int right = 0;
            for (Drop drop : current.drops) {
                if (drop.predictedRight != null) {
                    checked++;
                    right += drop.predictedRight ? 1 : 0;
                }
            }
            lines.add("§7Predictions right: §f" + right + "/" + checked);
            for (Drop drop : current.drops) {
                lines.add("  " + (Boolean.FALSE.equals(drop.predictedRight) ? "§c✗ " : "§a✓ ") + "§f" + drop.item
                        + (drop.amount > 1 ? " x" + drop.amount : "") + " §7#" + drop.ids[0] + "-" + drop.ids[2]
                        + " §8predicted " + drop.predicted + " (" + drop.matchedBy + ")");
            }
        }
        lines.add("§8" + path());
        return lines;
    }

    // ------------------------------------------------------------------ storage

    /** Fills in run numbers once the run history has saved them. */
    private static void fillRunNumbers() {
        for (RunLog log : data.runs) {
            if (log.run != null) {
                continue;
            }
            for (RunHistory.Run run : RunHistory.runs()) {
                if (run.start == log.start) {
                    log.run = run.number;
                    break;
                }
            }
        }
        // A run with nothing seen at all (a hop through the entrance) isn't worth keeping.
        data.runs.removeIf(log -> log != current && log.groups.isEmpty() && log.drops.isEmpty() && log.unmatched.isEmpty());
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-floor-drops.json");
    }

    private static void load() {
        if (data != null) {
            return;
        }
        data = new Data();
        Path file = path();
        if (!Files.exists(file)) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null && read.runs != null) {
                data = read;
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read the floor drop log — starting a new one", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save the floor drop log", e);
        }
    }
}
