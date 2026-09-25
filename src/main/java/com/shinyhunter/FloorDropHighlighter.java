package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights floor drops.
 *
 * <p><b>What a floor drop actually is.</b> Logging the entities around one showed no emitter, no
 * armour stand and no marker mob — just a tight cluster of {@code minecraft:item_display} entities,
 * each displaying {@code minecraft:string}, all within a fraction of a block of each other. Three of
 * them in the samples seen so far. The earlier "emitter plus a string-holding armour stand" rule was
 * built on a token ({@code crop_growth_emitter}) that does not appear anywhere in Minecraft 26.1.2
 * or in the data the server sends, so it could never have matched.
 *
 * <p>The cluster is the signal: a lone string display could be scenery, but several stacked on one
 * spot is a drop. Grouping them also means one box per drop instead of three overlapping ones.
 *
 * <p>Gizmo-based, so like the other highlighters it must be driven from {@code END_CLIENT_TICK} —
 * see {@link BeeNestHighlighter} for why.
 */
public final class FloorDropHighlighter {

    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Ids of the parts found by their item data rather than by being display-shaped. */
    private static final java.util.Set<Integer> ITEM_MATCHED = new java.util.HashSet<>();

    private static boolean containsItemMatch(List<Entity> group) {
        for (Entity entity : group) {
            if (ITEM_MATCHED.contains(entity.getId())) {
                return true;
            }
        }
        return false;
    }

    /** Entity ids grouped per drop, so each drop draws a single box around the whole cluster. */
    private static final List<List<Integer>> CLUSTERS = new ArrayList<>();

    private static int tickCounter;

    private FloorDropHighlighter() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        if (!config.highlightFloorDrops
                || client.level == null
                || client.player == null
                || (config.onlyInSkyblock && !SkyblockSidebar.inSkyblock(client))) {
            CLUSTERS.clear();
            tickCounter = RESCAN_INTERVAL_TICKS;
            return;
        }

