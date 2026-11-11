package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
 * Which food each Scrappy wants: Wholesale Wheat, Flavor-packed Fish or Lush Lily Pads.
 *
 * <p><b>From the spawn order.</b> An instance has three Scrappies, each an armadillo with its nametag
 * on the next entity id, and their ids give the food away (found over four runs): sorted by
 * armadillo id, the lowest wants Wheat, the middle one Fish and the highest Lily Pads. The three sit
 * three ids apart, so with only two of them loaded the gap still tells which is which: six apart
 * means lowest and highest. Every Scrappy seen in an instance is remembered, so one that unloads
 * keeps its place.
 *
 * <p><b>From feeding.</b> What the server says when one is fed ("happily eats the X", "doesn't seem to
 * care for this type of food") overrides the prediction for that Scrappy, and is checked against it:
 * {@code /shiny scrappy} shows how often the spawn order was right.
 */
public final class ScrappyFood {

    public static final List<String> FOODS = List.of("Wholesale Wheat", "Flavor-packed Fish", "Lush Lily Pad");
    private static final int[] COLOURS = {0xFFFFD75E, 0xFF5EC8FF, 0xFF6CFF6C};

    private static final Pattern LIKES = Pattern.compile(
            "^The Scrappy (?:happily eats the|cautiously started eating the|ate the|reaches out its paw for more) (.+?)"
                    + "(?: and came out of its shell)?[.!](?: \\(\\d+\\))?$");
    private static final Pattern REJECTS = Pattern.compile(
            "^This particular Scrappy doesn't seem to care for this type of food");

    /** How far apart the three Scrappies' ids are. */
    private static final int ID_STEP = 3;
    private static final long CLICK_WINDOW_MILLIS = 3_000L;
    private static final int LABEL_RANGE = 64;

    /** Armadillo id -> where it was last seen, for every Scrappy seen this instance. */
    private static final Map<Integer, Vec3> SCRAPPIES = new LinkedHashMap<>();
    /** Armadillo id -> what feeding showed it wants. */
    private static final Map<Integer, String> FED = new LinkedHashMap<>();
    /** Armadillo id -> foods feeding showed it doesn't want. */
    private static final Map<Integer, Set<String>> REFUSED = new LinkedHashMap<>();
    private static Object level;
    private static Integer clicked;
    private static String heldFood;
    private static long clickedAt;
    private static int tickCounter;
    private static int right;
    private static int wrong;

    private ScrappyFood() {
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (world.isClientSide() && player == client.player && ShinyConfig.get().scrappyFood) {
                clicked = scrappyOf(client, entity);
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

    // ------------------------------------------------------------------ the spawn order

    /**
     * The food a Scrappy wants from the ids of the Scrappies seen this instance, or null while it
     * can't be told: lowest id Wheat, middle Fish, highest Lily Pads.
     */
    static String predicted(int id, Set<Integer> ids) {
        TreeSet<Integer> sorted = new TreeSet<>(ids);
        if (!sorted.contains(id)) {
            return null;
        }
        int slot;
        if (sorted.size() >= 3) {
            slot = sorted.headSet(id).size();
        } else if (sorted.size() == 2 && sorted.last() - sorted.first() == 2 * ID_STEP) {
            slot = id == sorted.first() ? 0 : 2; // the middle one is missing
        } else {
            return null; // one Scrappy, or two side by side: not enough to tell
        }
        return slot < FOODS.size() ? FOODS.get(slot) : null;
    }

    /** The armadillo id of the Scrappy this entity belongs to (the body or its nametag), or null. */
    private static Integer scrappyOf(Minecraft client, Entity entity) {
        if (isScrappyName(entity)) {
            return entity.getId() - 1;
        }
        Entity tag = client.level.getEntity(entity.getId() + 1);
        if (tag != null && isScrappyName(tag)) {
            return entity.getId();
        }
        return null;
    }

    private static boolean isScrappyName(Entity entity) {
        return "Scrappy".equals(CritterDex.findIn(EntityDataProbe.nameOf(entity)));
    }

    /** Collects every Scrappy loaded right now: a "Scrappy" nametag, its body one id below. */
    private static void scan(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!entity.isRemoved() && isScrappyName(entity)) {
                Entity body = client.level.getEntity(entity.getId() - 1);
                SCRAPPIES.put(entity.getId() - 1, (body != null ? body : entity).position());
            }
        }
    }

    // ------------------------------------------------------------------ feeding

    private static void chat(String text) {
        if (!ShinyConfig.get().scrappyFood || clicked == null
                || System.currentTimeMillis() - clickedAt > CLICK_WINDOW_MILLIS) {
            return;
        }
        Matcher likes = LIKES.matcher(text);
        String guess = predicted(clicked, SCRAPPIES.keySet());
        if (likes.find()) {
            String food = food(likes.group(1));
            if (food != null && FED.put(clicked, food) == null && guess != null) {
                score(guess.equals(food), food, guess);
            }
        } else if (REJECTS.matcher(text).find() && heldFood != null) {
            REFUSED.computeIfAbsent(clicked, id -> new LinkedHashSet<>()).add(heldFood);
            if (heldFood.equals(guess)) {
                score(false, "not " + heldFood, guess);
            }
        }
    }

    private static void score(boolean ok, String actual, String guess) {
        if (ok) {
            right++;
        } else {
            wrong++;
        }
        ShinyHunterClient.LOGGER.info("Scrappy #{} wanted {}, spawn order said {} — ids seen {}",
                clicked, actual, guess, new TreeSet<>(SCRAPPIES.keySet()));
    }

    /** What to show for one Scrappy: feeding first, then the spawn order. */
    private static String verdict(int id) {
        String fed = FED.get(id);
        if (fed != null) {
            return fed;
        }
        Set<String> refused = REFUSED.getOrDefault(id, Set.of());
        if (refused.size() == FOODS.size() - 1) {
            for (String food : FOODS) {
                if (!refused.contains(food)) {
                    return food;
                }
            }
        }
        String guess = predicted(id, SCRAPPIES.keySet());
        return guess != null && !refused.contains(guess) ? guess : null;
    }

    // ------------------------------------------------------------------ labels

    public static void onClientTick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            SCRAPPIES.clear();
            FED.clear();
            REFUSED.clear();
            clicked = null;
        }
        if (!ShinyConfig.get().scrappyFood || client.level == null || client.player == null
                || !SafariBiomes.playerInSafari(client)) {
            return;
        }
        if (++tickCounter >= 10) {
            tickCounter = 0;
            scan(client);
        }
        for (Map.Entry<Integer, Vec3> scrappy : SCRAPPIES.entrySet()) {
            Entity body = client.level.getEntity(scrappy.getKey());
            Vec3 at = body != null ? body.position() : scrappy.getValue();
            String food = verdict(scrappy.getKey());
            if (food != null) {
                label(client, at, food, COLOURS[FOODS.indexOf(food)]);
            }
        }
    }

    private static void label(Minecraft client, Vec3 at, String text, int colour) {
        if (client.player.position().distanceToSqr(at) > LABEL_RANGE * LABEL_RANGE) {
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

    // ------------------------------------------------------------------ command

    /** {@code /shiny scrappy}: this instance's Scrappies, and how the spawn order has done. */
    public static List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("§b§lScrappy food §7— " + (ShinyConfig.get().scrappyFood ? "§aon" : "§coff")
                + "§7 · spawn order right §a" + right + "§7, wrong §c" + wrong + " §8(this session)");
        for (Integer id : new TreeSet<>(SCRAPPIES.keySet())) {
            String food = verdict(id);
            lines.add("  §7#" + id + " §f" + (food == null ? "?" : food)
                    + (FED.containsKey(id) ? " §8(fed)" : ""));
        }
        if (SCRAPPIES.isEmpty()) {
            lines.add("  §7No Scrappy seen in this instance yet.");
        }
        return lines;
    }
}
