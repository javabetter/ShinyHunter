package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the mobs around you and alerts once per SPARKLING critter you can see.
 *
 * <p>Two ways a sparkling gives itself away:
 * <ul>
 *   <li><b>Nametag</b> — the word SPARKLING in the mob's name. The custom name is synced whether
 *       or not it's rendered, so a hidden nametag still counts.</li>
 *   <li><b>Particles</b> — the trail of sparkle particles a sparkling critter leaves, for critters
 *       that don't carry the word in their name until you interact with them.</li>
 * </ul>
 *
 * <p>Only what's in plain view counts: the server sends mobs well before you could see them (76
 * blocks off and underground, in one log), and an alert for something behind a wall would be
 * information no player has. So every match must pass {@link Sight#canSee} first, and alerts say
 * what was seen, never where.
 */
public final class ShinyScanner {
    /** Two scans a second — fast enough that a shiny is flagged well before it can walk out of range. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Hunted only in the hunting area, and only sparklings. */
    private static final List<String> KEYWORDS = List.of("SPARKLING");

    /** entity id -> wall-clock millis we last announced it. */
    private static final Map<Integer, Long> LAST_NOTIFIED = new HashMap<>();

    /** How an entity was identified. Shown in the alert so a false positive can be traced. */
    public enum Reason {
        NAMETAG("nametag"),
        PARTICLES("particles");

        public final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    public record Match(Entity entity, Reason reason) {
    }

    /** Entity id -> the reason it last matched, so the announce step can say so. */
    private static final Map<Integer, Reason> REASONS = new HashMap<>();

    private static int tickCounter;

    private ShinyScanner() {
    }

    public static void onClientTick(Minecraft client) {
        if (client.level == null || client.player == null) {
            // Left the world — forget what was announced so rejoining announces everything afresh.
            LAST_NOTIFIED.clear();
            PENDING_NOTES.clear();
            return;
        }

        // Ahead of every early return below, so a queued fanfare always finishes playing.
        tickFanfare(client);

        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        ShinyConfig config = ShinyConfig.get();
        if (!config.enabled) {
            return;
        }
        if (config.onlyInSkyblock && !inSkyblock(client)) {
            return;
        }

        List<String> needles = activeKeywords(config, inHuntArea(client, config));
        if (needles.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = config.renotifySeconds * 1000L;

        for (Match match : findMatches(client, needles)) {
            Entity entity = match.entity();
            if (!Sight.canSee(client, entity)) {
                continue;
            }
            REASONS.put(entity.getId(), match.reason());

            Long last = LAST_NOTIFIED.get(entity.getId());
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            LAST_NOTIFIED.put(entity.getId(), now);
            announce(client, entity);
        }

        pruneStaleState(client.level, now, cooldownMillis);
    }

    /** Every loaded entity matching by nametag or particle trail, nearest first. */
    public static List<Match> findMatches(Minecraft client, List<String> needles) {
        List<Match> matches = new ArrayList<>();
        if (client.level == null || client.player == null) {
            return matches;
        }

        ShinyConfig config = ShinyConfig.get();
        if (!needles.isEmpty()) {
            findByKeyword(client, needles, matches);
        }
        if (config.particleDetection) {
            findByParticles(client, config, matches);
        }

        matches.sort(Comparator.comparingDouble(m -> client.player.distanceToSqr(m.entity())));
        return matches;
    }

    private static void findByKeyword(Minecraft client, List<String> needles, List<Match> matches) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }
            if (containsAny(EntityDataProbe.nameOf(entity).toUpperCase(), needles)) {
                matches.add(new Match(entity, Reason.NAMETAG));
            }
        }
    }

    /**
     * A sparkling trail of particles around a mob — how a critter reveals itself before it has a
     * name tag. An entity already matched by name isn't re-added; the name is the better reason.
     */
    private static void findByParticles(Minecraft client, ShinyConfig config, List<Match> matches) {
        if (config.sparklingParticles.isEmpty()) {
            return;
        }
        java.util.Set<Integer> already = new java.util.HashSet<>();
        for (Match match : matches) {
            already.add(match.entity().getId());
        }
        long window = config.particleWindowSeconds * 1000L;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved() || already.contains(entity.getId())) {
                continue;
            }
            // Trails are scored against the mob itself, not its stands — the stands are stacked
            // on the same spot and would each count the same trail again.
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity)
                    || entity instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                continue;
            }
            int seen = ParticleWatcher.countNear(entity.position(), config.particleRadius,
                    window, config.sparklingParticles);
            if (seen >= config.particleThreshold) {
                matches.add(new Match(entity, Reason.PARTICLES));
                already.add(entity.getId());
            }
        }
    }

    /** True when the sidebar's zone row names the configured hunting area. */
    public static boolean inHuntArea(Minecraft client, ShinyConfig config) {
        return SkyblockSidebar.isAtLocation(client, config.huntLocation);
    }

    /** What's hunted right now: sparklings in the hunting area, nothing anywhere else. */
    public static List<String> activeKeywords(ShinyConfig config, boolean inHuntArea) {
        return inHuntArea ? KEYWORDS : List.of();
    }

    /** Every keyword regardless of where you are — for status output. */
    public static List<String> allKeywords(ShinyConfig config) {
        return KEYWORDS;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** @see SkyblockSidebar#inSkyblock(Minecraft) */
    public static boolean inSkyblock(Minecraft client) {
        return SkyblockSidebar.inSkyblock(client);
    }

    private static void announce(Minecraft client, Entity entity) {
        String name = EntityDataProbe.nameOf(entity).trim();
        if (name.isEmpty()) {
            name = String.valueOf(EntityType.getKey(entity.getType()));
        }
        ShinyConfig config = ShinyConfig.get();
        Reason reason = REASONS.getOrDefault(entity.getId(), Reason.NAMETAG);

        // The full fanfare is for an actual SPARKLING; a particle hit is one by definition.
        boolean sparkling = reason == Reason.PARTICLES
                || EntityDataProbe.nameOf(entity).toUpperCase().contains("SPARKLING");
        if (sparkling) {
            announceSparkling(client, config, name, reason);
        } else {
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §e§lMATCH! §r§f" + name + " §7(detected by " + reason.label + ")"));
            if (config.actionBar) {
                client.player.sendOverlayMessage(Component.literal("§e§l✨ " + name));
            }
            if (config.playSound) {
                client.player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.5f);
            }
        }

        ShinyHunterClient.LOGGER.info("Match: {} — detected by {}", name, reason.label);
    }

    private static void announceSparkling(Minecraft client, ShinyConfig config, String name, Reason reason) {
        String rule = "§b§m                                                            §r";

        client.player.sendSystemMessage(Component.literal(rule));
        client.player.sendSystemMessage(Component.literal(
                "              §e§l✦ §6§lS P A R K L I N G §e§l✦"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.literal("        §f§l" + name));
        client.player.sendSystemMessage(Component.literal(
                "        §8detected by §7" + reason.label));
        client.player.sendSystemMessage(Component.literal(rule));

        client.gui.setTimes(5, 50, 15);
        client.gui.setSubtitle(Component.literal("§f" + name));
        client.gui.setTitle(Component.literal("§6§l✦ §e§lSPARKLING §6§l✦"));

        if (config.actionBar) {
            client.player.sendOverlayMessage(Component.literal(
                    "§e§l✨ §6§lSPARKLING §e§l✨ §r§f" + name));
        }
        if (config.playSound) {
            playFanfare();
        }
    }

    // ------------------------------------------------------------------ fanfare

    /** Pending fanfare notes as (ticks remaining, pitch), drained by {@link #onClientTick}. */
    private static final List<float[]> PENDING_NOTES = new ArrayList<>();

    /**
     * A rising three-note arpeggio. Queued rather than played at once — three sounds fired on the
     * same tick just stack into one muddy chord.
     */
    private static void playFanfare() {
        PENDING_NOTES.add(new float[]{0f, 1.0f});
        PENDING_NOTES.add(new float[]{3f, 1.35f});
        PENDING_NOTES.add(new float[]{6f, 1.8f});
    }

    private static void tickFanfare(Minecraft client) {
        if (PENDING_NOTES.isEmpty() || client.player == null) {
            return;
        }
        for (var iterator = PENDING_NOTES.iterator(); iterator.hasNext(); ) {
            float[] note = iterator.next();
            if (note[0] <= 0f) {
                client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, note[1]);
                iterator.remove();
            } else {
                note[0]--;
            }
        }
    }

    /**
     * Forgets entities that have despawned or left render distance. Without this the map would
     * grow for the whole session, and a mob that wandered away and came back would stay silent
     * forever instead of re-announcing after the cooldown.
     */
    private static void pruneStaleState(ClientLevel level, long now, long cooldownMillis) {
        LAST_NOTIFIED.entrySet().removeIf(entry ->
                now - entry.getValue() > cooldownMillis && level.getEntity(entry.getKey()) == null);
        REASONS.keySet().removeIf(id -> level.getEntity(id) == null);
    }
}
