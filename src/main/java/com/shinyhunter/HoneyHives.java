package com.shinyhunter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shinyhunter.api.HypixelApi;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Beacons over the honeyhives that are still full, while Miria's Contest is unfinished.
 *
 * <p><b>Knowing which hives are full.</b> Every hive is tracked on its own by one number: when it
 * refills. A hive gets a beacon only once that time has passed. Nothing else brings one back early,
 * the block's look included: hives are looted per player, and the block goes on looking full to you
 * after you've emptied it. The refill time comes from:
 *
 * <ol>
 *   <li>This client seeing you loot it: a hive refills exactly an hour later (measured against the
 *       API — loots at 20:53:51 and 20:56:10 produced refills at 21:53:52 and 21:56:11). The hive
 *       is the bee nest you clicked just before the loot message, so neighbouring hives can't be
 *       mixed up; a looted nest that isn't in the survey yet is added to it.</li>
 *   <li>Hypixel's {@code foraging.honey.refill_times}, which lists a refill time per hive id for
 *       hives looted on any session, merged in for every matched hive.</li>
 * </ol>
 *
 * <p>A Queen Bee proc is the one exception: it refills the hive there and then, so the hive is full
 * again at once, and an API refill time from before the proc is ignored for it.
 *
 * <p>Refill times are saved, so a hive stays empty for its full hour across restarts and server
 * hops, coming back from the Safari included.
 *
 * <p><b>Matching hive ids to positions.</b> The API names hives {@code hive_8}, {@code hive_14}
 * and so on and never says where they are, and the loot message doesn't name one either. The exact
 * hour gives the join: when this client sees a loot at a known position at time T, the hive whose
 * refill time lands on T + 1h is that position's. Those bindings are written into the survey file
 * as they're learned, so the third source becomes usable for one hive after you loot it once, and
 * stays usable across sessions.
 */
public final class HoneyHives {

    /** The server's line when a hive is looted. It names no hive, hence the timing match. */
    private static final Pattern LOOTED = Pattern.compile(
            "You stick your hand into the honeyhive and feel around", Pattern.CASE_INSENSITIVE);

    /** A queen bee refills the hive there and then, so the loot that just happened doesn't count. */
    private static final Pattern QUEEN_BEE = Pattern.compile(
            "QUEEN BEE! The Honeyhive instantly refilled", Pattern.CASE_INSENSITIVE);

    /** Measured against the API: refill time minus loot time, to the second. */
    static final long REFILL_MILLIS = 60 * 60 * 1000L;

    /** How far from a hive the loot message can be and still be taken as that hive's. */
    private static final double LOOT_RADIUS = 8.0;

    /** How close a refill time has to be to loot + an hour to count as the same event. */
    private static final long MATCH_TOLERANCE_MILLIS = 15_000L;

    private static final int REFRESH_INTERVAL_TICKS = 20 * 60 * 5;

    /** How often each hive's state is worked out again. The drawing happens every tick. */
    private static final int STATE_INTERVAL_TICKS = 20;

    /**
     * Slightly longer than a tick, like the other highlighters. Gizmos expire, so they have to be
     * re-emitted every tick — emitting them on a slower cycle than they last leaves gaps where
     * nothing is drawn at all.
     */
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** hive id -> when it refills, from the API. */
    private static final Map<String, Long> REFILLS = new HashMap<>();

    /**
     * Nest key -> when it refills, from loots seen here and from the API. The one thing that decides
     * whether a hive is full. Saved to disk, so a hive stays empty for its full hour across restarts
     * and server hops.
     */
    private static final Map<String, Long> REFILL_AT = new HashMap<>();

    /** Nest key -> when a Queen Bee refilled it; API refill times from before that are stale. */
    private static final Map<String, Long> QUEEN_AT = new HashMap<>();

    /** The bee nest last clicked, which is the hive a loot message straight after belongs to. */
    private static BlockPos clickedNest;
    private static long clickedAt;

    /** How soon after the click the loot message has to come to be that nest's. */
    private static final long CLICK_WINDOW_MILLIS = 5_000L;

    /** Loots seen but not yet matched to a hive id: nest key -> loot time. */
    private static final Map<String, Long> PENDING = new LinkedHashMap<>();

    /** What to draw: nest key -> colour, worked out on the slow cycle and drawn every tick. */
    private static final Map<String, Integer> VISIBLE = new LinkedHashMap<>();

