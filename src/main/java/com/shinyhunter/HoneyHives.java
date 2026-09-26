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
 * <p><b>Knowing which hives are full.</b> Three sources, best first:
 *
 * <ol>
 *   <li>The block itself, when the chunk is loaded — a bee nest's honey level is in its block
 *       state, and nothing is more reliable than looking.</li>
 *   <li>This client's own memory of looting one: a hive refills exactly an hour later (measured
 *       against the API — loots at 20:53:51 and 20:56:10 produced refills at 21:53:52 and
 *       21:56:11), so a loot here means empty until then.</li>
 *   <li>Hypixel's {@code foraging.honey.refill_times}, which lists a refill time per hive id for
 *       hives looted on any session. A hive in that list with a future time is empty; anything
 *       else is full.</li>
 * </ol>
 *
 * <p><b>Matching hive ids to positions.</b> The API names hives {@code hive_8}, {@code hive_14}
 * and so on and never says where they are, and the loot message doesn't name one either. The exact
 * hour gives the join: when this client sees a loot at a known position at time T, the hive whose
 * refill time lands on T + 1h is that position's. Those bindings are written into the survey file
 * as they're learned, so the third source becomes usable for one hive after you loot it once, and
 * stays usable across sessions.
 *
 * <p>A hive whose state is unknown is drawn anyway (it is, after all, probably full) in a dimmer
 * colour, so an unsurveyed or unmatched hive doesn't silently vanish from the map.
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

    /** Nest key -> when this client saw it looted, so it's empty without asking anyone. */
    private static final Map<String, Long> LOOTED_HERE = new HashMap<>();

    /** Loots seen but not yet matched to a hive id: nest key -> loot time. */
    private static final Map<String, Long> PENDING = new LinkedHashMap<>();

    /** What to draw: nest key -> colour, worked out on the slow cycle and drawn every tick. */
    private static final Map<String, Integer> VISIBLE = new LinkedHashMap<>();

    private static int stateCounter = STATE_INTERVAL_TICKS;
    private static int refreshCounter = REFRESH_INTERVAL_TICKS;
    private static boolean refreshing;

    private HoneyHives() {
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
        if (!ShinyConfig.get().honeyHiveWaypoints) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (QUEEN_BEE.matcher(text).find()) {
            // Instantly refilled: undo the loot that has just been recorded for this spot.
            String key = nearestKey(client);
            if (key != null) {
                LOOTED_HERE.remove(key);
                PENDING.remove(key);
                ShinyHunterClient.LOGGER.info("Queen bee refilled the hive at {}", key);
            }
            return;
        }
        if (!LOOTED.matcher(text).find()) {
            return;
        }

        String key = nearestKey(client);
        long now = System.currentTimeMillis();
        if (key == null) {
            ShinyHunterClient.LOGGER.info("Honeyhive looted but no surveyed hive is within {} blocks"
                    + " — run /shiny nestsurvey on and walk past it", LOOT_RADIUS);
            return;
        }
        LOOTED_HERE.put(key, now);
        PENDING.put(key, now);
        // The binding needs the API to have caught up, so this is a nudge rather than a demand.
        refreshCounter = REFRESH_INTERVAL_TICKS;
        ShinyHunterClient.LOGGER.info("Honeyhive looted at {} — full again at {}", key,
                java.time.Instant.ofEpochMilli(now + REFILL_MILLIS));
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
        FULL, EMPTY, UNKNOWN
    }

    private static State stateOf(Minecraft client, String key, NestSurvey.Nest nest, long now) {
        // 1. The block, when it's loaded. Nothing beats looking at it.
        BlockPos pos = new BlockPos(nest.x, nest.y, nest.z);
        if (client.level.hasChunkAt(pos)) {
            BlockState state = client.level.getBlockState(pos);
            if (state.is(Blocks.BEE_NEST) && state.hasProperty(BeehiveBlock.HONEY_LEVEL)) {
                return state.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS
                        ? State.FULL : State.EMPTY;
            }
        }
        // 2. This client's own memory of looting it.
        Long looted = LOOTED_HERE.get(key);
        if (looted != null) {
            if (now < looted + REFILL_MILLIS) {
                return State.EMPTY;
            }
            LOOTED_HERE.remove(key);
        }
        // 3. The API, for hives whose id has been matched to this position.
        if (nest.hive != null) {
            Long refill = REFILLS.get(nest.hive);
            if (refill != null) {
                return now < refill ? State.EMPTY : State.FULL;
            }
            return State.FULL; // known hive, not in the refill list at all: nothing pending
        }
        return State.UNKNOWN;
    }

    // ------------------------------------------------------------------ drawing

    /** Decides which hives get a beacon, and in what colour. Runs on the slow cycle. */
    private static void recompute(Minecraft client, ShinyConfig config) {
        VISIBLE.clear();
        long now = System.currentTimeMillis();
        int full = BeeNestHighlighter.parseColor(config.honeyHiveColor, 0xFF55FF55);
        int unknown = (full & 0x00FFFFFF) | 0x66000000;
        double rangeSq = (double) config.honeyHiveRange * config.honeyHiveRange;

        for (Map.Entry<String, NestSurvey.Nest> entry : NestSurvey.nests().entrySet()) {
            NestSurvey.Nest nest = entry.getValue();
            Vec3 centre = new Vec3(nest.x + 0.5, nest.y + 0.5, nest.z + 0.5);
            if (client.player.position().distanceToSqr(centre) > rangeSq) {
                continue;
            }
            State state = stateOf(client, entry.getKey(), nest, now);
            if (state == State.EMPTY || (state == State.UNKNOWN && !config.honeyHiveShowUnknown)) {
                continue;
            }
            VISIBLE.put(entry.getKey(), state == State.FULL ? full : unknown);
        }
        Debug.log("hive beacons:", VISIBLE.size(), "of", NestSurvey.nests().size(), "in range");
    }

    private static void draw(Minecraft client, ShinyConfig config) {
        // The beams themselves are drawn by the game's beacon renderer, from inside the level
        // render; this hands it the current set. The box around the hive is still a gizmo.
        Map<Vec3, Integer> beams = new LinkedHashMap<>();
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
            Debug.log("honeyhive refills:", REFILLS.size(), "pending bindings:", PENDING.size());
        }));
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
            if (state == State.EMPTY && nest.hive != null && REFILLS.containsKey(nest.hive)) {
                long left = (REFILLS.get(nest.hive) - now) / 1000;
                when = " §8(" + ContestTracker.formatRemaining((int) Math.max(0, left)) + " left)";
            }
            lines.add("  " + switch (state) {
                case FULL -> "§afull  ";
                case EMPTY -> "§cempty ";
                case UNKNOWN -> "§7?     ";
            } + "§f" + entry.getKey() + " §8" + (nest.hive == null ? "unmatched" : nest.hive) + when);
        }
        lines.add("§7Full right now: §a" + fullCount);
        return lines;
    }

    public static void reset() {
        REFILLS.clear();
        PENDING.clear();
        VISIBLE.clear();
        refreshCounter = REFRESH_INTERVAL_TICKS;
        stateCounter = STATE_INTERVAL_TICKS;
    }
}
