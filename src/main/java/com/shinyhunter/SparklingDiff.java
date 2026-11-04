package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Debug test of a theory: critters only turn sparkling when the first ticket is used, so comparing
 * every entity's data before and after a ticket should show a difference when an instance has one.
 *
 * <p>While on, every loaded entity is fingerprinted twice a second from the moment you join the
 * Safari (the ticket line arrives about two seconds after that). When a ticket line arrives:
 * <ol>
 *   <li><b>before</b> is the last fingerprint taken ahead of it;</li>
 *   <li><b>after</b> is taken five seconds later, and compared with before;</li>
 *   <li>a <b>control</b> five seconds after that shows what changes with no ticket involved
 *       (animations, timers), and those changes are discounted.</li>
 * </ol>
 * Whatever is left is the signal. A teammate's first ticket gives you no chat line, so changes to
 * skins, items and names are also watched continuously until the first catch of the instance.
 *
 * <p>Each instance is saved with whether a sparkling actually turned up, which is what shows if the
 * theory holds: {@code /shiny sparkdiff} prints the hits and misses. Each report also keeps a census
 * (entities per type and per critter, the id span) and any sparkling skin in the player list, two
 * other places a sparkling might show early. Reads entities and chat only; nothing is sent.
 */
public final class SparklingDiff {

    private static final int CAPTURE_TICKS = 10;
    private static final long AFTER_MILLIS = 5_000L;
    private static final long CONTROL_MILLIS = 5_000L;
    private static final long TEST_FORCE_MILLIS = 30_000L;
    /** The Safari Manager's spot is in the middle area, so look a long way for a critter biome. */
    private static final int SAFARI_REACH = 96;
    private static final int MAX_LISTED = 40;
    private static final int MAX_VALUE = 120;
    private static final int MAX_INSTANCES = 300;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ------------------------------------------------------------------ saved shape

    public static final class TicketReport {
        public String line;
        public String at;
        public boolean firstOfInstance;
        public boolean test;
        public long beforeAgeMillis;
        public int beforeEntities;
        public int afterEntities;
        public int inBoth;
        public int changed;
        public int discounted;
        public int signal;
        public List<String> changes = new ArrayList<>();
        public Map<String, Integer> byKind = new TreeMap<>();
    }

    public static final class Instance {
        public String started;
        public List<TicketReport> tickets = new ArrayList<>();
        /** Skin, item or name changes seen before the first catch, with no ticket needed. */
        public List<String> passive = new ArrayList<>();
        public boolean flagged;
        /** Sparklings that actually turned up here. */
        public Set<String> sparklings = new TreeSet<>();
        /** Sparkling skins found on player-list profiles rather than on an entity. */
        public Set<String> listedSkins = new TreeSet<>();
        public Map<String, Integer> censusTypes = new TreeMap<>();
        public Map<String, Integer> censusCritters = new TreeMap<>();
        public int censusIdMin;
        public int censusIdMax;
    }

    private static final class Data {
        String note = "Sparkling ticket-diff test: what changed around tickets per instance, and whether a"
                + " sparkling really turned up. Written by Shiny Hunter.";
        List<Instance> instances = new ArrayList<>();
    }

    // ------------------------------------------------------------------ state

    /** One entity's data at one moment. Position is left out on purpose: critters move. */
    private record Print(String type, String label, Map<String, String> fields) {
    }

    private record Change(int id, String type, String label, String field, String from, String to) {
        boolean stable() {
            return !field.startsWith("val#");
        }

        String describe() {
            return type + " #" + id + (label.isEmpty() ? "" : " (" + label + ")") + " " + field + ": " + from + " -> " + to;
        }
    }

    private static final class Pending {
        String line;
        long at;
        boolean first;
        boolean test;
        Map<Integer, Print> before;
        long beforeAt;
        Map<Integer, Print> after;
    }

    private static Data data;
    private static Instance current;
    private static Object level;
    private static int tickCounter;
    private static Map<Integer, Print> last;
    private static long lastAt;
    private static Pending pending;
    private static long forcedUntil;
    private static Map<Integer, Print> manualSnap;
    private static boolean passiveAlerted;

