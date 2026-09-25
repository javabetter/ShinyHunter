package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A short memory of every particle the server has sent: what it was, where, and when.
 *
 * <p>Kept so the scanner can ask "how many of <em>this</em> particle appeared next to <em>that</em>
 * mob in the last few seconds" — which is what a sparkling trail looks like from the client. Each
 * packet is one entry regardless of its count, because a trail is many packets over time while a
 * single burst (a capture effect, a firework) is one packet with a large count; counting packets
 * separates the two.
 *
 * <p>Entries expire after a few seconds and the buffer is capped, so a busy hub can't grow it
 * without bound. Reads run on the client thread, as does the packet handler that writes.
 */
public final class ParticleWatcher {

    /** How long an entry is remembered. Longer than any detection window that reads it. */
    private static final long KEEP_MILLIS = 6_000L;

    /** Hard cap on remembered entries; a firework show shouldn't become a memory leak. */
    private static final int MAX_ENTRIES = 4_000;

    public record Sighting(String type, Vec3 pos, long at) {
    }

    private static final Deque<Sighting> RECENT = new ArrayDeque<>();

    private ParticleWatcher() {
    }

    /**
     * Called from the packet handler for every particle packet.
     *
     * <p>Defended three ways, because this runs inside the network stack and an exception here
     * doesn't crash the game — it drops the connection, which is worse. The mixin only calls it on
     * the main thread; it refuses to touch the deque from any other thread regardless; and nothing
     * it does can throw past this method.
     */
    public static void record(ClientboundLevelParticlesPacket packet) {
        try {
            if (!Minecraft.getInstance().isSameThread()) {
                return;
            }
            if (!ShinyConfig.get().particleDetection && !Debug.isEnabled()) {
                return; // nothing reads it; don't pay for it
            }
            Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType());
            String type = id == null ? "?" : id.toString();
            long now = System.currentTimeMillis();
            Sighting sighting = new Sighting(type, new Vec3(packet.getX(), packet.getY(), packet.getZ()), now);

            synchronized (RECENT) {
                RECENT.addLast(sighting);
                Sighting oldest;
                while (RECENT.size() > MAX_ENTRIES
                        || ((oldest = RECENT.peekFirst()) != null && now - oldest.at() > KEEP_MILLIS)) {
                    RECENT.pollFirst();
                }
            }
        } catch (RuntimeException e) {
            // Never let anything out of a packet handler.
            ShinyHunterClient.LOGGER.warn("Particle record failed (ignored)", e);
        }
    }

    /**
     * How many packets of any of the given types landed within {@code radius} of {@code centre}
     * in the last {@code windowMillis}.
     */
    public static int countNear(Vec3 centre, double radius, long windowMillis, List<String> types) {
        if (types.isEmpty()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - windowMillis;
        double radiusSq = radius * radius;
        int count = 0;
        synchronized (RECENT) {
            for (Sighting sighting : RECENT) {
                if (sighting.at() < cutoff || sighting.pos().distanceToSqr(centre) > radiusSq) {
                    continue;
                }
                for (String type : types) {
                    if (sighting.type().equalsIgnoreCase(type)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Every particle type seen within {@code radius} of {@code centre} in the window, with counts,
     * most frequent first. This is the discovery tool: read it off a real sparkling and the trail
     * particle is whichever type dominates.
     */
    public static Map<String, Integer> summaryNear(Vec3 centre, double radius, long windowMillis) {
        long cutoff = System.currentTimeMillis() - windowMillis;
        double radiusSq = radius * radius;
        Map<String, Integer> counts = new LinkedHashMap<>();
        synchronized (RECENT) {
            for (Sighting sighting : RECENT) {
                if (sighting.at() >= cutoff && sighting.pos().distanceToSqr(centre) <= radiusSq) {
                    counts.merge(sighting.type(), 1, Integer::sum);
                }
            }
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public static void clear() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }
}
