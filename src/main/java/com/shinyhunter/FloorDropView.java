package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shows each predicted floor drop ({@link FloorDropLog}) still on the ground: a label saying what it
 * gives, a box around the drop in the label's colour, and a stand-in dropped item with an outline in
 * the drop type's colour.
 *
 * <p>The stand-ins are client-only {@link ItemEntity}s with negative ids, added to the client level
 * and removed again; the server never hears of them. They can't be targeted (an item entity isn't
 * pickable), and the render mixins give them their colour and let them draw from far away.
 *
 * <p>Which drops show: the type's own switch, the "hide after picking up N this run" count, the
 * range, and optionally only the biome you're in.
 */
public final class FloorDropView {

    /** Group key -> its stand-in item. */
    private static final Map<Integer, ItemEntity> FAKES = new HashMap<>();
    /** Stand-in entity id -> outline colour, read by the render mixin. */
    private static final Map<Integer, Integer> OUTLINES = new HashMap<>();
    private static final int GIZMO_LIFETIME_MILLIS = 120;
    private static int nextId = -1_000_000;
    private static Object level;

    private FloorDropView() {
    }

    public static boolean isFake(int entityId) {
        return OUTLINES.containsKey(entityId);
    }

    /** The outline colour for a stand-in, or 0 for anything else. */
    public static int outlineFor(int entityId) {
        Integer colour = OUTLINES.get(entityId);
        return colour == null ? 0 : colour;
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (client.level != level) {
            // A new level dropped every client entity already; just forget them.
            FAKES.clear();
            OUTLINES.clear();
            level = client.level;
        }
        if (client.level == null || client.player == null || !config.floorDropPredict || !FloorDropLog.active()) {
            clearFakes(client);
            return;
        }
        double range = config.floorDropRange;
        String myBiome = FloorDropLog.biomeAt(client.player.getX(), client.player.getZ());
        Set<Integer> wanted = new HashSet<>();
        int labelOverride = "type".equalsIgnoreCase(config.floorDropLabelColour.trim())
                ? 0 : BeeNestHighlighter.parseColor(config.floorDropLabelColour, 0xFFFFFFFF);
        for (FloorDropLog.OnGround drop : FloorDropLog.onGround()) {
            FloorDropType type = FloorDropType.of(drop.predicted());
            if (!type.shown(config)) {
                continue;
            }
            int hideAfter = type.hideAfter(config);
            if (hideAfter > 0 && FloorDropLog.pickedUp(type.label) >= hideAfter) {
                continue;
            }
            if (config.floorDropOnlyMyBiome && !FloorDropLog.biomeAt(drop.x(), drop.z()).equals(myBiome)) {
                continue;
            }
            Vec3 at = new Vec3(drop.x(), drop.y() + 0.25, drop.z());
            if (client.player.position().distanceToSqr(at) > range * range) {
                continue;
            }
            int colour = type.colour(config);
            wanted.add(drop.key());
            if (config.floorDropFakeItems) {
                fake(client, drop.key(), type, at, colour, config.floorDropFakeOutline);
            }
            int labelColour = labelOverride != 0 ? labelOverride : colour;
            label(type.label, at, labelColour, config);
            if (config.floorDropBoxes) {
                box(drop, labelColour, config);
            }
        }
        // Stand-ins whose drop is gone, hidden or out of range.
        for (Integer key : new HashSet<>(FAKES.keySet())) {
            if (!wanted.contains(key) || !config.floorDropFakeItems) {
                remove(client, key);
            }
        }
    }

    private static void label(String text, Vec3 at, int colour, ShinyConfig config) {
        try {
            var label = Gizmos.billboardTextOverBlock(text, BlockPos.containing(at), 0, colour,
                            (float) config.floorDropLabelScale)
                    .persistForMillis(GIZMO_LIFETIME_MILLIS);
            Shaders.place(label, config.floorDropThroughWalls);
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw a floor drop label", e);
        }
    }

    /** The box the string-cluster highlighter used to draw, now around each predicted drop. */
    private static void box(FloorDropLog.OnGround drop, int colour, ShinyConfig config) {
        try {
            int fill = (colour & 0x00FFFFFF) | 0x33000000;
            var box = Gizmos.cuboid(new net.minecraft.world.phys.AABB(drop.x() - 0.4, drop.y() - 0.4, drop.z() - 0.4,
                                    drop.x() + 0.4, drop.y() + 0.4, drop.z() + 0.4),
                            net.minecraft.gizmos.GizmoStyle.strokeAndFill(colour, 2.0f, fill))
                    .persistForMillis(GIZMO_LIFETIME_MILLIS);
            Shaders.place(box, config.floorDropThroughWalls);
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw a floor drop box", e);
        }
    }

    private static void fake(Minecraft client, int key, FloorDropType type, Vec3 at, int colour, boolean outline) {
        ItemEntity existing = FAKES.get(key);
        if (existing != null && !existing.isRemoved()) {
            existing.setGlowingTag(outline);
            OUTLINES.put(existing.getId(), colour);
            return;
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(type.itemId));
        ItemEntity entity = new ItemEntity(client.level, at.x, at.y, at.z, new ItemStack(item));
        entity.setId(nextId--);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setNoGravity(true);
        entity.setNeverPickUp();
        entity.setUnlimitedLifetime();
        entity.setGlowingTag(outline);
        OUTLINES.put(entity.getId(), colour);
        client.level.addEntity(entity);
        FAKES.put(key, entity);
    }

    private static void remove(Minecraft client, int key) {
        ItemEntity entity = FAKES.remove(key);
        if (entity == null) {
            return;
        }
        OUTLINES.remove(entity.getId());
        if (client.level != null && !entity.isRemoved()) {
            client.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private static void clearFakes(Minecraft client) {
        for (Integer key : new HashSet<>(FAKES.keySet())) {
            remove(client, key);
        }
    }
}