    private SparklingDiff() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chat(EntityDataProbe.stripFormatting(message.getString()).trim());
            }
        });
    }

    private static boolean active() {
        return ShinyConfig.get().sparklingDiffDebug;
    }

    private static void chat(String text) {
        if (!active()) {
            return;
        }
        if (TicketCounter.isTicketLine(text)) {
            startComparison(text, false);
        } else if (text.startsWith("SPARKLING!")) {
            String critter = CritterDex.findIn(text);
            sparklingSeen(critter == null ? text : critter);
        }
    }

    /** Called for every real sparkling, so a report can say whether its instance had one. */
    public static void sparklingSeen(String critter) {
        if (active() && critter != null && instance().sparklings.add(critter)) {
            save();
        }
    }

    private static Instance instance() {
        load();
        if (current == null) {
            current = new Instance();
            current.started = java.time.Instant.now().toString();
            data.instances.add(current);
            while (data.instances.size() > MAX_INSTANCES) {
                data.instances.remove(0);
            }
        }
        return current;
    }

    private static void startComparison(String line, boolean test) {
        if (pending != null) {
            return;
        }
        Pending next = new Pending();
        next.line = line;
        next.at = System.currentTimeMillis();
        next.test = test;
        next.first = !test && instance().tickets.stream().noneMatch(t -> !t.test);
        next.before = last;
        next.beforeAt = lastAt;
        pending = next;
    }

    // ------------------------------------------------------------------ the tick

    public static void onClientTick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            current = null;
            last = null;
            pending = null;
            manualSnap = null;
            passiveAlerted = false;
        }
        long now = System.currentTimeMillis();
        boolean forced = now < forcedUntil;
        if (client.level == null || client.player == null || (!active() && !forced)) {
            return;
        }
        if (++tickCounter < CAPTURE_TICKS) {
            return;
        }
        tickCounter = 0;
        if (!forced && !SafariBiomes.nearCritterBiome(client, SAFARI_REACH)) {
            return;
        }

        Map<Integer, Print> now_ = capture(client);
        if (last != null && !forced) {
            watchPassive(client, diff(last, now_));
        }
        last = now_;
        lastAt = now;

        Pending job = pending;
        if (job != null) {
            if (job.after == null && now >= job.at + AFTER_MILLIS) {
                job.after = now_;
                census(client);
            } else if (job.after != null && now >= job.at + AFTER_MILLIS + CONTROL_MILLIS) {
                pending = null;
                finish(client, job, now_);
            }
        }
    }

    /** Stable data changing before anyone has caught anything is worth saying straight away. */
    private static void watchPassive(Minecraft client, List<Change> changes) {
        if (CritterTracker.foundCount() > 0) {
            return;
        }
        List<Change> stable = changes.stream().filter(Change::stable).toList();
        if (stable.isEmpty()) {
            return;
        }
        Instance instance = instance();
        for (Change change : stable) {
            if (instance.passive.size() < MAX_LISTED) {
                instance.passive.add(change.describe());
            }
        }
        instance.flagged = true;
        save();
        ShinyHunterClient.LOGGER.info("Sparkling diff: {} stable change(s) before any catch, e.g. {}",
                stable.size(), stable.get(0).describe());
        if (!passiveAlerted) {
            passiveAlerted = true;
            tell(client, "§e[debug] Entity data changed before any catch: a sparkling may be in this instance. §7"
                    + cut(stable.get(0).describe()));
        }
    }

    private static void finish(Minecraft client, Pending job, Map<Integer, Print> control) {
        Instance instance = instance();
        TicketReport report = new TicketReport();
        report.line = job.line;
        report.at = java.time.Instant.ofEpochMilli(job.at).toString();
        report.firstOfInstance = job.first;
        report.test = job.test;
        instance.tickets.add(report);
        if (job.before == null) {
            report.beforeAgeMillis = -1;
            save();
            tell(client, "§7[debug] Ticket seen, but no \"before\" snapshot existed to compare.");
            return;
        }
        report.beforeAgeMillis = job.at - job.beforeAt;
        report.beforeEntities = job.before.size();
        report.afterEntities = job.after.size();
        report.inBoth = (int) job.before.keySet().stream().filter(job.after::containsKey).count();

        List<Change> changes = diff(job.before, job.after);
        // What changes anyway, with no ticket involved. Values are discounted per entity type, since
        // an animation runs on all of them; text and items only on the same entity, so one stand with
        // a ticking timer can't hide a real change on another.
        Set<String> noisy = new HashSet<>();
        for (Change change : diff(job.after, control)) {
            noisy.add(signature(change));
        }
        List<Change> signal = changes.stream().filter(change -> !noisy.contains(signature(change))).toList();
        report.changed = changes.size();
        report.discounted = changes.size() - signal.size();
        report.signal = signal.size();
        for (Change change : signal) {
            report.byKind.merge(change.type() + " " + change.field(), 1, Integer::sum);
            if (report.changes.size() < MAX_LISTED) {
                report.changes.add(change.describe());
            }
        }
        if (!job.test && !signal.isEmpty()) {
            instance.flagged = true;
        }
        save();
        ShinyHunterClient.LOGGER.info("Sparkling diff: {} {} in both, {} changed, {} discounted, {} signal: {}",
                job.first ? "first ticket," : "ticket,", report.inBoth, report.changed, report.discounted,
                report.signal, report.byKind);
        String which = job.test ? "Test" : job.first ? "First ticket" : "Later ticket";
        if (signal.isEmpty()) {
            tell(client, "§7[debug] " + which + ": " + report.inBoth + " entities compared, no difference ("
                    + report.discounted + " ignored as noise).");
            return;
        }
        tell(client, "§e[debug] " + which + ": " + signal.size() + " difference(s) across " + report.inBoth
                + " entities. §6§lLikely a sparkling in this instance.");
        tell(client, "§7  " + cut(signal.get(0).describe()));
        client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    private static String signature(Change change) {
        return change.stable() ? "#" + change.id() + "|" + change.field() : change.type() + "|" + change.field();
    }

    // ------------------------------------------------------------------ other places to look

    /** Entity counts and the sparkling skins on list profiles, kept with the report for comparing. */
    private static void census(Minecraft client) {
        Instance instance = instance();
        instance.censusTypes.clear();
        instance.censusCritters.clear();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getId() < 0 || entity == client.player) {
                continue;
            }
            min = Math.min(min, entity.getId());
            max = Math.max(max, entity.getId());
            instance.censusTypes.merge(EntityType.getKey(entity.getType()).getPath(), 1, Integer::sum);
            String critter = CritterDex.findIn(EntityDataProbe.nameOf(entity));
            if (critter != null) {
                instance.censusCritters.merge(critter, 1, Integer::sum);
            }
        }
        instance.censusIdMin = min == Integer.MAX_VALUE ? 0 : min;
        instance.censusIdMax = max == Integer.MIN_VALUE ? 0 : max;
        if (client.getConnection() != null) {
            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                String critter = SparklingSkins.critterFor(TextureProbe.hashesOf(info.getProfile()));
                if (critter != null && instance.listedSkins.add(critter)) {
                    tell(client, "§e[debug] A sparkling " + critter + " skin is on a player-list profile.");
                }
            }
        }
        save();
    }

    // ------------------------------------------------------------------ fingerprints

    private static Map<Integer, Print> capture(Minecraft client) {
        Map<Integer, Print> out = new HashMap<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            // Negative ids are this mod's own stand-in items. Real players' health and hunger change
            // all the time; player-model critters have non-v4 ids and are kept.
            if (entity.getId() < 0 || entity.isRemoved() || entity == client.player
                    || (entity instanceof Player && entity.getUUID().version() == 4)) {
                continue;
            }
            out.put(entity.getId(), print(entity));
        }
        return out;
    }

    private static Print print(Entity entity) {
        Map<String, String> fields = new LinkedHashMap<>();
        String name = EntityDataProbe.nameOf(entity);
        fields.put("name", text(name));
        Set<String> skins = TextureProbe.hashesOf(entity);
        if (!skins.isEmpty()) {
            fields.put("skin", String.join(",", new TreeSet<>(skins)));
        }
        fields.put("passengers", String.valueOf(entity.getPassengers().size()));
        if (entity instanceof LivingEntity living) {
            StringBuilder equipment = new StringBuilder();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.append(slot.getName()).append('=').append(stack(stack)).append(' ');
                }
            }
            if (!equipment.isEmpty()) {
                fields.put("equip", equipment.toString().trim());
            }
        }
        try {
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            if (values != null) {
                for (SynchedEntityData.DataValue<?> value : values) {
                    add(fields, value.id(), value.value());
                }
            }
        } catch (RuntimeException e) {
            ShinyHunterClient.LOGGER.debug("Couldn't read entity {}", entity.getId(), e);
        }
        String critter = CritterDex.findIn(name);
        return new Print(EntityType.getKey(entity.getType()).getPath(), critter == null ? "" : critter, fields);
    }

    /** Items and text count as stable data; everything else may just be an animation. */
    private static void add(Map<String, String> fields, int id, Object value) {
        switch (value) {
            case null -> {
            }
            case Optional<?> optional -> optional.ifPresent(inner -> add(fields, id, inner));
            case ItemStack stack -> fields.put("item#" + id, stack(stack));
            case Component component -> fields.put("text#" + id, text(component.getString()));
            case String string -> fields.put("text#" + id, text(string));
            default -> fields.put("val#" + id, limit(String.valueOf(value)));
        }
    }

    private static String stack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " \"" + text(stack.getHoverName().getString()) + "\"";
    }

    /** Text with its numbers masked, so health bars and countdowns don't read as changes. */
    private static String text(String raw) {
        return limit(EntityDataProbe.stripFormatting(raw).replaceAll("\\d+", "#"));
    }

    private static String limit(String value) {
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE) + "…";
    }

    private static String cut(String value) {
        return value.length() <= 90 ? value : value.substring(0, 90) + "…";
    }

    /** Field differences on entities present in both. New or gone entities are just tracking range. */
    private static List<Change> diff(Map<Integer, Print> from, Map<Integer, Print> to) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<Integer, Print> entry : from.entrySet()) {
            Print after = to.get(entry.getKey());
            if (after == null) {
                continue;
            }
            Print before = entry.getValue();
            Set<String> keys = new HashSet<>(before.fields().keySet());
            keys.addAll(after.fields().keySet());
            for (String key : keys) {
                String a = before.fields().get(key);
                String b = after.fields().get(key);
                if (a == null ? b != null : !a.equals(b)) {
                    out.add(new Change(entry.getKey(), after.type(),
                            after.label().isEmpty() ? before.label() : after.label(), key, String.valueOf(a), String.valueOf(b)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ commands

    /** {@code /shiny sparkdiff on|off}. */
    public static List<String> setEnabled(boolean on) {
        ShinyConfig config = ShinyConfig.get();
        config.sparklingDiffDebug = on;
        config.save();
        return status();
    }

    /**
     * {@code /shiny sparkdiff test}: runs the whole before/after/control comparison now, anywhere,
     * without a ticket. Anything it reports is noise the real test would have to survive.
     */
    public static List<String> test() {
        Minecraft client = Minecraft.getInstance();
        forcedUntil = System.currentTimeMillis() + TEST_FORCE_MILLIS;
        last = client.level == null ? null : capture(client);
        lastAt = System.currentTimeMillis();
        pending = null;
        startComparison("manual test", true);
        return List.of("§7Comparing now: the result comes in about 10 seconds. Change something to see it noticed.");
    }

    /** {@code /shiny sparkdiff snap}: the first call stores a snapshot, the second compares with it. */
    public static List<String> snap() {
        Minecraft client = Minecraft.getInstance();
        List<String> lines = new ArrayList<>();
        if (client.level == null) {
            return lines;
        }
        Map<Integer, Print> now = capture(client);
        if (manualSnap == null) {
            manualSnap = now;
            lines.add("§7Snapshot of §f" + now.size() + "§7 entities stored. Run this again to compare.");
            return lines;
        }
        List<Change> changes = diff(manualSnap, now);
        manualSnap = null;
        Map<String, Integer> kinds = new TreeMap<>();
        for (Change change : changes) {
            kinds.merge(change.type() + " " + change.field(), 1, Integer::sum);
            ShinyHunterClient.LOGGER.info("Sparkling diff snap: {}", change.describe());
        }
        lines.add("§7Compared: §f" + changes.size() + "§7 differences §8(all in latest.log)");
        kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(kind -> lines.add("  §f" + kind.getValue() + "x §7" + kind.getKey()));
        return lines;
    }

    /** {@code /shiny sparkdiff}: on or off, and how the theory is doing. */
    public static List<String> status() {
        load();
        int hit = 0;
        int falseAlarm = 0;
        int missed = 0;
        int quiet = 0;
        for (Instance instance : data.instances) {
            if (instance.tickets.stream().allMatch(ticket -> ticket.test)) {
                continue;
            }
            boolean had = !instance.sparklings.isEmpty();
            if (instance.flagged) {
                if (had) hit++;
                else falseAlarm++;
            } else if (had) {
                missed++;
            } else {
                quiet++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("§b§lTicket diff test §7— " + (active() ? "§aon" : "§coff")
                + " §8(/shiny sparkdiff on|off|test|snap)");
        lines.add("§7Difference and a sparkling: §a" + hit + " §7· difference, no sparkling: §c" + falseAlarm);
        lines.add("§7No difference, but a sparkling: §c" + missed + " §7· no difference, no sparkling: §a" + quiet);
        if (current != null) {
            lines.add("§7This instance: " + (current.flagged ? "§eflagged" : "§7not flagged") + "§7, tickets §f"
                    + current.tickets.size() + "§7, sparklings §f" + current.sparklings);
        }
        lines.add("§8" + path());
        return lines;
    }

    private static void tell(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] " + message));
        }
    }

    // ------------------------------------------------------------------ storage

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-sparkling-diff.json");
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
            if (read != null && read.instances != null) {
                data = read;
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read the sparkling diff log — starting a new one", e);
        }
    }

    private static void save() {
        load();
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save the sparkling diff log", e);
        }
    }
}
