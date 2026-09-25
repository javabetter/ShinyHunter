package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outlines every entity matching a configured tracker, each in its own colour.
 *
 * <p>Separate from {@link ShinyScanner}: that hunts one keyword and makes noise about it, this
 * quietly marks a list of known things so they're findable in a crowd. Trackers set to announce also
 * call out coordinates once per entity.
 *
 * <p>Boxes are {@link EntityBoxGizmo}s rather than fixed cuboids, so they track at frame rate
 * instead of jumping once per tick. Must be driven from {@code END_CLIENT_TICK} — see
 * {@link BeeNestHighlighter} for why.
 */
public final class EntityHighlighter {

    private static final int RESCAN_INTERVAL_TICKS = 10;
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Nameplate text that marks the capture UI rather than a real mob. */
    private static final String CAPTURE_MARKER = "CAPTURING";
    private static final double CAPTURE_RADIUS = 4.0;

    /** Don't re-announce the same entity within this window. */
    private static final long ANNOUNCE_COOLDOWN_MILLIS = 120_000L;

    /** A match: which tracker, and what to write over it (empty = the entity's own name). */
    private record Hit(ShinyConfig.Tracker tracker, String label) {
    }

    /** entity id -> what matched it. Insertion-ordered for stable drawing. */
    private static final Map<Integer, Hit> MATCHED = new LinkedHashMap<>();

    /**
     * Shulker entity id -> which critter it turned out to be, for the rest of the run.
     *
     * <p>A shulker critter arrives in two parts at two ranges: the shulker itself, sent from far
     * off, and the armour stand carrying its name, sent only closer in. So a shulker is outlined
     * as just "Shulker" the moment it loads, gets its real name when the stand shows up, and keeps
     * that name after walking back out of stand range — the identification isn't thrown away
     * with the stand. Cleared with the run, since a new instance re-rolls everything.
     */
    private static final Map<Integer, String> SHULKER_KINDS = new HashMap<>();

    /** How far a name stand can sit from its shulker and still be taken as its label. */
    private static final double SHULKER_NAME_RADIUS = 3.0;

    private static final String UNKNOWN_SHULKER = "Shulker";

    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;

    private static final Map<Integer, Long> ANNOUNCED = new HashMap<>();

    /**
     * Entity id -> model tint for everything outlined this tick. Rebuilt each tick and swapped in
     * whole, so the renderer (same thread, but mid-frame) never sees it half-built.
     */
    private static volatile Map<Integer, Integer> TINTS = Map.of();

    /** How far toward the outline colour a tinted mob is pulled; 0 = untouched, 1 = solid colour. */
    private static final float TINT_STRENGTH = 0.6f;

    private static int tickCounter;

