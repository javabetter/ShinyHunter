package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Verbose tracing, printed to chat as well as the log. There's no command for it in release builds;
 * a developer switches it on with {@link #setEnabled} while working on the mod.
 *
 * <p>Exists because the features that go wrong here go wrong <em>during</em> a run — a split that
 * doesn't commit, a catch that isn't credited — and by the time the run is over the state that would
 * explain it has already been cleared. Reading it back out of the log after the fact means finding
 * the log, and the interesting lines are interleaved with everything else. In chat it's visible at
 * the moment it happens.
 *
 * <p>Off by default and off after a restart: this is loud on purpose.
 *
 * <p><b>Traces never re-enter the chat handlers.</b> See {@link #log} — the mod's own chat output
 * is delivered to Fabric's receive event like anything else, so a trace quoting a matched line
 * would feed straight back into the matcher.
 */
public final class Debug {

    private static boolean enabled;

    private Debug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ShinyHunterClient.LOGGER.info("Debug tracing {}", value ? "ON" : "off");
    }

    /**
     * Traces one event. The message is only assembled when tracing is on, so callers pass the parts
     * rather than a pre-built string.
     */
    public static void log(String what, Object... details) {
        if (!enabled) {
            return;
        }
        StringBuilder line = new StringBuilder(what);
        for (Object detail : details) {
            line.append(' ').append(detail);
        }
        String text = line.toString();

        ShinyHunterClient.LOGGER.info("[debug] {}", text);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            // Straight into the chat window, NOT via sendSystemMessage. That route goes through
            // ChatListener, where Fabric fires ClientReceiveMessageEvents.GAME — so every trace
            // would re-enter every chat handler in the mod. A trace that quotes the line it's
            // tracing ("options line: Select an option...") then matches itself, traces again,
            // and loops until the game locks up. This entry point skips the listener entirely.
            client.gui.getChat().addClientSystemMessage(Component.literal("§8[dbg] §7" + text));
        }
    }
}
