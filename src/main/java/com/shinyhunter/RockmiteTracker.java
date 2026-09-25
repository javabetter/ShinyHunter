package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Counts the Rockmite mounds opened this run, and how many are still standing nearby.
 *
 * <p><b>Two different numbers.</b> Opening a mound tells you one of two things: it was empty, or a
 * Rockmite was inside. Both mean the mound is gone, so both count toward the mounds opened; only the
 * second counts toward Rockmites obtained. Conflating them would make an empty mound look like a
 * catch and put the total over the real one.
 *
 * <p>Only your own mounds are counted — these lines are addressed to whoever swung, so a teammate
 * breaking one produces nothing to read. That makes this a personal tally, which is what it's for:
 * knowing whether <em>you</em> have any left to open.
 */
public final class RockmiteTracker {

    /** An opened mound with nothing in it. */
    private static final Pattern EMPTY = Pattern.compile(
            "mound falls apart, but nothing is inside", Pattern.CASE_INSENSITIVE);

    /** An opened mound with a Rockmite in it. */
    private static final Pattern REVEALED = Pattern.compile(
            "mound fell apart, revealing a Rockmite", Pattern.CASE_INSENSITIVE);

    private static int opened;
    private static int obtained;

    private RockmiteTracker() {
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

    private static void handle(Component message) {
        if (!ShinyConfig.get().trackRockmites) {
            return;
        }
        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        if (REVEALED.matcher(text).find()) {
            opened++;
            obtained++;
            Debug.log("rockmite mound opened - found one:", obtained, "obtained,", opened, "opened");
        } else if (EMPTY.matcher(text).find()) {
            opened++;
            Debug.log("rockmite mound opened - empty:", opened, "opened");
        } else {
            return;
        }

        ShinyConfig config = ShinyConfig.get();
        if (opened == config.rockmiteMoundTotal) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "§b[Shiny Hunter] §aAll " + config.rockmiteMoundTotal + " mounds opened§r — "
                                + obtained + " Rockmite" + (obtained == 1 ? "" : "s") + " found."));
            }
        }
    }

    // ------------------------------------------------------------------ state

    public static int obtained() {
        return obtained;
    }

    public static int opened() {
        return opened;
    }

    /** True once every mound has been opened, so the panel can say so instead of counting. */
    public static boolean allOpened() {
        return opened >= ShinyConfig.get().rockmiteMoundTotal;
    }

    /**
     * Unopened mounds currently loaded around you. Read from the highlighter's paired-entity scan
     * rather than counted separately — an opened mound's entities are removed, so what that scan
     * finds is exactly what's left.
     */
    public static int nearby() {
        return GroundObjectHighlighter.pairedCount();
    }

    public static void reset() {
        if (opened > 0) {
            ShinyHunterClient.LOGGER.info("Rockmite tracker reset ({} opened, {} obtained)",
                    opened, obtained);
        }
        opened = 0;
        obtained = 0;
    }
}