    private EntityHighlighter() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            SHULKER_KINDS.clear();
        }

        if (!config.highlightEntities
                || client.level == null
                || client.player == null
                || config.trackers == null
                || config.trackers.isEmpty()
                || (config.onlyInSkyblock && !SkyblockSidebar.inSkyblock(client))) {
            MATCHED.clear();
            TINTS = Map.of();
            tickCounter = RESCAN_INTERVAL_TICKS;
            return;
        }

        if (++tickCounter >= RESCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            rescan(client, config);
        }
        emit(client, config);
    }

    private static void rescan(Minecraft client, ShinyConfig config) {
        MATCHED.clear();

        List<ShinyConfig.Tracker> active = new ArrayList<>();
        for (ShinyConfig.Tracker tracker : config.trackers) {
            if (tracker.enabled && tracker.token != null && !tracker.token.isBlank()) {
                active.add(tracker);
            }
        }

        // Critters are handled by what's still outstanding on this run rather than by the tracker
        // list, so every one of them counts, not just the few somebody thought to add.
        boolean critterMode = config.highlightMissingCritters
                && config.trackCritters
                && SkyblockSidebar.isAtLocation(client, config.huntLocation);
        ShinyConfig.Tracker missingCritter = null;
        if (critterMode) {
            missingCritter = new ShinyConfig.Tracker("critter");
            missingCritter.color = config.missingCritterColor;
            missingCritter.announce = false;
        }

        if (active.isEmpty() && !critterMode) {
            return;
        }

        List<Entity> captureNameplates = config.highlightIgnoreCapturing
                ? findCaptureNameplates(client)
                : List.of();

        double deepRadiusSq = (double) config.highlightDeepRadius * config.highlightDeepRadius;
        long now = System.currentTimeMillis();

        // Shulkers first, so their name stands can be recognised (and not boxed twice) below.
        List<Entity> shulkers = new ArrayList<>();
        if (critterMode && config.shulkerIsHideonfloor) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.monster.Shulker && !entity.isRemoved()) {
                    shulkers.add(entity);
                }
            }
        }

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }

            String rawName = EntityDataProbe.nameOf(entity);

            // A known critter takes this branch whatever the tracker list says: it's outlined only
            // while still outstanding, and goes dark the moment one is caught. Falling through to
            // the tracker list would keep drawing a box on a critter already secured, and draw two
            // boxes on the ones that are in both.
            if (critterMode) {
                if (entity instanceof net.minecraft.world.entity.monster.Shulker) {
                    if (config.shulkerIsHideonfloor) {
                        String kind = identifyShulker(client, entity);
                        // Once it's known and caught, the box and the name both go — unless asked
                        // to keep shulkers lit regardless; the name is remembered either way.
                        if (kind == null || config.alwaysHighlightShulkers || !CritterTracker.isFound(kind)) {
                            MATCHED.put(entity.getId(), new Hit(missingCritter, kind == null ? UNKNOWN_SHULKER : kind));
                        }
                    }
                    continue;
                }
                String critter = CritterDex.findIn(rawName);
                if (critter != null) {
                    // The name stand of a shulker is drawn as part of the shulker, not on its own.
                    if (!CritterTracker.isFound(critter) && !isCaptureUi(entity, captureNameplates)
                            && !nearAny(entity, shulkers)) {
                        // The name rides on an armour stand; the box belongs on the mob under it.
                        Entity body = bodyUnder(client, entity);
                        // The critter's name comes from the nameplate, which is what was matched;
                        // the mob under it reports its vanilla type ("Tropical Fish" for a
                        // Gimmiegold), so carrying the name across is what keeps the label right.
                        MATCHED.put(body.getId(), new Hit(missingCritter, critter));
                    }
                    continue;
                }
            }

            String name = rawName.toUpperCase();
            ShinyConfig.Tracker hit = firstMatch(active, name);

            // Some things worth tracking carry no nameplate at all — the rocks that precede a
            // Rockmite, for instance — so nearby entities also get their item and NBT data checked.
            if (hit == null
                    && config.highlightDeepScan
                    && client.player.distanceToSqr(entity) <= deepRadiusSq) {
                hit = firstMatch(active, EntityDataProbe.deepText(entity).toUpperCase());
            }

            if (hit == null || isCaptureUi(entity, captureNameplates)) {
                continue;
            }

            // Named matches are nameplates over a mob; unnamed ones (rocks found by deep scan) are
            // the thing itself.
            Entity boxed = rawName.isBlank() ? entity : bodyUnder(client, entity);
            // Same reasoning as the critter branch: keep the matched nameplate's text rather than
            // asking the mob underneath what it is.
            MATCHED.put(boxed.getId(), new Hit(hit, boxed == entity ? "" : rawName.trim()));
            if (hit.announce) {
                maybeAnnounce(client, entity, now);
            }
        }

        ANNOUNCED.keySet().removeIf(id -> client.level.getEntity(id) == null);
    }

    /**
     * The critter this shulker is, from a name stand within reach, remembered for the run once seen.
     * Null while no stand has been close enough to read.
     */
    private static String identifyShulker(Minecraft client, Entity shulker) {
        String known = SHULKER_KINDS.get(shulker.getId());
        if (known != null) {
            return known;
        }
        AABB around = shulker.getBoundingBox().inflate(SHULKER_NAME_RADIUS);
        for (Entity nearby : client.level.getEntities(shulker, around)) {
            String critter = CritterDex.findIn(EntityDataProbe.nameOf(nearby));
            if (critter != null) {
                SHULKER_KINDS.put(shulker.getId(), critter);
                ShinyHunterClient.LOGGER.info("Shulker {} identified as {}", shulker.getId(), critter);
                return critter;
            }
        }
        return null;
    }

    /**
     * The mob a nameplate belongs to: the nearest living, non-stand entity within reach of an
     * armour stand, or the entity itself when it isn't one. Hypixel puts every mob's name on a
     * separate invisible stand, so boxing the match itself drew a tiny cube at head height instead
     * of the mob's hitbox.
     */
    private static Entity bodyUnder(Minecraft client, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
            return entity;
        }
        Entity best = entity;
        double bestDistance = SHULKER_NAME_RADIUS * SHULKER_NAME_RADIUS;
        for (Entity nearby : client.level.getEntities(entity, entity.getBoundingBox().inflate(SHULKER_NAME_RADIUS))) {
            if (nearby instanceof net.minecraft.world.entity.player.Player
                    || nearby instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || !(nearby instanceof net.minecraft.world.entity.LivingEntity)
                    || nearby.getY() > entity.getY() + 0.5) {
                continue; // players aren't critters, and the mob sits under its nameplate, not above
            }
            double d = nearby.distanceToSqr(entity);
            if (d < bestDistance) {
                bestDistance = d;
                best = nearby;
            }
        }
        return best;
    }

    /**
     * True when the game already shows a name over this mob. Hypixel floats its nameplates well
     * above tall mobs — a Hideyho's sits over the cobwebs on its head — so the search is a column
     * over the mob, not a small sphere around it; the small sphere missed it and doubled the name.
     * Text displays count as well as named armour stands.
     */
    private static boolean hasGameNametag(Minecraft client, Entity entity) {
        if (entity.hasCustomName() && entity.isCustomNameVisible()) {
            return true;
        }
        AABB box = entity.getBoundingBox();
        AABB column = new AABB(box.minX - 1.5, box.minY - 1.0, box.minZ - 1.5,
                box.maxX + 1.5, box.maxY + NAMETAG_COLUMN_HEIGHT, box.maxZ + 1.5);
        for (Entity nearby : client.level.getEntities(entity, column)) {
            if (nearby.hasCustomName() && nearby.isCustomNameVisible()
                    && !EntityDataProbe.stripFormatting(nearby.getCustomName().getString()).isBlank()) {
                return true;
            }
            if (nearby instanceof net.minecraft.world.entity.Display.TextDisplay
                    && !EntityDataProbe.deepText(nearby).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** How far above a mob's head its game nameplate can float and still count as its own. */
    private static final double NAMETAG_COLUMN_HEIGHT = 4.5;

    /** Where {@code billboardTextOverMob} puts the label: just over the top of the hitbox. */
    private static Vec3 labelPoint(Entity entity) {
        AABB box = entity.getBoundingBox();
        return new Vec3(box.getCenter().x, box.maxY + 0.5, box.getCenter().z);
    }

    /** The model tint for an outlined mob, or 0 when it isn't outlined. Read by the render mixin. */
    public static int tintFor(int entityId) {
        Integer tint = TINTS.get(entityId);
        return tint == null ? 0 : tint;
    }

    /** White pulled {@link #TINT_STRENGTH} of the way to the outline colour, as opaque ARGB. */
    private static int tintOf(int argb) {
        int r = mix((argb >> 16) & 0xFF), g = mix((argb >> 8) & 0xFF), b = mix(argb & 0xFF);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int channel) {
        return Math.round(255 - (255 - channel) * TINT_STRENGTH);
    }

    private static boolean nearAny(Entity entity, List<Entity> shulkers) {
        for (Entity shulker : shulkers) {
            if (shulker.distanceToSqr(entity) <= SHULKER_NAME_RADIUS * SHULKER_NAME_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static ShinyConfig.Tracker firstMatch(List<ShinyConfig.Tracker> trackers, String haystack) {
        for (ShinyConfig.Tracker tracker : trackers) {
            if (haystack.contains(tracker.token.trim().toUpperCase())) {
                return tracker;
            }
        }
        return null;
    }

    private static void maybeAnnounce(Minecraft client, Entity entity, long now) {
        // Only what's in view is announced, and without a position.
        if (!Sight.canSee(client, entity)) {
            return;
        }
        Long last = ANNOUNCED.get(entity.getId());
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MILLIS) {
            return;
        }
        ANNOUNCED.put(entity.getId(), now);

        String name = EntityDataProbe.nameOf(entity).trim();
        client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §f" + name + " §7spotted"));
    }

    private static List<Entity> findCaptureNameplates(Minecraft client) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (EntityDataProbe.nameOf(entity).toUpperCase().contains(CAPTURE_MARKER)) {
                found.add(entity);
            }
        }
        return found;
    }

    /**
     * True when this match belongs to the capsule capture display rather than a mob in the world.
     * That UI renders a "CAPTURING" nameplate with the mob's name directly beneath it, and the
     * second line matches a tracker exactly like the real mob would.
     */
    private static boolean isCaptureUi(Entity entity, List<Entity> captureNameplates) {
        for (Entity nameplate : captureNameplates) {
            if (nameplate != entity
                    && nameplate.distanceToSqr(entity) <= CAPTURE_RADIUS * CAPTURE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static void emit(Minecraft client, ShinyConfig config) {
        Map<Integer, Integer> tints = new HashMap<>();
        TINTS = tints;
        if (MATCHED.isEmpty()) {
            return;
        }

        try {
            for (Map.Entry<Integer, Hit> entry : MATCHED.entrySet()) {
                Entity entity = client.level.getEntity(entry.getKey());
                if (entity == null || entity.isRemoved()) {
                    continue;
                }

                String label = entry.getValue().label().isEmpty()
                        ? (EntityDataProbe.nameOf(entity).isBlank() ? entry.getValue().tracker().token.trim()
                                : EntityDataProbe.nameOf(entity).trim())
                        : entry.getValue().label();
                int stroke = BeeNestHighlighter.parseColor(entry.getValue().tracker().color, 0xFFFFAA33);
                int fill = (stroke & 0x00FFFFFF) | 0x33000000;
                GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 2.0f, fill);

                // Oversized on purpose: a snug box z-fights with the mob's own faces (shulkers),
                // hides a tiny mob instead of showing it (Gazer), and is buried inside the block a
                // still Duplico is pretending to be.
                double grow = Math.max(0.05, config.highlightBoxGrow);
                double minSize = Math.max(0.0, config.highlightBoxMinSize);
                Gizmos
                        .addGizmo(new EntityBoxGizmo(entry.getKey(), grow, minSize, style))
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
                if (config.highlightTintMobs) {
                    tints.put(entry.getKey(), tintOf(stroke));
                }

                // "default" draws a name only where the game shows none of its own, so a second
                // "Hideonwall" never floats over Hypixel's; "always" draws it regardless, which is
                // what you want for mobs whose nameplate is easy to lose in a crowd.
                String mode = config.highlightLabelMode == null ? "default"
                        : config.highlightLabelMode.trim().toLowerCase();
                boolean wantLabel = config.highlightLabels && !label.isEmpty() && !mode.equals("never")
                        && (mode.equals("always") || !hasGameNametag(client, entity))
                        // Only a label you could actually see from here. Checked by ray rather
                        // than trusted to the depth buffer, which shader packs bypass.
                        && Sight.canSeePoint(client, labelPoint(entity));
                if (wantLabel) {
                    Gizmos.billboardTextOverMob(entity, 0, label, stroke, 0.7f)
                            .persistForMillis(GIZMO_LIFETIME_MILLIS);
                }
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't emit entity highlights", e);
        }
    }

    public static int highlightedCount() {
        return MATCHED.size();
    }
}
