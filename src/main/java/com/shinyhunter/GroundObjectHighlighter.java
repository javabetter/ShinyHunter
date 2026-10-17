package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Highlights interactable ground objects — the rocks a Rockmite spawns from, and the punchable cases.
 *
 * <p><b>What they are.</b> Each is a pair: an {@code item_display} holding the visible model, and an
 * {@code interaction} entity sitting directly beneath it (same X/Z, a fraction of a block lower)
 * providing the clickable hitbox. Both carry only the generic names "Item Display" and "Interaction",
 * so there is nothing to match on by name — the pairing itself is the signal.
 *
 * <p>Matching on {@code interaction} alone would be wrong: those are used for plenty of other
 * clickables. Requiring a display directly above is what narrows it to a ground object.
 *
 * <p>Gizmo-based, so it must be driven from {@code END_CLIENT_TICK} — see {@link BeeNestHighlighter}.
 */
public final class GroundObjectHighlighter {

    private static final int RESCAN_INTERVAL_TICKS = 20;
    private static final int GIZMO_LIFETIME_MILLIS = 120;

    /** Ids of the display halves that have an interaction paired beneath them. */
    private static final List<Integer> PAIRED = new ArrayList<>();

    /** Position codes already called out, so a re-scan doesn't re-announce the same rock. */
    private static final Map<Integer, Long> ANNOUNCED = new HashMap<>();

    private static final long ANNOUNCE_COOLDOWN_MILLIS = 120_000L;

    private static int tickCounter;

    private GroundObjectHighlighter() {
    }

    /**
     * Private build only: when a disguised Duplico is found, log which ground object was nearest —
     * the one just clicked — so its {@link #describeOnce} line can be told apart from real rocks.
     */
    public static void register() {
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !message.getString().contains("disguised as a block")) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) {
                return;
            }
            Entity nearest = null;
            for (Integer id : PAIRED) {
                Entity display = client.level.getEntity(id);
                if (display != null && (nearest == null
                        || client.player.distanceToSqr(display) < client.player.distanceToSqr(nearest))) {
                    nearest = display;
                }
            }
            ShinyHunterClient.LOGGER.info("Duplico found — nearest ground object: {}",
                    nearest == null ? "none" : nearest.getId() + " (" + Math.round(client.player.distanceTo(nearest)) + "m away)");
        });
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        // These rocks are what a Rockmite spawns from, so once the run's Rockmite is caught there's
        // nothing left to watch them for — same rule the bee nests follow with the Honeybug.
        // Unlike bee nests, these stay highlighted after the run's Rockmite is secured: there are
        // twenty mounds, each is opened once, and every one is worth opening whether or not the
        // unique has already turned up.
        if (!config.highlightGroundObjects
                || client.level == null
                || client.player == null
                || (config.onlyInSkyblock && !SkyblockSidebar.inSkyblock(client))) {
            PAIRED.clear();
            tickCounter = RESCAN_INTERVAL_TICKS;
            return;
        }

        if (++tickCounter >= RESCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            rescan(client, config);
        }
        emit(client, config);
    }

    /**
     * How many unopened mounds are currently loaded nearby. This is the highlighter's own scan
     * result, reused rather than recomputed — an opened mound's entities are gone, so the paired
     * list is already the answer.
     */
    public static int pairedCount() {
        return PAIRED.size();
    }

    private static void rescan(Minecraft client, ShinyConfig config) {
        PAIRED.clear();

        List<Entity> displays = new ArrayList<>();
        List<Entity> interactions = new ArrayList<>();
        double radiusSq = (double) config.groundObjectScanRadius * config.groundObjectScanRadius;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.isRemoved() || client.player.distanceToSqr(entity) > radiusSq) {
                continue;
            }
            if (entity.getType() == EntityType.ITEM_DISPLAY) {
                displays.add(entity);
            } else if (entity.getType() == EntityType.INTERACTION) {
                interactions.add(entity);
            }
        }

        double pairSq = config.groundObjectPairDistance * config.groundObjectPairDistance;
        long now = System.currentTimeMillis();

        for (Entity display : displays) {
            for (Entity interaction : interactions) {
                if (display.distanceToSqr(interaction) > pairSq) {
                    continue;
                }
                PAIRED.add(display.getId());
                if (!Edition.PUBLIC) {
                    describeOnce(client, display, interaction);
                }
                if (config.groundObjectAnnounce) {
                    maybeAnnounce(client, display, now);
                }
                break;
            }
        }

        ANNOUNCED.keySet().removeIf(id -> client.level.getEntity(id) == null);
    }

    /** Display ids already written to the log by {@link #describeOnce}. */
    private static final java.util.Set<Integer> DESCRIBED = new java.util.HashSet<>();

    /**
     * Logs what one ground object is made of, once per object. A still Duplico is built exactly
     * like a rock or a punchable case — a display plus an interaction, and no nametag until it's
     * found — so telling them apart needs to know what each one actually carries: the displayed
     * item, the click box's size, and where it stands.
     */
    private static void describeOnce(Minecraft client, Entity display, Entity interaction) {
        if (!DESCRIBED.add(display.getId())) {
            return;
        }
        if (DESCRIBED.size() > 2000) {
            DESCRIBED.clear();
        }
        var pos = display.position();
        var box = interaction.getBoundingBox();
        ShinyHunterClient.LOGGER.info(
                "Ground object {} at {}, {}, {}: item [{}] | click box {}x{}x{} | display {} above click box",
                display.getId(), Math.round(pos.x * 10) / 10.0, Math.round(pos.y * 10) / 10.0,
                Math.round(pos.z * 10) / 10.0, EntityDataProbe.itemText(display),
                Math.round(box.getXsize() * 100) / 100.0, Math.round(box.getYsize() * 100) / 100.0,
                Math.round(box.getZsize() * 100) / 100.0,
                Math.round((display.getY() - interaction.getY()) * 100) / 100.0);
    }

    private static void maybeAnnounce(Minecraft client, Entity display, long now) {
        Long last = ANNOUNCED.get(display.getId());
        if (last != null && now - last < ANNOUNCE_COOLDOWN_MILLIS) {
            return;
        }
        ANNOUNCED.put(display.getId(), now);

        var pos = display.position();
        client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§b[Shiny Hunter] §7Ground object at §f"
                        + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z
                        + " §8(" + Math.round(client.player.distanceTo(display)) + "m)"));
    }

    private static void emit(Minecraft client, ShinyConfig config) {
        if (PAIRED.isEmpty()) {
            return;
        }

        int stroke = BeeNestHighlighter.parseColor(config.groundObjectColor, 0xFFB98A5A);
        int fill = (stroke & 0x00FFFFFF) | 0x33000000;
        GizmoStyle style = GizmoStyle.strokeAndFill(stroke, 2.0f, fill);

        try {
            for (Integer id : PAIRED) {
                Entity entity = client.level.getEntity(id);
                if (entity == null || entity.isRemoved()) {
                    continue;
                }
                // Item displays have a near-zero bounding box, so pad it into something visible.
                HighlightMarkers.add(entity.getBoundingBox().getCenter(), stroke, "");
                var properties = Gizmos
                        .addGizmo(new EntityBoxGizmo(id, 0.4, 0.0, style))
                        .persistForMillis(GIZMO_LIFETIME_MILLIS);
                Shaders.place(properties, config.groundObjectThroughWalls && !Edition.PUBLIC);
            }
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't emit ground object highlights", e);
        }
    }

    public static int highlightedCount() {
        return PAIRED.size();
    }
}
