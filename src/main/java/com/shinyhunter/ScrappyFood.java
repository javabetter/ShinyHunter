package com.shinyhunter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which food a Scrappy wants. Each Scrappy likes one of Wholesale Wheat, Flavor-packed Fish or Lush
 * Lily Pads, and nothing on screen says which, so this works from what the server says when one is fed:
 * <ul>
 *   <li>"The Scrappy happily eats the X" (or "cautiously started eating", "ate the X and came out of
 *       its shell", "reaches out its paw for more X"): it wants X;</li>
 *   <li>"This particular Scrappy doesn't seem to care for this type of food...": not the food in your
 *       hand. Two of those and the third food is the one.</li>
 * </ul>
 * Each fed Scrappy gets a label for the rest of the run.
 *
 * <p><b>Learning.</b> Each feeding also records what that Scrappy is made of: skins, displayed items
 * and their models, entity types. If one look always turns out to want the same food, unfed Scrappies
 * with that look get a "likely" label too. The record is kept in
 * {@code config/shinyhunter-scrappy.json}, and each feeding's look is logged so it can be checked.
 */
public final class ScrappyFood {

    public static final List<String> FOODS = List.of("Wholesale Wheat", "Flavor-packed Fish", "Lush Lily Pad");

    private static final Pattern LIKES = Pattern.compile(
            "^The Scrappy (?:happily eats the|cautiously started eating the|ate the|reaches out its paw for more) (.+?)"
                    + "(?: and came out of its shell)?[.!](?: \\(\\d+\\))?$");
    private static final Pattern REJECTS = Pattern.compile(
            "^This particular Scrappy doesn't seem to care for this type of food");

    /** How long after a click the server's answer still belongs to it. */
    private static final long CLICK_WINDOW_MILLIS = 3_000L;
    /** Two feedings within this distance are the same Scrappy. */
    private static final double SAME_SCRAPPY = 2.0;
    /** The entities this close to the clicked one make up its look. */
    private static final double LOOK_RADIUS = 1.5;
    /** A look needs this many agreeing feedings, and none disagreeing, before it predicts. */
    private static final int CONFIDENT = 2;
    private static final int LABEL_RANGE = 48;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** One Scrappy fed this run. */
    private static final class Known {
        Vec3 pos;
        String look;
        String wants;
        final Set<String> not = new LinkedHashSet<>();

        String verdict() {
            if (wants != null) {
                return wants;
            }
            if (not.size() == FOODS.size() - 1) {
                for (String food : FOODS) {
                    if (!not.contains(food)) {
                        return food;
                    }
                }
            }
            return null;
        }
    }

    private static final class Data {
        String note = "Scrappy looks (skins, displayed items, entity types) and the food each one turned out to want."
                + " Written by Shiny Hunter.";
        Map<String, Map<String, Integer>> looks = new LinkedHashMap<>();
    }

    private static final List<Known> KNOWN = new ArrayList<>();
    private static Data data;
    private static Object level;
    private static Entity clicked;
    private static String heldFood;
    private static long clickedAt;
    private static int tickCounter;
    /** Unfed Scrappies with a confident look, refreshed each second: position -> food. */
    private static final Map<Vec3, String> LIKELY = new LinkedHashMap<>();

    private ScrappyFood() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (world.isClientSide() && player == client.player && ShinyConfig.get().scrappyFood) {
                clicked = entity;
                heldFood = food(EntityDataProbe.stripFormatting(player.getItemInHand(hand).getHoverName().getString()));
                clickedAt = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chat(EntityDataProbe.stripFormatting(message.getString()).trim());
            }
        });
    }

    /** The food a text names, or null. Matched on a key word: Hypixel says "Lily Pad" and "Lily Pads". */
    static String food(String text) {
        String t = text.toLowerCase();
        if (t.contains("wheat")) {
            return FOODS.get(0);
        }
        if (t.contains("fish")) {
            return FOODS.get(1);
        }
        if (t.contains("lily")) {
            return FOODS.get(2);
        }
        return null;
    }

    private static void chat(String text) {
        if (!ShinyConfig.get().scrappyFood || clicked == null
                || System.currentTimeMillis() - clickedAt > CLICK_WINDOW_MILLIS) {
            return;
        }
        Matcher likes = LIKES.matcher(text);
        if (likes.find()) {
            String food = food(likes.group(1));
            if (food != null) {
                learn(food, true);
            }
        } else if (REJECTS.matcher(text).find() && heldFood != null) {
            learn(heldFood, false);
        }
    }

    private static void learn(String food, boolean wanted) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Vec3 at = clicked.position();
        Known known = null;
        for (Known candidate : KNOWN) {
            if (candidate.pos.distanceTo(at) <= SAME_SCRAPPY) {
                known = candidate;
            }
        }
        if (known == null) {
            known = new Known();
            known.look = look(client, clicked);
            KNOWN.add(known);
        }
        known.pos = at;
        String before = known.verdict();
        if (wanted) {
            known.wants = food;
        } else {
            known.not.add(food);
        }
        String verdict = known.verdict();
        ShinyHunterClient.LOGGER.info("Scrappy {} {} (verdict {}) — look: {}", wanted ? "wants" : "doesn't want",
                food, verdict, known.look);
        // A look is credited once, with its final answer: the first time this Scrappy's food is known.
        if (verdict != null && before == null) {
            load();
            data.looks.computeIfAbsent(known.look, k -> new LinkedHashMap<>()).merge(verdict, 1, Integer::sum);
            save();
        }
    }

    /**
     * What a Scrappy is made of, as text: every entity near the clicked one, by type, with its skins,
     * displayed or worn items and their models. Positions and ids are left out, so the same kind of
     * Scrappy gives the same look every time.
     */
    static String look(Minecraft client, Entity entity) {
        Set<String> parts = new TreeSet<>();
        AABB box = entity.getBoundingBox().inflate(LOOK_RADIUS);
        List<Entity> near = new ArrayList<>(client.level.getEntities(entity, box));
        near.add(entity);
        for (Entity part : near) {
            if (part == client.player || part.getId() < 0) {
                continue;
            }
            StringBuilder out = new StringBuilder(EntityType.getKey(part.getType()).getPath());
            for (String hash : TextureProbe.hashesOf(part)) {
                out.append(" skin=").append(hash, 0, Math.min(16, hash.length()));
            }
            if (part instanceof Display.ItemDisplay display) {
                out.append(" item=").append(item(display.getItemStack()));
            }
            if (part instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                    ItemStack stack = living.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        out.append(' ').append(slot.getName()).append('=').append(item(stack));
                    }
                }
            }
            parts.add(out.toString());
        }
        return String.join(" | ", parts);
    }

    private static String item(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        var model = stack.get(DataComponents.ITEM_MODEL);
        return model == null ? id : id + "@" + model;
    }

    /** The food a look has always turned out to want, once that's happened often enough. */
    private static String predicted(String look) {
        load();
        Map<String, Integer> counts = data.looks.get(look);
        if (counts == null || counts.size() != 1) {
            return null;
        }
        Map.Entry<String, Integer> only = counts.entrySet().iterator().next();
        return only.getValue() >= CONFIDENT ? only.getKey() : null;
    }

    // ------------------------------------------------------------------ labels

    public static void onClientTick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            KNOWN.clear();
            LIKELY.clear();
            clicked = null;
        }
        if (!ShinyConfig.get().scrappyFood || client.level == null || client.player == null
                || !SafariBiomes.playerInSafari(client)) {
            return;
        }
        if (++tickCounter >= 20) {
            tickCounter = 0;
            findLikely(client);
        }
        for (Known known : KNOWN) {
            String verdict = known.verdict();
            if (verdict != null) {
                label(known.pos, "Wants " + verdict, 0xFF55FF55);
            } else if (!known.not.isEmpty()) {
                label(known.pos, "Not " + String.join(", ", known.not), 0xFFFF7777);
            }
        }
        for (Map.Entry<Vec3, String> likely : LIKELY.entrySet()) {
            label(likely.getKey(), "Likely " + likely.getValue(), 0xFFFFFF55);
        }
    }

    /** Scrappies not fed yet whose look has a confident answer. */
    private static void findLikely(Minecraft client) {
        LIKELY.clear();
        load();
        if (data.looks.isEmpty()) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!"Scrappy".equals(CritterDex.findIn(EntityDataProbe.nameOf(entity)))
                    || client.player.distanceToSqr(entity) > LABEL_RANGE * LABEL_RANGE) {
                continue;
            }
            Vec3 at = entity.position();
            if (KNOWN.stream().anyMatch(known -> known.pos.distanceTo(at) <= SAME_SCRAPPY)) {
                continue;
            }
            // The nametag rides on a stand above the body; the look is the body and its parts.
            Entity body = entity;
            double best = Double.MAX_VALUE;
            for (Entity near : client.level.getEntities(entity, entity.getBoundingBox().inflate(1.0, 3.0, 1.0))) {
                double d = near.distanceToSqr(entity);
                if (!near.getType().equals(entity.getType()) && d < best) {
                    best = d;
                    body = near;
                }
            }
            String food = predicted(look(client, body));
            if (food != null) {
                LIKELY.put(at, food);
            }
        }
    }

    private static void label(Vec3 at, String text, int colour) {
        if (Minecraft.getInstance().player.position().distanceToSqr(at) > LABEL_RANGE * LABEL_RANGE) {
            return;
        }
        try {
            var label = Gizmos.billboardTextOverBlock(text, BlockPos.containing(at.add(0, 1.0, 0)), 0, colour, 0.8f)
                    .persistForMillis(120);
            Shaders.place(label, false);
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw a Scrappy label", e);
        }
    }

    // ------------------------------------------------------------------ command & storage

    /** {@code /shiny scrappy}: what's known this run, and what the looks have taught so far. */
    public static List<String> status() {
        load();
        List<String> lines = new ArrayList<>();
        lines.add("§b§lScrappy food §7— " + (ShinyConfig.get().scrappyFood ? "§aon" : "§coff")
                + "§7, " + KNOWN.size() + " fed this run, " + data.looks.size() + " look(s) learned");
        for (Map.Entry<String, Map<String, Integer>> look : data.looks.entrySet()) {
            String verdict = look.getValue().size() == 1 ? "§a" : "§c";
            lines.add("  " + verdict + look.getValue() + " §8" + (look.getKey().length() > 70
                    ? look.getKey().substring(0, 70) + "…" : look.getKey()));
        }
        lines.add("§8" + path());
        return lines;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("shinyhunter-scrappy.json");
    }

    private static void load() {
        if (data != null) {
            return;
        }
        data = new Data();
        if (!Files.exists(path())) {
            return;
        }
        try {
            Data read = GSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), Data.class);
            if (read != null && read.looks != null) {
                data = read;
            }
        } catch (IOException | RuntimeException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't read the Scrappy food record — starting a new one", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ShinyHunterClient.LOGGER.warn("Couldn't save the Scrappy food record", e);
        }
    }
}