        if (++tickCounter >= RESCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            rescan(client, config);
        }
        emit(client, config);
    }

    // ------------------------------------------------------------------ finding

    private static void rescan(Minecraft client, ShinyConfig config) {
        CLUSTERS.clear();

        List<Entity> parts = collectParts(client, config);
        boolean[] taken = new boolean[parts.size()];
        double radiusSq = config.floorDropClusterRadius * config.floorDropClusterRadius;

        for (int i = 0; i < parts.size(); i++) {
            if (taken[i]) {
                continue;
            }
            List<Entity> group = new ArrayList<>();
            group.add(parts.get(i));
            taken[i] = true;

            // Repeat until nothing new joins, so a chain of displays each close to the previous one
            // still ends up as a single drop rather than several.
            boolean grew = true;
            while (grew) {
                grew = false;
                for (int j = 0; j < parts.size(); j++) {
                    if (taken[j]) {
                        continue;
                    }
                    for (int k = 0; k < group.size(); k++) {
                        if (group.get(k).distanceToSqr(parts.get(j)) <= radiusSq) {
                            group.add(parts.get(j));
                            taken[j] = true;
                            grew = true;
                            break;
                        }
                    }
                }
            }

            // A cluster that includes a real item match is trusted at the usual size; one made
            // only of item displays has to be bigger, because "a few stationary displays" describes
            // plenty of scenery as well as a drop.
            int needed = containsItemMatch(group)
                    ? config.floorDropClusterSize
                    : Math.max(config.floorDropClusterSize, config.floorDropDisplayCount);
            if (group.size() >= needed) {
                List<Integer> ids = new ArrayList<>(group.size());
                for (Entity entity : group) {
                    ids.add(entity.getId());
                }
                CLUSTERS.add(ids);
            }
        }
    }

    /** Nearby entities displaying the drop's marker item, plus item displays when that's on. */
    private static List<Entity> collectParts(Minecraft client, ShinyConfig config) {
        List<Entity> parts = new ArrayList<>();
        ITEM_MATCHED.clear();
        String token = config.floorDropItemToken.trim().toUpperCase();

        double radiusSq = (double) config.floorDropScanRadius * config.floorDropScanRadius;
        double displayRadius = config.floorDropDisplayRadius > 0
                ? config.floorDropDisplayRadius
                : config.floorDropScanRadius;
        double displayRadiusSq = displayRadius * displayRadius;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }
            double distanceSq = client.player.distanceToSqr(entity);

            // The item-data route: the registry id is the identity, and searching free text for
            // something as common as "string" would match display names and lore everywhere.
            if (!token.isEmpty() && distanceSq <= radiusSq
                    && EntityDataProbe.itemText(entity).toUpperCase().contains(token)) {
                parts.add(entity);
                ITEM_MATCHED.add(entity.getId());
                continue;
            }

            // The shape route: an item display that isn't moving. Reaches much further, because it
            // asks nothing of the item data — which is exactly why it's off by default and counted
            // rather than trusted (see isDropShaped).
            if (config.floorDropItemDisplays && distanceSq <= displayRadiusSq && isStationaryDisplay(entity)) {
                parts.add(entity);
            }
        }
        return parts;
    }

    /**
     * A stationary item display. Movement is checked because dropped-item displays elsewhere in
     * Skyblock bob and drift, while a floor drop's parts sit still.
     */
    private static boolean isStationaryDisplay(Entity entity) {
        return entity instanceof net.minecraft.world.entity.Display.ItemDisplay
                && entity.getDeltaMovement().lengthSqr() < 0.0001;
    }

    // ------------------------------------------------------------------ drawing

    private static void emit(Minecraft client, ShinyConfig config) {
        if (CLUSTERS.isEmpty()) {
            return;
        }

        int stroke = BeeNestHighlighter.parseColor(config.floorDropColor, 0xFFFF55FF);
        int fill = (stroke & 0x00FFFFFF) | 0x33000000;
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 2.0f, fill);

        try {
            for (List<Integer> cluster : CLUSTERS) {
                AABB box = boundsOf(client, cluster);
                if (box == null) {
                    continue;
                }
                // Item displays have a near-zero bounding box, so pad it into something you can see.
                // Drops don't move, so a plain fixed cuboid is right here — the whole cluster gets
                // one box rather than a tracking gizmo per part.
                Gizmos
                        .cuboid(box.inflate(0.35), style)
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't emit floor drop highlights", e);
        }
    }

    /** Union of the cluster's bounding boxes, or null if every member has despawned. */
    private static AABB boundsOf(Minecraft client, List<Integer> cluster) {
        AABB box = null;
        for (Integer id : cluster) {
            Entity entity = client.level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            AABB part = entity.getBoundingBox();
            box = box == null ? part : box.minmax(part);
        }
        return box;
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Reports what the detector can see, and logs the data of everything nearby when it finds
     * nothing — which is how the real shape of a floor drop was worked out in the first place.
     */
    public static List<String> describeCandidates(Minecraft client, ShinyConfig config) {
        List<String> report = new ArrayList<>();
        if (client.level == null || client.player == null) {
            report.add("§cNot in a world.");
            return report;
        }

        List<Entity> parts = collectParts(client, config);
        report.add("§7Marker item §f\"" + config.floorDropItemToken + "\"§7: §f" + parts.size()
                + "§7 nearby · clusters of §f" + config.floorDropClusterSize
                + "§7+ within §f" + config.floorDropClusterRadius + "m§7: §f" + CLUSTERS.size());

        for (List<Integer> cluster : CLUSTERS) {
            AABB box = boundsOf(client, cluster);
            if (box != null) {
                report.add("§7 drop · §f" + cluster.size() + " parts §7at "
                        + (int) box.getCenter().x + ", " + (int) box.getCenter().y + ", "
                        + (int) box.getCenter().z);
            }
        }

        if (parts.isEmpty()) {
            int logged = logNearbyData(client);
            report.add("§eNothing matched.§7 Logged §f" + logged
                    + "§7 nearby entities to latest.log.");
        }
        return report;
    }

    private static int logNearbyData(Minecraft client) {
        int logged = 0;
        ShinyHunterClient.LOGGER.info("===== /shinyhunter drops where — nearby entity data =====");
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || client.player.distanceToSqr(entity) > 64.0) {
                continue;
            }
            String deep = EntityDataProbe.deepText(entity).trim();
            ShinyHunterClient.LOGGER.info("#{} {} @{}m  {}",
                    entity.getId(),
                    EntityType.getKey(entity.getType()),
                    Math.round(client.player.distanceTo(entity)),
                    deep.isEmpty() ? "<no text data>" : deep);
            logged++;
        }
        ShinyHunterClient.LOGGER.info("===== end =====");
        return logged;
    }

    public static int highlightedCount() {
        return CLUSTERS.size();
    }
}
