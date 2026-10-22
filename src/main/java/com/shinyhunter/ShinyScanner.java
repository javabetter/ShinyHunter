package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps every entity the client currently has loaded (i.e. everything inside render distance) and
 * alerts once per matching mob.
 *
 * <p>Two match passes, because they cost very different amounts:
 * <ul>
 *   <li><b>Nameplate</b> — one string per entity, run every scan. Catches the common case, including
 *       hidden nametags, since the custom name is synced whether or not it's rendered.</li>
 *   <li><b>Deep data</b> — synced fields plus item names, lore and custom-data NBT, run per entity on
 *       a slower cycle and cached. This is what finds a mob whose identity is only in the item it's
 *       displaying rather than in any name.</li>
 * </ul>
 */
public final class ShinyScanner {
    /** Two scans a second — fast enough that a shiny is flagged well before it can walk out of range. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** The discovery dump looks this far around a detected sparkling, over this long. */
    private static final double PARTICLE_DUMP_RADIUS = 5.0;
    private static final long PARTICLE_DUMP_WINDOW_MILLIS = 5_000L;

    /** entity id -> wall-clock millis we last announced it. */
    private static final Map<Integer, Long> LAST_NOTIFIED = new HashMap<>();

    /** Entities we've told to glow, so the glow can be cleared when the feature/keyword changes. */
    private static final Map<Integer, Boolean> GLOWING = new HashMap<>();

    /** entity id -> most recent deep-scan result, so the expensive pass doesn't rerun every tick. */
    private static final Map<Integer, DeepResult> DEEP_CACHE = new HashMap<>();

    /**
     * Cap on deep scans per pass. Joining a busy hub drops several hundred entities on us at once
     * with an empty cache; without a budget that's one long stutter on the render thread. Anything
     * skipped is simply picked up by the next pass 0.5s later, so a 500-entity lobby is still fully
     * covered within a few seconds of arriving.
     */
    private static final int MAX_DEEP_SCANS_PER_PASS = 64;

    /** How an entity was identified. Shown in the alert so a false positive can be traced. */
    public enum Reason {
        NAMETAG("nametag"),
        ENTITY_DATA("entity data"),
        PARTICLES("particles"),
        TEXTURE("skin texture");

        public final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    public record Match(Entity entity, Reason reason) {
    }

    /** Entity id -> the reason it last matched, so the announce step can say so. */
    private static final Map<Integer, Reason> REASONS = new HashMap<>();

    /** Entity id -> the critter its sparkling-only skin belongs to ({@link SparklingSkins}). */
    private static final Map<Integer, String> SKIN_CRITTERS = new HashMap<>();

    private record DeepResult(long checkedAt, boolean matched) {
    }

    private static int tickCounter;

    private ShinyScanner() {
    }

