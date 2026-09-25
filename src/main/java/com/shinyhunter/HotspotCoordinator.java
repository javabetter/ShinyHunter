package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Divides the four hunting hotspots between everyone in the party who runs this mod.
 *
 * <p><b>Why this needs no negotiation protocol.</b> Every message arrives through party chat, so the
 * <i>server</i> fixes their order and every client sees the same sequence. Replaying that sequence
 * through the same rules gives every client the same answer, with no back-and-forth that could
 * itself race.
 *
 * <p><b>The event log.</b> Two kinds of message matter: an original CLAIM ("my hotspot is X") and a
 * REASSIGN ("I was bumped, taking Y"). Resolution replays them in order:
 * <ul>
 *   <li>an unheld hotspot goes to whoever names it;</li>
 *   <li>an original claim <b>outranks</b> someone who merely got reassigned there, and bumps them —
 *       this is what stops a reassignment from stealing a hotspot its real owner announces late;</li>
 *   <li>otherwise the later arrival is bumped.</li>
 * </ul>
 * Bumped players take the remaining hotspots in canonical order, in the order they were bumped —
 * which is what keeps three players bumped off one hotspot from all landing on the next.
 *
 * <p>Only the local player ever announces its own reassignment, so the log stays a single ordered
 * stream rather than four clients narrating each other.
 */
public final class HotspotCoordinator {

    /** Fixed order — it's what makes the fallback allocation identical on every client. */
    private static final List<String> HOTSPOTS = List.of("Forest", "Icy", "Haunted", "Cavern");

    enum Kind {
        /** "My hotspot is X" — straight from the server, the authoritative form. */
        CLAIM,
        /** "I was bumped, taking Y" — provisional, and loses to a real claim on the same hotspot. */
        REASSIGN
    }

    record Event(String player, String hotspot, Kind kind, boolean local) {
    }

    /** Events in the order the server delivered them. */
    static final List<Event> EVENTS = new ArrayList<>();

    /**
     * Cap on how many times we'll announce our own reassignment in one round. Each announcement can
     * bump someone else into announcing, so a pathological sequence could ping-pong; stopping is
     * better than flooding party chat.
     */
    private static final int MAX_REASSIGNMENTS = 6;

    private static final long SOLO_GRACE_MILLIS = 6_000L;

    private static String announced;
    private static long announcedAt;
    private static String lastAssignment;
    private static long lastClaimAt;
    private static int reassignmentsSent;

    private HotspotCoordinator() {
    }

    // ------------------------------------------------------------------ input

    /** Records someone's original hotspot, seen in party chat. One per player per round. */
    public static void recordClaim(String player, String rawHotspot) {
        String hotspot = canonical(rawHotspot);
        if (hotspot == null || player == null || player.isBlank()) {
            return;
        }

        for (int i = 0; i < EVENTS.size(); i++) {
            Event event = EVENTS.get(i);
            if (!event.player().equalsIgnoreCase(player) || event.kind() != Kind.CLAIM) {
                continue;
            }
            // Our own claim reaching party chat replaces the locally-guessed one and takes the slot
            // the server actually gave it. Without this the orders diverge between clients.
            if (event.local()) {
                EVENTS.remove(i);
                break;
            }
            return; // already claimed this round
        }

        append(new Event(player, hotspot, Kind.CLAIM, false));
    }

    /** Records someone announcing they were bumped onto a different hotspot. */
    public static void recordReassign(String player, String rawHotspot) {
        String hotspot = canonical(rawHotspot);
        if (hotspot == null || player == null || player.isBlank()) {
            return;
        }
        append(new Event(player, hotspot, Kind.REASSIGN, false));
    }

    private static void append(Event event) {
        EVENTS.add(event);
        lastClaimAt = System.currentTimeMillis();
        ShinyHunterClient.LOGGER.info("Hotspot {}: {} -> {} ({} events)",
                event.kind(), event.player(), event.hotspot(), EVENTS.size());
        refreshAssignment(Minecraft.getInstance());
    }

    /**
     * Notes that we've just announced our own hotspot. The claim itself is registered when our
     * message comes back through party chat, so that it lands in true server order.
     */
    public static void noteOwnAnnouncement(String rawHotspot) {
        String hotspot = canonical(rawHotspot);
        if (hotspot != null) {
            announced = hotspot;
            announcedAt = System.currentTimeMillis();
        }
    }

    /**
     * Handles the solo case: if our own announcement never comes back through party chat — not in a
     * party, relay disabled, wrong channel — nothing would ever register it and no title would show.
     */
    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();

        if (!EVENTS.isEmpty()
                && System.currentTimeMillis() - lastClaimAt > config.hotspotRoundMinutes * 60_000L) {
            reset();
            return;
        }

        if (announced == null || client.player == null) {
            return;
        }
        if (System.currentTimeMillis() - announcedAt < SOLO_GRACE_MILLIS) {
            return;
        }

        String self = selfName(client);
        String pending = announced;
        announced = null;
        if (self == null || hasClaim(self)) {
            return;
        }

        // Only self-claim when genuinely alone. If anyone else's message has come through we ARE in
        // a party and ours is merely late; inventing a local position would order us differently
        // from every other client and hand two people the same hotspot.
        if (!EVENTS.isEmpty()) {
            announced = pending;
            announcedAt = System.currentTimeMillis();
            return;
        }

