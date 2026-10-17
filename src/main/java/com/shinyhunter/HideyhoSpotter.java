package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Says whether Hideyho hid inside or outside the mansion, the moment it settles after the quest is
 * accepted.
 *
 * <p>Accepting arms a watch. Hideyho then vanishes and is re-sent by the server at its hiding
 * spot — but it surfaces from below (positions like y=-1 and y=45 go past on the way up), so the
 * watch only fires once the entity is within a few blocks of one of the known hiding spots. The
 * verdict is then a point-in-polygon test of that spot against the mansion's footprint, in x/z:
 * the mansion isn't a box, and its floors span several y levels, so the footprint is what decides.
 */
public final class HideyhoSpotter {

    /** Hideyho's line as it runs off to hide. */
    private static final Pattern ACCEPTED = Pattern.compile(
            "Hideyho: (?:Close your eyes and then come find me!|No peeking!)", Pattern.CASE_INSENSITIVE);

    /** The quest is over — a new position after this is Hideyho coming back, not hiding. */
    private static final Pattern FOUND = Pattern.compile(
            "Hideyho: (?:Aah! You found me!|Hehe, you found me!)", Pattern.CASE_INSENSITIVE);

    /** How close the entity has to be to a listed spot to count as settled there. */
    private static final double SPOT_RADIUS = 3.5;

    /** Give up if it hasn't settled by then. */
    private static final long WATCH_MILLIS = 90_000L;

    private static final int SCAN_INTERVAL_TICKS = 5;

    private static long armedAt;
    private static BlockPos spotBefore;
    private static int tickCounter;

    /** The spot already called out this run, so a re-scan doesn't repeat it. */
    private static BlockPos announcedSpot;

    /** Ticks between passive looks for Hideyho when no quest is pending. */
    private static final int IDLE_INTERVAL_TICKS = 20;

    private static int idleCounter;

    private HideyhoSpotter() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> handle(message));
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        if (!ShinyConfig.get().hideyhoInsideOutside) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }
        if (ACCEPTED.matcher(text).find()) {
            if (armedAt == 0L) {
                Minecraft client = Minecraft.getInstance();
                spotBefore = client.level == null ? null : find(client);
                ShinyHunterClient.LOGGER.info("Hideyho quest accepted — watching for its hiding spot (was at {})",
                        spotBefore);
            }
            armedAt = System.currentTimeMillis();
        } else if (FOUND.matcher(text).find()) {
            armedAt = 0L;
        }
    }

    // ------------------------------------------------------------------ tick

    public static void onClientTick(Minecraft client) {
        if (armedAt == 0L) {
            // No quest pending, but the first Hideyho of a run has to be found too — so its spot
            // is called out as soon as it's seen, not only after it hides again.
            idle(client);
            return;
        }
        idleCounter = 0;
        if (client.level == null || client.player == null) {
            armedAt = 0L;
            return;
        }
        if (System.currentTimeMillis() - armedAt > WATCH_MILLIS) {
            ShinyHunterClient.LOGGER.info("Hideyho never settled on a known spot — giving up");
            armedAt = 0L;
            return;
        }
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        BlockPos pos = find(client);
        if (pos == null) {
            return; // gone — between vanishing and being re-sent
        }
        ShinyConfig config = ShinyConfig.get();
        BlockPos spot = nearestSpot(pos, config);
        if (spot == null) {
            return; // in transit — surfacing from below, or somewhere not on the list
        }
        // Still on the spot it was found at when the quest was accepted (its block position can
        // jitter by one, so the spot is compared rather than the position). A re-hide at the very
        // same spot is missed by this; the alternative is announcing the old one every time.
        if (spotBefore != null && spot.equals(nearestSpot(spotBefore, config))) {
            return;
        }
        armedAt = 0L;
        announce(client, config, pos, spot);
    }

    /** The verdict for one settled position, on screen and in chat. */
    private static void announce(Minecraft client, ShinyConfig config, BlockPos pos, BlockPos spot) {
        announcedSpot = spot;
        boolean inside = insideMansion(spot.getX() + 0.5, spot.getZ() + 0.5, config);
        String where = inside ? "inside" : "outside";
        ShinyHunterClient.LOGGER.info("Hideyho settled at {} (spot {}) — {}", pos, spot, where);

        client.gui.setTimes(5, 50, 15);
        client.gui.setTitle(Component.literal(inside ? "§a§lHideyho inside!" : "§c§lHideyho outside!"));
        client.gui.setSubtitle(Component.literal("§7" + spot.getX() + ", " + spot.getY() + ", " + spot.getZ()));
        client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] "
                + (inside ? "§aHideyho inside!" : "§cHideyho outside!")));
    }

    /**
     * Looks for a settled Hideyho while no quest is running. Same verdict as the quest path, said
     * once per spot: walking into the Safari with Hideyho already placed is the common case.
     */
    private static void idle(Minecraft client) {
        if (!ShinyConfig.get().hideyhoInsideOutside || client.level == null || client.player == null) {
            return;
        }
        if (++idleCounter < IDLE_INTERVAL_TICKS) {
            return;
        }
        idleCounter = 0;

        BlockPos pos = find(client);
        if (pos == null) {
            return;
        }
        ShinyConfig config = ShinyConfig.get();
        BlockPos spot = nearestSpot(pos, config);
        if (spot == null || spot.equals(announcedSpot)) {
            return;
        }
        announce(client, config, pos, spot);
    }

    // ------------------------------------------------------------------ geometry

    private static BlockPos find(Minecraft client) {
        String needle = ShinyConfig.get().hideyhoName.trim().toUpperCase();
        if (needle.isEmpty()) {
            return null;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!entity.isRemoved() && EntityDataProbe.nameOf(entity).toUpperCase().contains(needle)) {
                return entity.blockPosition();
            }
        }
        return null;
    }

    /** The listed hiding spot this position is within {@link #SPOT_RADIUS} of, or null. */
    private static BlockPos nearestSpot(BlockPos pos, ShinyConfig config) {
        BlockPos best = null;
        double bestDistance = SPOT_RADIUS * SPOT_RADIUS;
        for (BlockPos spot : parse(config.hideyhoSpots)) {
            double d = spot.distSqr(pos);
            if (d <= bestDistance) {
                bestDistance = d;
                best = spot;
            }
        }
        return best;
    }

    /**
     * Ray-casting point-in-polygon on the mansion's footprint. The corners are listed in walking
     * order around the building; y is carried in the setting for legibility but ignored here.
     */
    static boolean insideMansion(double x, double z, ShinyConfig config) {
        List<double[]> corners = new ArrayList<>();
        for (String corner : config.hideyhoMansion) {
            double[] xyz = parseDoubles(corner);
            if (xyz != null) {
                corners.add(new double[] {xyz[0], xyz[2]});
            }
        }
        if (corners.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
            double xi = corners.get(i)[0], zi = corners.get(i)[1];
            double xj = corners.get(j)[0], zj = corners.get(j)[1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static List<BlockPos> parse(List<String> entries) {
        List<BlockPos> out = new ArrayList<>();
        for (String entry : entries) {
            double[] xyz = parseDoubles(entry);
            if (xyz != null) {
                out.add(new BlockPos((int) Math.floor(xyz[0]), (int) Math.floor(xyz[1]), (int) Math.floor(xyz[2])));
            }
        }
        return out;
    }

    /** "x,y,z" or "x y z", with decimals allowed. */
    private static double[] parseDoubles(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.trim().split("[,\\s]+");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new double[] {
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void reset() {
        armedAt = 0L;
        spotBefore = null;
        announcedSpot = null;
        idleCounter = 0;
    }
}
