package com.shinyhunter;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pulls every scrap of text the client actually holds for an entity.
 *
 * <p><b>What "NBT" means on the client.</b> A client never receives an entity's server-side NBT —
 * that data lives on the server and is only ever exposed through {@code /data}, which needs op.
 * What the client <i>does</i> get is:
 *
 * <ul>
 *   <li>the entity's <b>synced data</b> ({@link SynchedEntityData}) — the tracked fields the server
 *       pushes so the client can render the thing. Custom name lives here <i>regardless of whether
 *       the nameplate is set to visible</i>, which is why a hidden nametag is readable at all; so
 *       does the {@code ItemStack} an item-display entity is showing, which is otherwise behind a
 *       private accessor.</li>
 *   <li>the <b>item stacks</b> on the entity — equipment slots, and the displayed stack above. Those
 *       carry real data components client-side, including {@code minecraft:custom_data}, the
 *       compound tag Hypixel stuffs its {@code ExtraAttributes} into. That component is genuine NBT
 *       and is the one place a "hidden" identity realistically survives the trip to the client.</li>
 * </ul>
 *
 * <p>Everything below reads only those two sources. If Hypixel doesn't send a detail, nothing here
 * (or in any other client mod) can recover it.
 */
public final class EntityDataProbe {

    private EntityDataProbe() {
    }

    // ------------------------------------------------------------------ text extraction

    /** Drops legacy {@code §x} colour codes so they can't split the keyword we're matching on. */
    public static String stripFormatting(String text) {
        if (text == null || text.indexOf('§') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++; // skip the code character too
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The nameplate text, formatting stripped. Cheap — this is the every-scan path.
     *
     * <p>Reads the custom name even when the server has marked it invisible: {@code getCustomName()}
     * returns the synced field, and nameplate visibility is a separate flag the client only consults
     * at render time.
     */
    public static String nameOf(Entity entity) {
        Component custom = entity.getCustomName();
        return stripFormatting(custom != null ? custom.getString() : entity.getName().getString());
    }

    /**
     * Every piece of client-visible text on the entity, flattened into one buffer for substring
     * matching: nameplate, all non-default synced fields, and all item stacks with their names, lore
     * and custom-data NBT.
     *
     * <p>Deliberately generic — walking the synced fields by value type rather than by entity class
     * means item displays, text displays and whatever Hypixel invents next are all covered without
     * per-type code or an accessor mixin.
     */
    public static String deepText(Entity entity) {
        StringBuilder out = new StringBuilder(128);
        out.append(nameOf(entity));

        try {
            // Returns null (not an empty list) when every tracked field still holds its default.
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            if (values != null) {
                for (SynchedEntityData.DataValue<?> value : values) {
                    appendValue(out, value.value());
                }
            }

            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    appendStack(out, living.getItemBySlot(slot));
                }
            }
        } catch (Exception e) {
            // One malformed entity must not take down the scan loop or the client. Whatever text we
            // managed to collect before the failure is still worth matching against.
            ShinyHunterClient.LOGGER.debug("Couldn't fully probe entity {}", entity.getId(), e);
        }

        return out.toString();
    }

    private static void appendValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> {
            }
            case Component component -> append(out, component.getString());
            case String string -> append(out, string);
            case ItemStack stack -> appendStack(out, stack);
            // Custom name is tracked as an Optional<Component>, so unwrap one level.
            case Optional<?> optional -> optional.ifPresent(inner -> appendValue(out, inner));
            default -> {
            }
        }
    }

    /**
     * Text of just the item stacks on an entity — displayed stacks and equipment, no names. Cheaper
     * than {@link #deepText} when the thing being matched is known to live on an item.
     */
    public static String itemText(Entity entity) {
        StringBuilder out = new StringBuilder(64);
        try {
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            if (values != null) {
                for (SynchedEntityData.DataValue<?> value : values) {
                    if (value.value() instanceof ItemStack stack) {
                        appendStack(out, stack);
                    }
                }
            }
            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    appendStack(out, living.getItemBySlot(slot));
                }
            }
        } catch (Exception e) {
            ShinyHunterClient.LOGGER.debug("Couldn't probe items on entity {}", entity.getId(), e);
        }
        return out.toString();
    }

    private static void appendStack(StringBuilder out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        // The registry id and the item_model component are where a server's custom item identity
        // actually lives — the visible name is often just decoration, or absent entirely.
        append(out, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null) {
            append(out, model.toString());
        }

        append(out, stack.getHoverName().getString());

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                append(out, line.getString());
            }
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            // toString() hands back the backing CompoundTag's own text form without copying the tag,
            // which matters when this runs across every entity in a packed lobby.
            append(out, customData.toString());
        }
    }

    private static void append(StringBuilder out, String text) {
        if (text != null && !text.isEmpty()) {
            out.append(' ').append(stripFormatting(text));
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * A readable breakdown of everything above, for {@code /shinyhunter dump}. This is the tool for
     * answering "where does this mob's real identity actually live?" — run it next to something whose
     * name is hidden and read back what the client was actually handed.
     */
    public static List<String> describe(Entity entity, double distance) {
        List<String> lines = new ArrayList<>();
        lines.add("#" + entity.getId() + "  " + EntityType.getKey(entity.getType())
                + "  " + String.format("%.1f", distance) + "m  at "
                + (int) entity.position().x + ", " + (int) entity.position().y + ", "
                + (int) entity.position().z);
        lines.add("   name: " + nameOf(entity)
                + (entity.getCustomName() != null
                ? " (custom, nameplate " + (entity.isCustomNameVisible() ? "visible" : "hidden") + ")"
                : " (type name, no custom name set)"));

        try {
            List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
            if (values == null || values.isEmpty()) {
                lines.add("   synced data: <all fields at default — server sent nothing extra>");
            } else {
                for (SynchedEntityData.DataValue<?> value : values) {
                    describeValue(lines, "   data[" + value.id() + "]", value.value());
                }
            }

            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    ItemStack stack = living.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        describeStack(lines, "   " + slot.getName(), stack);
                    }
                }
            }
        } catch (Exception e) {
            lines.add("   <probe failed: " + e + ">");
        }

        return lines;
    }

    private static void describeValue(List<String> lines, String label, Object value) {
        switch (value) {
            case null -> {
            }
            case Component component -> lines.add(label + " text: " + component.getString());
            case String string -> lines.add(label + " string: " + string);
            case ItemStack stack -> describeStack(lines, label, stack);
            case Optional<?> optional -> optional.ifPresent(inner -> describeValue(lines, label, inner));
            default -> {
            }
        }
    }

    private static void describeStack(List<String> lines, String label, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        lines.add(label + " item: " + stack.getHoverName().getString()
                + "  [" + BuiltInRegistries.ITEM.getKey(stack.getItem()) + "]");

        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null) {
            lines.add(label + "   item_model: " + model);
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(label + "   lore: " + line.getString());
            }
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            lines.add(label + "   nbt: " + customData);
        }
    }
}