    public static void onClientTick(Minecraft client) {
        if (client.level == null || client.player == null) {
            // Left the world — drop the caches so rejoining announces everything afresh.
            LAST_NOTIFIED.clear();
            GLOWING.clear();
            DEEP_CACHE.clear();
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

        // Each keyword decides for itself whether it still applies out here, so leaving the Safari
        // narrows the hunt rather than stopping it.
        List<String> needles = activeKeywords(config, inHuntArea(client, config));
        if (needles.isEmpty()) {
            // Nothing applies here. Drop any outlines this put on mobs rather than leaving them lit
            // on things we've stopped tracking.
            clearGlows(client);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = config.renotifySeconds * 1000L;

        for (Match match : findMatches(client, needles, true)) {
            Entity entity = match.entity();
            // The public edition only reacts to what the player could see. The server sends a
            // sparkling long before that — 76 blocks off and underground, in one log — and the
            // private build acts on it. The public build waits until the name is on your screen,
            // or, for a particle match with no name to read, until the mob is in plain view.
            if (Edition.PUBLIC && !(match.reason() == Reason.NAMETAG
                    ? Sight.canDetect(client, entity) : Sight.canSee(client, entity))) {
                continue;
            }
            REASONS.put(entity.getId(), match.reason());
            if (config.glowMatches && !Edition.PUBLIC && !entity.hasGlowingTag()) {
                entity.setGlowingTag(true);
                GLOWING.put(entity.getId(), true);
            }

            Long last = LAST_NOTIFIED.get(entity.getId());
            if (last != null && now - last < cooldownMillis) {
                continue;
            }
            LAST_NOTIFIED.put(entity.getId(), now);
            announce(client, entity);
        }

        pruneStaleState(client.level, now, cooldownMillis);
    }

    /**
     * Every loaded entity matching any configured keyword, nearest first.
     *
     * @param useCache true on the tick path, so each entity's deep scan is reused for a few seconds.
     *                 False for one-off lookups, which should always read live data.
     */
    public static List<Match> findMatches(Minecraft client, List<String> needles, boolean useCache) {
        List<Match> matches = new ArrayList<>();
        if (client.level == null || client.player == null) {
            return matches;
        }

        ShinyConfig config = ShinyConfig.get();
        long now = System.currentTimeMillis();

        // The keyword channels need keywords; the particle and texture channels don't, and must
        // still run with none configured — a blank keyword list disables hunting by name only.
        if (!needles.isEmpty()) {
            findByKeyword(client, config, needles, useCache, now, matches);
        }
        // Texture hashes are data the player can't see; the public edition detects by nametag
        // and particles only. Both channels only mean anything on the Safari island: elsewhere a
        // statue wearing a sparkling skin would alert forever.
        if ((config.particleDetection || (config.textureDetection && !Edition.PUBLIC))
                && SkyblockSidebar.onSafariIslandOrUnknown(client)) {
            findBySignature(client, config, matches);
        }

        matches.sort(Comparator.comparingDouble(m -> client.player.distanceToSqr(m.entity())));
        return matches;
    }

    private static void findByKeyword(Minecraft client, ShinyConfig config, List<String> needles,
                                      boolean useCache, long now, List<Match> matches) {
        // Only the recurring tick path is budgeted; a one-off command should examine everything.
        int budget = useCache ? MAX_DEEP_SCANS_PER_PASS : Integer.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved()) {
                continue;
            }

            // Cheap pass first — most hits never need the deep scan.
            if (containsAny(EntityDataProbe.nameOf(entity).toUpperCase(), needles)) {
                matches.add(new Match(entity, Reason.NAMETAG));
                continue;
            }
            if (!config.scanEntityData || Edition.PUBLIC) {
                continue;
            }

            if (useCache) {
                DeepResult cached = DEEP_CACHE.get(entity.getId());
                if (cached != null && now - cached.checkedAt() < config.deepScanSeconds * 1000L) {
                    if (cached.matched()) {
                        matches.add(new Match(entity, Reason.ENTITY_DATA));
                    }
                    continue;
                }
            }
            if (budget <= 0) {
                continue; // out of deep-scan budget this pass; retried next pass
            }
            budget--;

            boolean matched = containsAny(EntityDataProbe.deepText(entity).toUpperCase(), needles);
            if (useCache) {
                DEEP_CACHE.put(entity.getId(), new DeepResult(now, matched));
            }
            if (matched) {
                matches.add(new Match(entity, Reason.ENTITY_DATA));
            }
        }
    }

