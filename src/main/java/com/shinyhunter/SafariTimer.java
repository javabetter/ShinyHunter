package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * The run clock.
 *
 * <p><b>Why chat and not the zone.</b> Walking into the Safari isn't the same thing as the run
 * beginning — the party stands around in there while people ready up. Hypixel says so explicitly
 * when the hunt actually starts, so the clock keys off those lines rather than off the sidebar.
 * Two of them are accepted because which one you get depends on the run: a head-start gem is only
 * announced when somebody has one, and the hotspot line only when hotspots are in play. Whichever
 * lands first wins and the other is ignored for the remainder of the run.
 */
public final class SafariTimer {

    /** A gem handed out at the start of a run. */
    private static final Pattern HEAD_START = Pattern.compile("^HEAD START!", Pattern.CASE_INSENSITIVE);

    /** The per-player hotspot assignment, sent once the hunt begins. */
    private static final Pattern HOTSPOT = Pattern.compile("^HOTSPOT!", Pattern.CASE_INSENSITIVE);

    private static long startedAt;

    private SafariTimer() {
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
        if (!ShinyConfig.get().showTimer) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty()) {
            return;
        }

        // Somebody quoting a start line in party chat must not start anybody's clock.
        if (HotspotAnnouncer.isRelayed(text)) {
            return;
        }

        if (HEAD_START.matcher(text).find() || HOTSPOT.matcher(text).find()) {
            start(text.split("!", 2)[0] + "!");
        }
    }

    /**
     * Starts the clock, ignoring every later trigger in the same run. Both start lines usually
     * arrive together at the top of a run; taking only the first keeps the clock honest whichever
     * order they land in.
     */
    private static void start(String trigger) {
        if (startedAt != 0) {
            return;
        }
        startedAt = System.currentTimeMillis();
        ShinyHunterClient.LOGGER.info("Safari clock started by \"{}\"", trigger);
        Debug.log("run clock started by", "\"" + trigger + "\"");
    }

    // ------------------------------------------------------------------ state

    public static boolean isRunning() {
        return startedAt != 0;
    }

    /** Milliseconds since the run began, or 0 when it hasn't. */
    public static long elapsedMillis() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    /** Clears the clock. Called at every run boundary. */
    public static void resetRun() {
        if (startedAt != 0) {
            ShinyHunterClient.LOGGER.info("Safari clock reset at {}", format(elapsedMillis()));
        }
        startedAt = 0;
    }

    /** Kept as a distinct entry point for leaving the world; today it's the same as a run reset. */
    public static void reset() {
        resetRun();
    }

    // ------------------------------------------------------------------ formatting

    /**
     * A split time in the form the community writes them: {@code 2m38.25s}, {@code 9.35s}. Seconds
     * aren't zero-padded but hundredths are, so times line up on the decimal point without carrying
     * a leading zero nobody writes.
     */
    public static String format(long millis) {
        long total = Math.max(0, millis);
        long minutes = total / 60_000L;
        long seconds = (total % 60_000L) / 1000L;
        long hundredths = (total % 1000L) / 10L;
        return minutes > 0
                ? String.format("%dm%d.%02ds", minutes, seconds, hundredths)
                : String.format("%d.%02ds", seconds, hundredths);
    }
}
