package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses to start a Safari run early — before the party is all here, after somebody has called
 * for a kick, or while a {@code !dt} downtime hold stands. Sneak and click to go anyway.
 *
 * <p><b>Both ways in are covered.</b> The party leader starts a run with a single click on the
 * manager; everyone else clicks him and then uses a ticket. Blocking only the click would leave the
 * ticket route open, and blocking only the ticket would leave the leader's route open, so both are
 * guarded. The click itself is cancelled from {@link com.shinyhunter.mixin.MultiPlayerGameModeMixin}
 * — see that class for why Fabric's {@code UseEntityCallback} cannot do it.
 *
 * <p><b>Counting who's here.</b> Loaded player entities within a short radius are counted, not the
 * tab list: on Hypixel the tab list is a formatted information display full of entries that aren't
 * players at all, and even the real ones include party members still back in the canyon — which is
 * the exact case this guard exists to catch. Standing next to somebody is the only evidence that
 * they are actually here.
 */
public final class SafariGuard {

    /** Gap between refusals, so holding right-click doesn't fill chat. */
    private static final long MESSAGE_COOLDOWN_MILLIS = 3_000L;

    /** "kick" as its own word, so "kickstart" doesn't trip it. */
    private static final Pattern KICK =
            Pattern.compile("\\bkick\\b", Pattern.CASE_INSENSITIVE);

    /** A Minecraft account name. Hypixel's holograms carry names that don't fit this. */
    private static final Pattern ACCOUNT_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /**
     * Real Mojang accounts get random (version 4) UUIDs. Server-spawned fake players — every NPC on
     * Hypixel, the Safari Manager included — get name-based (version 2) ones, because that's what
     * you get from {@code UUID.nameUUIDFromBytes}. This is the discriminator that actually works:
     * an NPC's profile name can look exactly like an account name, so the name test alone counted
     * the Safari Manager as a party member.
     */
    private static final int REAL_ACCOUNT_UUID_VERSION = 4;

    /** How far above and around an NPC to look for the nameplate that identifies it. */
    private static final double NAMEPLATE_RADIUS = 3.0;

    private static long lastMessageAt;

    /** Set when somebody calls for a kick; the manager stays blocked until it passes. */
    private static long blockedUntil;

    private SafariGuard() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                watchForKick(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> watchForKick(message));

        // Left-clicking the manager. Unlike the right-click this one Fabric does fire client-side.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                shouldBlockEntity(entity) ? InteractionResult.FAIL : InteractionResult.PASS);