    /**
     * The two channels that need no name: a sparkling trail of particles around the mob, and a
     * sparkling-only skin texture. Both are how a critter reveals itself before it has a name tag.
     * An entity already matched by name isn't re-added; the name is the better reason.
     */
    private static void findBySignature(Minecraft client, ShinyConfig config, List<Match> matches) {
        java.util.Set<Integer> already = new java.util.HashSet<>();
        for (Match match : matches) {
            already.add(match.entity().getId());
        }
        long window = config.particleWindowSeconds * 1000L;
        List<String> textures = config.sparklingTextures;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.isRemoved() || already.contains(entity.getId())) {
                continue;
            }
            // Item displays can carry a critter's skin too (a player head shown on one), so they're
            // checked for textures; particles are only scored on mobs.
            boolean display = entity instanceof net.minecraft.world.entity.Display.ItemDisplay;
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity) && !display) {
                continue;
            }
            boolean armourStand = entity instanceof net.minecraft.world.entity.decoration.ArmorStand;

            // Textures are checked on armour stands too: a sparkling critter's model IS an armour
            // stand wearing a player head (a sparkling Driftling is a silverfish, a head-wearing
            // stand, and a nametag stand, stacked). Only a configured hash matches, so the hundreds
            // of decorative stands around a hub can't trip it.
            if (config.textureDetection && !Edition.PUBLIC) {
                java.util.Set<String> hashes = TextureProbe.hashesOf(entity);
                // The built-in skins name the critter; one critter made of several skinned parts
                // (Shyworm) is matched once, on the first part found.
                String skinCritter = SparklingSkins.critterFor(hashes);
                if (skinCritter != null) {
                    if (!sameCritterNearby(matches, entity, skinCritter)) {
                        matches.add(new Match(entity, Reason.TEXTURE));
                        SKIN_CRITTERS.put(entity.getId(), skinCritter);
                    }
                    already.add(entity.getId());
                    continue;
                }
                boolean hit = false;
                for (String hash : hashes) {
                    for (String wanted : textures) {
                        if (!wanted.isBlank() && hash.startsWith(wanted.trim().toLowerCase())) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        break;
                    }
                }
                if (hit) {
                    matches.add(new Match(entity, Reason.TEXTURE));
                    already.add(entity.getId());
                    continue;
                }
            }

            // Trails are scored against the mob itself, not its stands — the stands are stacked
            // on the same spot and would each count the same trail again.
            if (!armourStand && !display && config.particleDetection && !config.sparklingParticles.isEmpty()) {
                int seen = ParticleWatcher.countNear(entity.position(), config.particleRadius,
                        window, config.sparklingParticles);
                if (seen >= config.particleThreshold) {
                    matches.add(new Match(entity, Reason.PARTICLES));
                    already.add(entity.getId());
                }
            }
        }
    }

    /** A skin match of the same critter already within a few blocks: another part of it. */
    private static boolean sameCritterNearby(List<Match> matches, Entity entity, String critter) {
        for (Match match : matches) {
            if (match.reason() == Reason.TEXTURE && critter.equals(SKIN_CRITTERS.get(match.entity().getId()))
                    && match.entity().distanceToSqr(entity) < 6.0 * 6.0) {
                return true;
            }
        }
        return false;
    }

    /** True when the sidebar's zone row names the configured hunting area. */
    public static boolean inHuntArea(Minecraft client, ShinyConfig config) {
        // The hunt area's zone row, and the Safari island itself: Torrhus Canyon has a zone of the
        // same name, and sparkling detection belongs to the island only.
        return SkyblockSidebar.isAtLocation(client, config.huntLocation)
                && SkyblockSidebar.onSafariIslandOrUnknown(client);
    }

    /**
     * The keywords that apply right now, upper-cased, with blank entries dropped. Outside the
     * hunting area only those flagged to carry on are returned. An empty result means nothing is
     * being hunted here — never "hunt everything".
     */
    /**
     * The public edition hunts sparklings and nothing else: the keyword list is what let it be
     * pointed at any rare mob anywhere in Skyblock, which is not what this is for.
     */
    private static final List<String> PUBLIC_KEYWORDS = List.of("SPARKLING");

    public static List<String> activeKeywords(ShinyConfig config, boolean inHuntArea) {
        if (Edition.PUBLIC) {
            return inHuntArea ? PUBLIC_KEYWORDS : List.of();
        }
        List<String> needles = new ArrayList<>();
        if (config.huntKeywords == null) {
            return needles;
        }
        for (ShinyConfig.Keyword keyword : config.huntKeywords) {
            if (keyword == null || keyword.token == null || keyword.token.isBlank()) {
                continue;
            }
            if (inHuntArea || keyword.huntOutsideSafari) {
                needles.add(keyword.token.trim().toUpperCase());
            }
        }
        // SPARKLING is always hunted in the Safari, whatever the list says: the list is for extra
        // targets, and a list edited down to something else once missed a sparkling Scrappy.
        if (inHuntArea && !containsAny("SPARKLING", needles)) {
            needles.add("SPARKLING");
        }
        return needles;
    }

    /** Every configured keyword regardless of scope — for status output. */
    public static List<String> allKeywords(ShinyConfig config) {
        if (Edition.PUBLIC) {
            return PUBLIC_KEYWORDS;
        }
        return activeKeywords(config, true);
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
        Vec3 pos = entity.position();
        int distance = Math.round(client.player.distanceTo(entity));
        ShinyConfig config = ShinyConfig.get();

        Reason reason = REASONS.getOrDefault(entity.getId(), Reason.NAMETAG);

        // The full fanfare is reserved for an actual SPARKLING. If the keyword has been pointed at
        // something else for testing, that still alerts — just without the ceremony. A particle or
        // texture hit is by definition a sparkling: those channels detect nothing else.
        boolean sparkling = reason == Reason.PARTICLES || reason == Reason.TEXTURE
                || isTrueSparkling(entity, config);
        if (sparkling) {
            // A trail or skin match is on the mob itself, whose name is the vanilla one
            // ("Silverfish"); the Skyblock name is on the nametag stand above it.
            String critter = SKIN_CRITTERS.get(entity.getId());
            if (critter == null) {
                critter = critterNear(client, entity);
            }
            if (critter != null) {
                name = "SPARKLING " + critter;
            } else if (CritterDex.findIn(name) == null) {
                name = "Sparkling critter §7(" + name + ")";
            }
        }
        if (sparkling) {
            announceSparkling(client, config, name, pos, distance, reason);
        } else {
            // The public edition says what was seen, not where: it only alerts on something in
            // view, and the player can see where that is.
            String where = Edition.PUBLIC ? "" : distance + "m away · "
                    + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z + " · ";
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §e§lMATCH! §r§f" + name
                            + " §7(" + where + "detected by " + reason.label + ")"));
            if (config.actionBar) {
                client.player.sendOverlayMessage(Component.literal(
                        "§e§l✨ " + name + (Edition.PUBLIC ? "" : " §r§7" + distance + "m")));
            }
            if (config.playSound) {
                client.player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.5f);
            }
        }

        // A find is exactly the kind of coordinate worth walking to, so it gets a marker — in the
        // private build. The public one shows no position at all.
        if (!Edition.PUBLIC) {
            WaypointManager.add(entity.blockPosition(), name);
        }

        ShinyHunterClient.LOGGER.info("Match: {}{} — detected by {}", name,
                Edition.PUBLIC ? "" : " at " + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z,
                reason.label);

        // The discovery dump: what particles were around it. Read this off a real sparkling and
        // whichever type dominates is the trail — the value to put in the particle list.
        if (sparkling && !Edition.PUBLIC) {
            dumpNearbyParticles(entity, name);
        }
    }

    /**
     * The Skyblock critter this entity is, read from its own name or the nametag stand stacked over
     * it — whichever names a critter, nearest first. Null when nothing nearby names one.
     */
    static String critterNear(Minecraft client, Entity entity) {
        String own = CritterDex.findIn(EntityDataProbe.nameOf(entity));
        if (own != null || client.level == null) {
            return own;
        }
        net.minecraft.world.phys.AABB column = entity.getBoundingBox()
                .inflate(1.5, 1.0, 1.5).expandTowards(0, 4.5, 0);
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity near : client.level.getEntities(entity, column)) {
            String critter = CritterDex.findIn(EntityDataProbe.nameOf(near));
            double distance = near.distanceToSqr(entity);
            if (critter != null && distance < bestDistance) {
                best = critter;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void dumpNearbyParticles(Entity entity, String name) {
        Map<String, Integer> nearby = ParticleWatcher.summaryNear(entity.position(),
                PARTICLE_DUMP_RADIUS, PARTICLE_DUMP_WINDOW_MILLIS);
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Integer> entry : nearby.entrySet()) {
            if (!line.isEmpty()) {
                line.append(", ");
            }
            line.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        String text = line.isEmpty() ? "none" : line.toString();
        ShinyHunterClient.LOGGER.info("Particles within {} blocks of {} (last {}s): {}",
                PARTICLE_DUMP_RADIUS, name, PARTICLE_DUMP_WINDOW_MILLIS / 1000, text);
        Debug.log("particles within", PARTICLE_DUMP_RADIUS, "blocks of", name + ":", text);
        // The matched entity is usually the nametag stand, which carries no skin; the head is on
        // a sibling stand at the same spot, so the whole stack is dumped.
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>(TextureProbe.hashesOf(entity));
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            for (Entity sibling : client.level.getEntities(entity, entity.getBoundingBox().inflate(2.0))) {
                hashes.addAll(TextureProbe.hashesOf(sibling));
            }
        }
        if (!hashes.isEmpty()) {
            ShinyHunterClient.LOGGER.info("Textures on/around {}: {}", name, hashes);
            Debug.log("textures around", name + ":", hashes);
        }
    }

    /**
     * Whether this entity is a genuine SPARKLING, independent of what {@code keyword} is currently
     * set to. Checked against the same sources the match itself used.
     */
    private static boolean isTrueSparkling(Entity entity, ShinyConfig config) {
        if (EntityDataProbe.nameOf(entity).toUpperCase().contains("SPARKLING")) {
            return true;
        }
        return config.scanEntityData && !Edition.PUBLIC
                && EntityDataProbe.deepText(entity).toUpperCase().contains("SPARKLING");
    }

    private static void announceSparkling(Minecraft client, ShinyConfig config,
                                          String name, Vec3 pos, int distance, Reason reason) {
        String rule = "§b§m                                                            §r";

        client.player.sendSystemMessage(Component.literal(rule));
        client.player.sendSystemMessage(Component.literal(
                "              §e§l✦ §6§lS P A R K L I N G §e§l✦"));
        client.player.sendSystemMessage(Component.literal(""));
        client.player.sendSystemMessage(Component.literal("        §f§l" + name));
        if (!Edition.PUBLIC) {
            client.player.sendSystemMessage(Component.literal(
                    "        §7" + distance + "m away  §8·  §7"
                            + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z));
        }
        client.player.sendSystemMessage(Component.literal(
                "        §8detected by §7" + reason.label));
        client.player.sendSystemMessage(Component.literal(rule));

        client.gui.setTimes(5, 50, 15);
        client.gui.setSubtitle(Component.literal("§f" + name + (Edition.PUBLIC ? "" : "  §7" + distance + "m")));
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
     * Forgets entities that have despawned or left render distance. Without this the maps would
     * grow for the whole session, and a mob that wandered away and came back would stay silent
     * forever instead of re-announcing after the cooldown.
     */
    private static void pruneStaleState(ClientLevel level, long now, long cooldownMillis) {
        LAST_NOTIFIED.entrySet().removeIf(entry ->
                now - entry.getValue() > cooldownMillis && level.getEntity(entry.getKey()) == null);
        GLOWING.keySet().removeIf(id -> level.getEntity(id) == null);
        DEEP_CACHE.keySet().removeIf(id -> level.getEntity(id) == null);
    }

    /**
     * Clears every glow this mod applied. Called when glow is turned off or the keyword changes, so
     * outlines from a previous setting don't linger on mobs that no longer match.
     */
    public static void clearGlows(Minecraft client) {
        if (client.level != null) {
            for (Integer id : GLOWING.keySet()) {
                Entity entity = client.level.getEntity(id);
                if (entity != null) {
                    entity.setGlowingTag(false);
                }
            }
        }
        GLOWING.clear();
    }

    /**
     * Drops the "already announced" memory and the deep-scan cache so the next scan re-examines
     * everything currently visible from scratch.
     */
    public static void resetNotifications() {
        LAST_NOTIFIED.clear();
        DEEP_CACHE.clear();
    }
}