    /** Hives you looted that are in range: nest key -> when they refill, for the countdown label. */
    private static final Map<String, Long> EMPTY_LABELS = new LinkedHashMap<>();

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private static int stateCounter = STATE_INTERVAL_TICKS;
    private static int refreshCounter = REFRESH_INTERVAL_TICKS;
    private static boolean refreshing;

    private HoneyHives() {
    }

    public static void register() {
        loadLoots();
        // Watching clicks only: neither callback changes or consumes the interaction.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            clicked(level, player, hit.getBlockPos());
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            clicked(level, player, pos);
            return net.minecraft.world.InteractionResult.PASS;
        });
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
        if (!ShinyConfig.get().honeyHiveWaypoints) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (QUEEN_BEE.matcher(text).find()) {
            // Instantly refilled: the hive is full again now, whatever any loot or the API said.
            String key = lootedKey(client, now);
            if (key != null) {
                REFILL_AT.remove(key);
                QUEEN_AT.put(key, now);
                PENDING.remove(key);
                saveLoots();
                ShinyHunterClient.LOGGER.info("Queen bee refilled the hive at {}", key);
            }
            return;
        }
        if (!LOOTED.matcher(text).find()) {
            return;
        }

        String key = lootedKey(client, now);
        if (key == null) {
            ShinyHunterClient.LOGGER.info("Honeyhive looted but no hive was clicked or is within {} blocks", LOOT_RADIUS);
            return;
        }
        REFILL_AT.put(key, now + REFILL_MILLIS);
        QUEEN_AT.remove(key);
        PENDING.put(key, now);
        saveLoots();
        // The binding needs the API to have caught up, so this is a nudge rather than a demand.
        refreshCounter = REFRESH_INTERVAL_TICKS;
        ShinyHunterClient.LOGGER.info("Honeyhive looted at {} — full again at {}", key,
                java.time.Instant.ofEpochMilli(now + REFILL_MILLIS));
    }

    private static void clicked(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player, BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (level.isClientSide() && player == client.player && level.getBlockState(pos).is(Blocks.BEE_NEST)) {
            clickedNest = pos.immutable();
            clickedAt = System.currentTimeMillis();
        }
    }

    /**
     * The hive a loot or Queen Bee message belongs to: the bee nest clicked just before it, added to
     * the survey if it's new, or failing that the surveyed hive nearest the player.
     */
    private static String lootedKey(Minecraft client, long now) {
        if (clickedNest != null && now - clickedAt <= CLICK_WINDOW_MILLIS) {
            return NestSurvey.addHive(clickedNest, SkyblockSidebar.zoneName(client));
        }
        return nearestKey(client);
    }

    /** The surveyed hive nearest the player, within {@link #LOOT_RADIUS}. */
    private static String nearestKey(Minecraft client) {
        if (client.player == null) {
            return null;
        }
        Vec3 eye = client.player.position();
        String best = null;
        double bestDistance = LOOT_RADIUS * LOOT_RADIUS;
        for (Map.Entry<String, NestSurvey.Nest> entry : NestSurvey.nests().entrySet()) {
            NestSurvey.Nest nest = entry.getValue();
            double d = eye.distanceToSqr(nest.x + 0.5, nest.y + 0.5, nest.z + 0.5);
            if (d <= bestDistance) {
                bestDistance = d;
                best = entry.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ tick

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.honeyHiveWaypoints || client.level == null || client.player == null) {
            VISIBLE.clear();
            HiveBeacons.clear();
            return;
        }
        if (++refreshCounter >= REFRESH_INTERVAL_TICKS) {
            refreshCounter = 0;
            refresh(client);
        }
        if (!shouldShow(client, config)) {
            // The beams are held by HiveBeacons and drawn every frame until told otherwise, so
            // they have to be cleared here too — clearing VISIBLE alone left them standing after
            // the contest was completed.
            VISIBLE.clear();
            HiveBeacons.clear();
            return;
        }
        if (++stateCounter >= STATE_INTERVAL_TICKS) {
            stateCounter = 0;
            recompute(client, config);
        }
        draw(client, config);
    }

    /**
     * Whether the beacons belong on screen: the contest is the reason to be collecting honey, so
     * they show while one is unfinished and get out of the way once it's done.
     */
    private static boolean shouldShow(Minecraft client, ShinyConfig config) {
        if (!SkyblockSidebar.inSkyblock(client)) {
            return false;
        }
        if (config.honeyHiveOnlyWhenIncomplete && ContestTracker.isComplete()) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ state

    /** What's known about one hive. */
    private enum State {
        FULL, EMPTY
    }

    /** Empty until its refill time, full after; a hive never looted is full. */
    static boolean full(Long refillAt, long now) {
        return refillAt == null || now >= refillAt;
    }

    private static State stateOf(Minecraft client, String key, NestSurvey.Nest nest, long now) {
        Long refill = REFILL_AT.get(key);
        if (refill != null && now >= refill) {
            REFILL_AT.remove(key);
            saveLoots();
        }
        return full(refill, now) ? State.FULL : State.EMPTY;
    }

    /**
     * Folds an API refill time into the hive's own: the later one wins, since the API can lag a loot
     * this client just saw. A time from a loot before the hive's last Queen Bee proc is stale (the API's
     * time runs a second or so past the loot, hence the tolerance).
     */
    static Long merge(Long current, long apiRefill, Long queenAt, long now) {
        if (apiRefill <= now) {
            return current;
        }
        if (queenAt != null && apiRefill - REFILL_MILLIS <= queenAt + MATCH_TOLERANCE_MILLIS) {
            return current;
        }
        return current == null ? apiRefill : Math.max(current, apiRefill);
    }

    // ------------------------------------------------------------------ drawing

    /** Decides which hives get a beacon, and in what colour. Runs on the slow cycle. */
    private static void recompute(Minecraft client, ShinyConfig config) {
        VISIBLE.clear();
        EMPTY_LABELS.clear();
        long now = System.currentTimeMillis();
        int full = BeeNestHighlighter.parseColor(config.honeyHiveColor, 0xFF55FF55);
        double rangeSq = (double) config.honeyHiveRange * config.honeyHiveRange;

        for (Map.Entry<String, NestSurvey.Nest> entry : NestSurvey.nests().entrySet()) {
            NestSurvey.Nest nest = entry.getValue();
            Vec3 centre = new Vec3(nest.x + 0.5, nest.y + 0.5, nest.z + 0.5);
            if (client.player.position().distanceToSqr(centre) > rangeSq) {
                continue;
            }
            State state = stateOf(client, entry.getKey(), nest, now);
            if (state == State.EMPTY) {
                if (config.honeyHiveShowRefill) {
                    EMPTY_LABELS.put(entry.getKey(), REFILL_AT.get(entry.getKey()));
                }
                continue;
            }
            VISIBLE.put(entry.getKey(), full);
        }
        Debug.log("hive beacons:", VISIBLE.size(), "of", NestSurvey.nests().size(), "in range");
    }

    private static void draw(Minecraft client, ShinyConfig config) {
        // The beams themselves are drawn by the game's beacon renderer, from inside the level
        // render; this hands it the current set. The box around the hive is still a gizmo.
        Map<Vec3, Integer> beams = new LinkedHashMap<>();
        drawRefillLabels(config);
        if (VISIBLE.isEmpty()) {
            HiveBeacons.clear();
            return;
        }
        try {
            for (Map.Entry<String, Integer> entry : VISIBLE.entrySet()) {
                NestSurvey.Nest nest = NestSurvey.nests().get(entry.getKey());
                if (nest == null) {
                    continue;
                }
                beams.put(new Vec3(nest.x, nest.y, nest.z), entry.getValue());
                if (config.honeyHiveOutline) {
                    outline(nest, entry.getValue(), config);
                }
                // A shader pack draws the real beam with its own beacon program (translucent and
                // frozen in some packs), so with one on the drawn column is used instead.
                if (HiveBeacons.broken() || Shaders.active()) {
                    column(nest, entry.getValue(), config);
                }
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw honeyhive outlines", e);
        }
        HiveBeacons.set(beams);
    }

    /** "Refills in 42m" over each hive you looted, so an emptied hive isn't walked back to. */
    private static void drawRefillLabels(ShinyConfig config) {
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<String, Long> entry : EMPTY_LABELS.entrySet()) {
                NestSurvey.Nest nest = NestSurvey.nests().get(entry.getKey());
                long left = (entry.getValue() - now) / 1000;
                if (nest == null || left <= 0) {
                    continue;
                }
                String text = "Refills in " + (left >= 60 ? (left / 60) + "m" : left + "s");
                var label = Gizmos.billboardTextOverBlock(text, new BlockPos(nest.x, nest.y, nest.z),
                                0, 0xFFFF6E6E, 0.6f)
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
                Shaders.place(label, config.honeyHiveThroughWalls);
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw honeyhive refill labels", e);
        }
    }

    /** The stand-in beam, drawn only if the real beacon beam couldn't be submitted. */
    private static void column(NestSurvey.Nest nest, int colour, ShinyConfig config) {
        int fill = (colour & 0x00FFFFFF) | 0x33000000;
        AABB shaft = new AABB(
                nest.x + 0.35, nest.y + 1.0, nest.z + 0.35,
                nest.x + 0.65, nest.y + 1.0 + Math.min(config.honeyHiveBeamHeight, 64), nest.z + 0.65);
        Gizmos.cuboid(shaft, GizmoStyle.strokeAndFill(colour, 1.0f, fill))
                .persistForMillis(GIZMO_LIFETIME_MILLIS);
    }

    /** The box around the hive block itself. The beam above it is a real beacon beam. */
    private static void outline(NestSurvey.Nest nest, int colour, ShinyConfig config) {
        int fill = (colour & 0x00FFFFFF) | 0x33000000;
        BlockPos pos = new BlockPos(nest.x, nest.y, nest.z);
        var box = Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(colour, 2.0f, fill))
                .persistForMillis(GIZMO_LIFETIME_MILLIS);
        Shaders.place(box, config.honeyHiveThroughWalls);
        HighlightMarkers.add(new Vec3(nest.x + 0.5, nest.y + 0.5, nest.z + 0.5), colour, "Hive",
                config.honeyHiveThroughWalls);
    }

    // ------------------------------------------------------------------ the API

    /**
     * Reads {@code foraging.honey.refill_times} for the player, and uses it to bind hive ids to
     * positions: a refill exactly an hour after a loot this client saw is that loot's hive.
     */
    private static void refresh(Minecraft client) {
        if (refreshing || !HypixelApi.hasKey() || client.player == null) {
            return;
        }
        if (NestSurvey.nests().isEmpty()) {
            return;
        }
        refreshing = true;
        String name = client.player.getName().getString();
        HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
            refreshing = false;
            if (error != null) {
                ShinyHunterClient.LOGGER.warn("Honeyhive refill lookup failed: {}", error.getMessage());
                return;
            }
            Map<String, Long> refills = readRefills(result.profiles(), result.uuid());
            if (refills == null) {
                return;
            }
            REFILLS.clear();
            REFILLS.putAll(refills);
            bindPending();
            mergeRefills();
            Debug.log("honeyhive refills:", REFILLS.size(), "pending bindings:", PENDING.size());
        }));
    }

    /** Gives every matched hive the API's refill time, unless a later loot or a Queen Bee says otherwise. */
    private static void mergeRefills() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, NestSurvey.Nest> entry : NestSurvey.nests().entrySet()) {
            String hive = entry.getValue().hive;
            Long api = hive == null ? null : REFILLS.get(hive);
            if (api == null) {
                continue;
            }
            Long before = REFILL_AT.get(entry.getKey());
            Long after = merge(before, api, QUEEN_AT.get(entry.getKey()), now);
            if (after != null && !after.equals(before)) {
                REFILL_AT.put(entry.getKey(), after);
                changed = true;
            }
        }
        if (changed) {
            saveLoots();
        }
    }

    /** Matches loots this client saw against the refill list, one hour apart. */
    private static void bindPending() {
        List<String> bound = new ArrayList<>();
        for (Map.Entry<String, Long> pending : PENDING.entrySet()) {
            long expected = pending.getValue() + REFILL_MILLIS;
            for (Map.Entry<String, Long> refill : REFILLS.entrySet()) {
                if (Math.abs(refill.getValue() - expected) > MATCH_TOLERANCE_MILLIS) {
                    continue;
                }
                if (NestSurvey.hiveTaken(refill.getKey(), pending.getKey())) {
                    continue; // already another position's
                }
                NestSurvey.bindHive(pending.getKey(), refill.getKey());
                bound.add(pending.getKey() + " = " + refill.getKey());
                break;
            }
        }
        for (String key : bound) {
            PENDING.remove(key.substring(0, key.indexOf(" = ")));
        }
        if (!bound.isEmpty()) {
            ShinyHunterClient.LOGGER.info("Honeyhive ids matched: {}", bound);
        }
        // A loot that never matched is dropped after a while rather than kept forever: the player
        // may have looted a hive that isn't in the survey yet.
        PENDING.values().removeIf(at -> System.currentTimeMillis() - at > REFILL_MILLIS);
    }

    private static Map<String, Long> readRefills(JsonObject profiles, String uuid) {
        if (profiles == null || !profiles.has("profiles") || !profiles.get("profiles").isJsonArray()) {
            return null;
        }
        for (JsonElement element : profiles.getAsJsonArray("profiles")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject profile = element.getAsJsonObject();
            if (!profile.has("selected") || !profile.get("selected").getAsBoolean()) {
                continue;
            }
            JsonElement node = navigate(profile, "members." + uuid + ".foraging.honey.refill_times");
            if (node == null || !node.isJsonObject()) {
                return Map.of();
            }
            Map<String, Long> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    out.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
            return out;
        }
        return null;
    }

    private static JsonElement navigate(JsonObject root, String path) {
        JsonElement node = root;
        for (String part : path.split("\\.")) {
            if (node == null || !node.isJsonObject()) {
                return null;
            }
            node = node.getAsJsonObject().get(part);
        }
        return node;
    }

    // ------------------------------------------------------------------ storage

    private static java.nio.file.Path lootsPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("shinyhunter-hive-loots.json");
    }

    /**
     * Reads the saved refill times, dropping any already passed. {@code {"refill": {key: time},
     * "queen": {key: time}}}; the older file was a plain {key: loot time}.
     */
    private static void loadLoots() {
        java.nio.file.Path file = lootsPath();
        if (!java.nio.file.Files.exists(file)) {
            return;
        }
        try {
            JsonObject saved = GSON.fromJson(java.nio.file.Files.readString(file), JsonObject.class);
            long now = System.currentTimeMillis();
            if (saved.has("refill") && saved.get("refill").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : saved.getAsJsonObject("refill").entrySet()) {
                    long at = entry.getValue().getAsLong();
                    if (now < at) {
                        REFILL_AT.put(entry.getKey(), at);
                    }
                }
                if (saved.has("queen") && saved.get("queen").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : saved.getAsJsonObject("queen").entrySet()) {
                        long at = entry.getValue().getAsLong();
                        if (now < at + REFILL_MILLIS) {
                            QUEEN_AT.put(entry.getKey(), at);
                        }
                    }
                }
            } else {
                for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
                    long at = entry.getValue().getAsLong() + REFILL_MILLIS;
                    if (now < at) {
                        REFILL_AT.put(entry.getKey(), at);
                    }
                }
            }
            ShinyHunterClient.LOGGER.info("Honeyhives: {} hive(s) still refilling", REFILL_AT.size());
        } catch (Exception e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read saved honeyhive loots", e);
        }
    }

    private static void saveLoots() {
        try {
            JsonObject out = new JsonObject();
            JsonObject refill = new JsonObject();
            JsonObject queen = new JsonObject();
            REFILL_AT.forEach(refill::addProperty);
            QUEEN_AT.forEach(queen::addProperty);
            out.add("refill", refill);
            out.add("queen", queen);
            java.nio.file.Files.createDirectories(lootsPath().getParent());
            java.nio.file.Files.writeString(lootsPath(), GSON.toJson(out));
        } catch (Exception e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save honeyhive loots", e);
        }
    }

    // ------------------------------------------------------------------ command

    /** {@code /shiny hives}: what's known about each hive right now. */
    public static List<String> status() {
        List<String> lines = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        lines.add("§b§lHoneyhives §7— " + (ShinyConfig.get().honeyHiveWaypoints ? "§aon" : "§coff")
                + "§7, " + NestSurvey.nests().size() + " surveyed, " + REFILLS.size() + " refilling");
        if (client.level == null || client.player == null) {
            return lines;
        }
        int fullCount = 0;
        for (Map.Entry<String, NestSurvey.Nest> entry : NestSurvey.nests().entrySet()) {
            NestSurvey.Nest nest = entry.getValue();
            State state = stateOf(client, entry.getKey(), nest, now);
            if (state == State.FULL) {
                fullCount++;
            }
            String when = "";
            Long refill = REFILL_AT.get(entry.getKey());
            if (state == State.EMPTY && refill != null) {
                long left = (refill - now) / 1000;
                when = " §8(" + ContestTracker.formatRemaining((int) Math.max(0, left)) + " left)";
            }
            lines.add("  " + switch (state) {
                case FULL -> "§afull  ";
                case EMPTY -> "§cempty ";
            } + "§f" + entry.getKey() + " §8" + (nest.hive == null ? "unmatched" : nest.hive) + when);
        }
        lines.add("§7Full right now: §a" + fullCount);
        return lines;
    }

    public static void reset() {
        REFILLS.clear();
        PENDING.clear();
        VISIBLE.clear();
        EMPTY_LABELS.clear();
        refreshCounter = REFRESH_INTERVAL_TICKS;
        stateCounter = STATE_INTERVAL_TICKS;
    }
}
