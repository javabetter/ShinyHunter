package com.shinyhunter;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pulls skin texture identities off an entity, for telling a sparkling critter from a plain one
 * before it has a name tag.
 *
 * <p>Sparkling critters that don't use a vanilla model have their own textures — a sparkling
 * Hideyho is a differently skinned Hideyho — and that's visible to the client from the moment the
 * entity spawns, well before the SPARKLING name appears on interaction. The texture is a Mojang
 * skin, identified by the hash at the end of its URL, and that hash is what this returns.
 *
 * <p>Three places a skin can live: a player-model NPC's own profile, a player head worn as a
 * helmet, and a player head shown by an item display. All three are checked; the config decides
 * which hashes mean "sparkling".
 */
public final class TextureProbe {

    private TextureProbe() {
    }

    /** Every skin texture hash this entity carries. Empty for anything without a custom skin. */
    public static Set<String> hashesOf(Entity entity) {
        Set<String> hashes = new LinkedHashSet<>();
        try {
            if (entity instanceof Player player) {
                collect(player.getGameProfile(), hashes);
            }
            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    collect(living.getItemBySlot(slot), hashes);
                }
            }
            // A player head shown by an item display: its skin is base64 inside the profile, so it
            // has to be decoded like a worn head — it never appears as a plain URL in the data.
            if (entity instanceof net.minecraft.world.entity.Display.ItemDisplay display) {
                collect(display.getItemStack(), hashes);
            }
            // Item displays and anything else that syncs a stack: EntityDataProbe already walks
            // the synced fields, so the hash appears in its text; scan that for URL hashes too.
            for (String hash : hashesIn(EntityDataProbe.deepText(entity))) {
                hashes.add(hash);
            }
        } catch (RuntimeException e) {
            ShinyHunterClient.LOGGER.debug("Texture probe failed on entity {}", entity.getId(), e);
        }
        return hashes;
    }

    private static void collect(ItemStack stack, Set<String> into) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);
        if (profile != null) {
            collect(profile.partialProfile(), into);
        }
    }

    private static void collect(GameProfile profile, Set<String> into) {
        if (profile == null) {
            return;
        }
        for (Property property : profile.properties().get("textures")) {
            String value = property.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            // The property is base64 JSON with the skin URL inside; the hash is the URL's last
            // path segment. Decoding can fail on a malformed property — treat that as "no skin".
            String json;
            try {
                json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                continue;
            }
            into.addAll(hashesIn(json));
        }
    }

    /** Texture hashes in any text — the 64-hex tail of a textures.minecraft.net URL. */
    static Set<String> hashesIn(String text) {
        Set<String> hashes = new LinkedHashSet<>();
        if (text == null) {
            return hashes;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf("texture/", from);
            if (at < 0) {
                break;
            }
            int start = at + "texture/".length();
            int end = start;
            while (end < text.length() && Character.digit(text.charAt(end), 16) >= 0) {
                end++;
            }
            if (end - start >= 32) {
                hashes.add(text.substring(start, end).toLowerCase());
            }
            from = end;
        }
        return hashes;
    }
}