        EVENTS.add(new Event(self, canonical(pending), Kind.CLAIM, true));
        lastClaimAt = System.currentTimeMillis();
        refreshAssignment(client);
    }

    // ------------------------------------------------------------------ allocation

    /**
     * Replays the event log into a player-to-hotspot map. Deterministic: same sequence in, same
     * result out, on every client.
     */
    static Map<String, String> resolve() {
        Map<String, String> holderOf = new LinkedHashMap<>();   // hotspot -> player
        Map<String, Kind> heldBy = new LinkedHashMap<>();       // hotspot -> how it was taken
        List<String> bumped = new ArrayList<>();
        Set<String> everyone = new LinkedHashSet<>();

        for (Event event : EVENTS) {
            everyone.add(event.player());

            // Whatever this player held before, they're naming a new one now.
            holderOf.entrySet().removeIf(entry -> entry.getValue().equalsIgnoreCase(event.player()));
            bumped.removeIf(player -> player.equalsIgnoreCase(event.player()));

            String current = holderOf.get(event.hotspot());
            if (current == null) {
                holderOf.put(event.hotspot(), event.player());
                heldBy.put(event.hotspot(), event.kind());
            } else if (event.kind() == Kind.CLAIM && heldBy.get(event.hotspot()) == Kind.REASSIGN) {
                // A real claim outranks someone who was only moved here.
                bumped.add(current);
                holderOf.put(event.hotspot(), event.player());
                heldBy.put(event.hotspot(), Kind.CLAIM);
            } else {
                bumped.add(event.player());
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : holderOf.entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }

        List<String> free = new ArrayList<>();
        for (String hotspot : HOTSPOTS) {
            if (!holderOf.containsKey(hotspot)) {
                free.add(hotspot);
            }
        }

        // Bumped players take what's left, in the order they were bumped — so three players bumped
        // off one hotspot get three different fallbacks rather than all grabbing the same one.
        int next = 0;
        for (String player : bumped) {
            if (result.containsKey(player)) {
                continue;
            }
            if (next < free.size()) {
                result.put(player, free.get(next++));
            }
        }
        return result;
    }

    static String assignmentFor(String player) {
        for (Map.Entry<String, String> entry : resolve().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(player)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void refreshAssignment(Minecraft client) {
        String self = selfName(client);
        if (self == null) {
            return;
        }

        String assignment = assignmentFor(self);
        if (assignment == null || assignment.equals(lastAssignment)) {
            return;
        }

        String previous = lastAssignment;
        lastAssignment = assignment;

        String claimed = claimedBy(self);
        boolean reassigned = claimed != null && !assignment.equalsIgnoreCase(claimed);
        showTitle(client, assignment, reassigned);

        // Tell the party we've moved, so their logs match ours. Only when it's actually a move —
        // announcing our first, uncontested hotspot would just duplicate the claim.
        if (reassigned && previous != null && ShinyConfig.get().coordinateHotspots) {
            announceReassignment(client, previous, assignment);
        }
    }

    private static void announceReassignment(Minecraft client, String from, String to) {
        if (reassignmentsSent >= MAX_REASSIGNMENTS) {
            ShinyHunterClient.LOGGER.warn("Reassignment cap reached; not announcing {} -> {}", from, to);
            return;
        }
        reassignmentsSent++;

        client.execute(() -> PartyChat.send(ShinyConfig.get().hotspotCommand,
                "Reassigned from " + from + " to " + to + "!"));
    }

    private static void showTitle(Minecraft client, String hotspot, boolean reassigned) {
        client.gui.setTimes(5, 60, 15);
        client.gui.setSubtitle(Component.literal(reassigned
                ? "§7reassigned — your first pick was taken"
                : "§7your hunting hotspot"));
        client.gui.setTitle(Component.literal("§b§l" + hotspot.toUpperCase()));

        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §rHotspot: §b§l" + hotspot
                            + (reassigned ? " §r§7(reassigned)" : "")));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Maps free text onto one of the four known hotspots, or null if it isn't one. */
    static String canonical(String text) {
        if (text == null) {
            return null;
        }
        String upper = text.trim().toUpperCase();
        for (String hotspot : HOTSPOTS) {
            if (upper.contains(hotspot.toUpperCase())) {
                return hotspot;
            }
        }
        return null;
    }

    /** The hotspot a player originally claimed, ignoring any later reassignment. */
    private static String claimedBy(String player) {
        for (Event event : EVENTS) {
            if (event.kind() == Kind.CLAIM && event.player().equalsIgnoreCase(player)) {
                return event.hotspot();
            }
        }
        return null;
    }

    private static boolean hasClaim(String player) {
        return claimedBy(player) != null;
    }

    private static String selfName(Minecraft client) {
        // Entity.getName() on a player is the plain account name, not the rank-decorated display
        // name — which is what party chat shows and what other clients will have recorded.
        return client.player == null ? null : client.player.getName().getString();
    }

    /** The hotspot currently allocated to this player, or null if none has been worked out. */
    public static String currentAssignment() {
        return lastAssignment;
    }

    /** Clears the round. Called on a new round, on request, and when leaving the world. */
    public static void reset() {
        EVENTS.clear();
        announced = null;
        lastAssignment = null;
        lastClaimAt = 0L;
        reassignmentsSent = 0;
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (EVENTS.isEmpty()) {
            lines.add("§7No hotspot messages seen this round.");
            return lines;
        }
        Map<String, String> resolved = resolve();
        int index = 1;
        for (Event event : EVENTS) {
            lines.add("§7 " + index++ + ". §f" + event.player() + " §7"
                    + (event.kind() == Kind.CLAIM ? "claimed" : "moved to") + " §f" + event.hotspot());
        }
        lines.add("§7Result:");
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            lines.add("§7  §f" + entry.getKey() + " §7-> §b" + entry.getValue());
        }
        return lines;
    }
}
