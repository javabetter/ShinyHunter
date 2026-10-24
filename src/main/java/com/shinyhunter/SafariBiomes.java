package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Where the Critter Safari is, from the biomes Hypixel sends with the world (what F3 shows): the four
 * critter biomes are {@code hypixel:forest}, {@code hypixel:cavern}, {@code hypixel:icy} and
 * {@code hypixel:haunted}, around a middle area of {@code hypixel:torrhus}.
 *
 * <p>Unlike the sidebar zone row and the tab list's area, which players and Hypixel's own layout
 * changes can hide or reword, the biome is part of the world data and always there.
 */
public final class SafariBiomes {

    private static final Set<String> CRITTER_BIOMES = Set.of("forest", "cavern", "icy", "haunted");

    /** How far round the player to look for a critter biome: the middle area sits inside them. */
    private static final int REACH = 48;

    private static long checkedAt;
    private static boolean lastAnswer;

    private SafariBiomes() {
    }

    /** The path of the {@code hypixel:} biome at a position ("icy"), or null for any other biome. */
    public static String hypixelBiome(Level level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        var key = level.getBiome(pos).unwrapKey();
        if (key.isEmpty()) {
            return null;
        }
        Identifier id = key.get().identifier();
        return "hypixel".equals(id.getNamespace()) ? id.getPath() : null;
    }

    /** True when this entity stands in one of the Safari's four critter biomes. */
    public static boolean inCritterBiome(Entity entity) {
        String biome = hypixelBiome(entity.level(), entity.blockPosition());
        return biome != null && CRITTER_BIOMES.contains(biome);
    }

    /**
     * True in the Critter Safari: a critter biome under the player or within {@value #REACH} blocks,
     * which covers the middle area too. A tab list naming a different island overrules it, so a
     * {@code hypixel:forest} elsewhere can't be mistaken for the Safari. Re-checked twice a second.
     */
    public static boolean playerInSafari(Minecraft client) {
        if (client.level == null || client.player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt < 500) {
            return lastAnswer;
        }
        checkedAt = now;
        lastAnswer = compute(client);
        return lastAnswer;
    }

    private static boolean compute(Minecraft client) {
        String island = SkyblockSidebar.islandName(client);
        if (island != null && !island.toLowerCase().contains("safari")) {
            return false;
        }
        BlockPos at = client.player.blockPosition();
        for (int dx = -REACH; dx <= REACH; dx += REACH) {
            for (int dz = -REACH; dz <= REACH; dz += REACH) {
                String biome = hypixelBiome(client.level, at.offset(dx, 0, dz));
                if (biome != null && CRITTER_BIOMES.contains(biome)) {
                    return true;
                }
            }
        }
        return false;
    }
}