        // The non-leader route: click the manager, then use the ticket in hand.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            Minecraft client = Minecraft.getInstance();
            if (player != client.player) {
                return InteractionResult.PASS;
            }
            return shouldBlockTicket(client, player.getItemInHand(hand))
                    ? InteractionResult.FAIL
                    : InteractionResult.PASS;
        });
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Whether right- or left-clicking this entity should be refused. Called from the mixin on every
     * entity interaction, so it returns early and cheaply for anything that isn't the manager.
     */
    public static boolean shouldBlockEntity(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        ShinyConfig config = ShinyConfig.get();

        if (!config.guardSafariManager || config.safariManagerName.isBlank()
                || client.player == null || entity == null) {
            return false;
        }
        if (!inSafariArea(client, config)) {
            return false;
        }
        if (!isManager(client, entity, config)) {
            return false;
        }
        if (client.player.isShiftKeyDown()) {
            // Deliberate override. It also spends the downtime hold, which is a one-shot by design.
            DowntimeTracker.clearHold("sneak-clicked the manager");
            return false;
        }
        return refuse(client, config, "clicking " + config.safariManagerName);
    }

    /** Whether using this item should be refused, i.e. it's a Safari ticket and we aren't ready. */
    private static boolean shouldBlockTicket(Minecraft client, ItemStack stack) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.guardSafariManager || !config.guardTicketItem
                || client.player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (!inSafariArea(client, config)) {
            return false;
        }
        String name = EntityDataProbe.stripFormatting(stack.getHoverName().getString());
        if (!name.toUpperCase().contains(config.ticketItemName.trim().toUpperCase())) {
            return false;
        }
        if (client.player.isShiftKeyDown()) {
            DowntimeTracker.clearHold("sneak-used a ticket");
            return false;
        }
        return refuse(client, config, "using a " + config.ticketItemName);
    }

    /**
     * The shared verdict: block when the party isn't all here, or a kick is pending. Prints the
     * reason at most once every few seconds and always logs it.
     */
    private static boolean refuse(Minecraft client, ShinyConfig config, String what) {
        long now = System.currentTimeMillis();
        int present = presentPlayers(client);
        boolean kickPending = now < blockedUntil;
        boolean downtime = DowntimeTracker.holding();

        // The party size comes from the roster the party-chat lines build, not a setting: a
        // three-person party is full at three.
        int partySize = PartyDex.partySize();
        if (present >= partySize && !kickPending && !downtime) {
            return false;
        }

        String reason = downtime
                ? "§e" + DowntimeTracker.holders() + "§c asked for downtime"
                : kickPending
                        ? "§ca kick was called"
                        : "§conly " + present + "/" + partySize + " here";
        if (now - lastMessageAt > MESSAGE_COOLDOWN_MILLIS) {
            lastMessageAt = now;
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §fBlocked§r — " + reason + "§r. Sneak to go anyway."));
        }
        ShinyHunterClient.LOGGER.info("Blocked {}: {}/{} present, kickPending={}, downtime={} — counted {}",
                what, present, partySize, kickPending, downtime, String.join(", ", presentNames(client)));
        return true;
    }

    /**
     * True anywhere in the Safari, its entrance included — the manager stands at the door, which is
     * a different zone from the hunting grounds proper.
     */
    private static boolean inSafariArea(Minecraft client, ShinyConfig config) {
        return SkyblockSidebar.isAtOrWithin(client, config.huntLocation);
    }

    // ------------------------------------------------------------------ identifying the NPC

    /**
     * Whether this entity is the Safari Manager.
     *
     * <p>Hypixel's NPCs are player entities whose account name is junk, with the visible name
     * floating above them on a separate armour stand. So the entity's own text is checked first, and
     * failing that the nameplates hovering near it — otherwise the guard would never recognise him.
     */
    private static boolean isManager(Minecraft client, Entity entity, ShinyConfig config) {
        String needle = config.safariManagerName.trim().toUpperCase();

        if (EntityDataProbe.nameOf(entity).toUpperCase().contains(needle)) {
            return true;
        }
        if (EntityDataProbe.deepText(entity).toUpperCase().contains(needle)) {
            return true;
        }

        AABB around = entity.getBoundingBox().inflate(NAMEPLATE_RADIUS);
        for (Entity nearby : client.level.getEntities(entity, around)) {
            Component custom = nearby.getCustomName();
            if (custom != null
                    && EntityDataProbe.stripFormatting(custom.getString()).toUpperCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ kick calls

    /**
     * Watches party chat for someone calling a kick, and holds the manager shut for a while after.
     * Matched as a whole word so "kicked off" counts but "kickstart" doesn't.
     */
    private static void watchForKick(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.guardSafariManager || !config.blockOnKickCall) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (!text.contains("Party >")) {
            return;
        }
        var relayed = HotspotAnnouncer.RELAYED_PUBLIC.matcher(text);
        if (!relayed.find() || !KICK.matcher(relayed.group(2)).find()) {
            return;
        }

        blockedUntil = System.currentTimeMillis() + config.kickBlockSeconds * 1000L;
        ShinyHunterClient.LOGGER.info("Kick called — {} blocked for {}s",
                config.safariManagerName, config.kickBlockSeconds);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §eKick called§r — manager held for "
                            + config.kickBlockSeconds + "s."));
        }
    }

    /** Clears a pending kick hold — used when a run ends. */
    public static void clearKickHold() {
        blockedUntil = 0L;
    }

    public static boolean kickPending() {
        return System.currentTimeMillis() < blockedUntil;
    }

    // ------------------------------------------------------------------ presence

    /**
     * Real players standing near you, yourself included.
     *
     * <p>An entity counts when it is close enough to be genuinely here, and its name looks like a
     * Minecraft account — Hypixel's NPCs are player entities too, and their names are decorated with
     * symbols and colour codes that no real account can contain.
     */
    public static int presentPlayers(Minecraft client) {
        return presentNames(client).size();
    }

    /** Exactly who {@link #presentPlayers} counted, for the log and {@code /shiny who}. */
    public static Set<String> presentNames(Minecraft client) {
        Set<String> names = new LinkedHashSet<>();
        if (client.level == null || client.player == null) {
            return names;
        }

        AABB around = client.player.getBoundingBox().inflate(ShinyConfig.get().presenceRadius);
        for (Player player : client.level.players()) {
            if (player.getBoundingBox().intersects(around) && isRealPlayer(client, player)) {
                names.add(EntityDataProbe.stripFormatting(player.getName().getString()).trim());
            }
        }
        return names;
    }

    /**
     * Whether this player entity is a person rather than one of Hypixel's NPCs.
     *
     * <p>Three things have to hold: a version-4 UUID, an account-shaped name, and an entry in the
     * server's player list. Any one of them alone lets something through — NPCs carry plausible
     * names, and Hypixel puts entries in the player list that aren't people — so all three are
     * required.
     */
    private static boolean isRealPlayer(Minecraft client, Player player) {
        if (player.getUUID().version() != REAL_ACCOUNT_UUID_VERSION) {
            return false;
        }
        String name = EntityDataProbe.stripFormatting(player.getName().getString()).trim();
        if (!ACCOUNT_NAME.matcher(name).matches()) {
            return false;
        }
        return client.getConnection() == null
                || client.getConnection().getPlayerInfo(player.getUUID()) != null;
    }

    /**
     * Every nearby player entity with the reason it was or wasn't counted. Purely diagnostic — when
     * the tally looks wrong this says which side of the account-name test each one landed on.
     */
    public static List<String> presenceReport(Minecraft client) {
        List<String> report = new ArrayList<>();
        if (client.level == null || client.player == null) {
            report.add("not in a world");
            return report;
        }

        AABB around = client.player.getBoundingBox().inflate(ShinyConfig.get().presenceRadius);
        for (Player player : client.level.players()) {
            String name = EntityDataProbe.stripFormatting(player.getName().getString()).trim();
            double distance = Math.sqrt(player.distanceToSqr(client.player));
            boolean near = player.getBoundingBox().intersects(around);
            boolean real = isRealPlayer(client, player);

            String why;
            if (!real) {
                why = " §8npc (uuid v" + player.getUUID().version() + ")";
            } else if (!near) {
                why = " §8too far";
            } else {
                why = "";
            }
            report.add(String.format("%s%s §7(%.1fm)%s",
                    real && near ? "§a✔ " : "§c✖ ",
                    name.isEmpty() ? "§8<no name>" : "§f" + name,
                    distance, why));
        }
        if (report.isEmpty()) {
            report.add("§8no player entities loaded");
        }
        return report;
    }
}
