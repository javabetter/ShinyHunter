package com.shinyhunter;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;

/**
 * Lets Hideyho's "play again?" prompt be accepted by clicking anywhere with chat open, instead of
 * hunting for the small {@code [Sure]} button among the chat lines.
 *
 * <p>Hypixel's dialogue options are ordinary clickable chat components, so this doesn't simulate a
 * cursor anywhere: when the prompt arrives it pulls the {@code [Sure]} component's own click event
 * out of the message and holds onto it. Any click while chat is open then fires that exact event —
 * the same thing the server would have received had the button been clicked directly.
 *
 * <p>Only two kinds of click event are ever fired: {@code run_command}, and the {@code custom}
 * action Hypixel switched the dialogue buttons to — both go to the server and nowhere else. A chat
 * component can carry an "open this URL" action just as easily, and blindly firing whatever a click
 * event contains would hand any message on the server the ability to open things on your machine.
 */
public final class QuestAccepter {

    /** How long the prompt stays answerable before the offer is assumed to have lapsed. */
    private static final long OFFER_WINDOW_MILLIS = 30_000L;

    /** How recently the mob must have spoken for an options line to be treated as its prompt. */
    private static final long DIALOGUE_WINDOW_MILLIS = 15_000L;

    /** The button's click event, held until a click fires it. Only RunCommand or Custom. */
    private static ClickEvent pending;
    private static long pendingSince;
    private static long lastMobLineAt;

    private QuestAccepter() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, params, timestamp) -> handle(message));

        // Handlers are per-screen, so they're attached each time a chat screen is built.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, mouse) -> !acceptIfPending());
            }
        });
    }

    // ------------------------------------------------------------------ chat

    private static void handle(Component message) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.acceptQuestClicks) {
            return;
        }

        String text = EntityDataProbe.stripFormatting(message.getString()).trim();
        if (text.isEmpty() || HotspotAnnouncer.isRelayed(text) || isOwnOutput(text)) {
            return;
        }

        String mob = config.questMobName.trim();
        if (!mob.isEmpty() && text.toUpperCase().contains(mob.toUpperCase())) {
            lastMobLineAt = System.currentTimeMillis();
        }

        if (!text.contains("Select an option:")) {
            return;
        }
        long sinceMob = System.currentTimeMillis() - lastMobLineAt;
        Debug.log("options line:", "\"" + text + "\"", "-", mob, "spoke", sinceMob + "ms ago");

        // Only this mob's prompt. Other NPCs offer options too — the shard traders, for one — and
        // answering those blind is not what was asked for.
        if (sinceMob > DIALOGUE_WINDOW_MILLIS) {
            ShinyHunterClient.LOGGER.info("Options line ignored — {} last spoke {}ms ago (window {}ms)",
                    mob, sinceMob, DIALOGUE_WINDOW_MILLIS);
            return;
        }

        ClickEvent button = findButton(message, config.questAcceptLabel.trim());
        if (button == null) {
            ShinyHunterClient.LOGGER.info("Options line seen but no fireable '{}' button in it",
                    config.questAcceptLabel);
            for (Component node : flatten(message)) {
                ClickEvent click = node.getStyle().getClickEvent();
                if (click != null) {
                    Debug.log("  option node:", "\"" + node.getString().trim() + "\"", "->",
                            click.getClass().getSimpleName());
                }
            }
            return;
        }

        pending = button;
        pendingSince = System.currentTimeMillis();
        ShinyHunterClient.LOGGER.info("Quest prompt armed — '{}' fires {}", config.questAcceptLabel,
                describe(button));
        Debug.log("prompt armed:", describe(button));

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "§b[Shiny Hunter] §aClick anywhere§r with chat open to accept §f" + mob + "§r."));
        }
    }

    /**
     * The mod's own chat lines. They arrive through the same receive event as the server's, and
     * "Click anywhere with chat open to accept Hideyho" names the mob — which would refresh the
     * dialogue window on our own say-so. Nothing this mod prints is evidence of anything.
     */
    private static boolean isOwnOutput(String text) {
        return text.startsWith("[Shiny Hunter]") || text.startsWith("[dbg]");
    }

    /**
     * Walks the message for the option button carrying {@code label} and returns its click event.
     * The shortest match wins, so a parent component wrapping the whole line can't be mistaken for
     * the button itself.
     *
     * <p>Hypixel's buttons used to be {@code run_command} and are now {@code custom} — the newer
     * mechanism where the server hands out an opaque id and payload and the client just echoes it
     * back on click. Both are accepted; nothing else is (see the class comment for why).
     */
    private static ClickEvent findButton(Component message, String label) {
        String upperLabel = label.toUpperCase();
        ClickEvent best = null;
        int bestLength = Integer.MAX_VALUE;

        for (Component node : flatten(message)) {
            ClickEvent click = node.getStyle().getClickEvent();
            if (!(click instanceof ClickEvent.RunCommand) && !(click instanceof ClickEvent.Custom)) {
                continue;
            }
            String content = EntityDataProbe.stripFormatting(node.getString()).trim();
            if (!content.toUpperCase().contains(upperLabel) || content.length() >= bestLength) {
                continue;
            }
            best = click;
            bestLength = content.length();
        }
        return best;
    }

    private static String describe(ClickEvent event) {
        if (event instanceof ClickEvent.RunCommand run) {
            return "command " + run.command();
        }
        if (event instanceof ClickEvent.Custom custom) {
            return "custom action " + custom.id();
        }
        return event.getClass().getSimpleName();
    }

    /** Every component in the tree, parents included. */
    private static java.util.List<Component> flatten(Component root) {
        java.util.List<Component> all = new java.util.ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(Component node, java.util.List<Component> into) {
        into.add(node);
        for (Component sibling : node.getSiblings()) {
            collect(sibling, into);
        }
    }

    // ------------------------------------------------------------------ clicking

    /**
     * Fires the held command, if there is one. Returns true when the click was used for that, so the
     * caller can swallow it rather than letting it also land on whatever was under the cursor.
     */
    private static boolean acceptIfPending() {
        if (pending == null) {
            Debug.log("chat click - no prompt pending");
            return false;
        }
        if (!ShinyConfig.get().acceptQuestClicks) {
            Debug.log("chat click - prompt pending but accepting is off in settings");
            return false;
        }
        long age = System.currentTimeMillis() - pendingSince;
        if (age > OFFER_WINDOW_MILLIS) {
            ShinyHunterClient.LOGGER.info("Quest prompt lapsed — armed {}ms ago, window {}ms",
                    age, OFFER_WINDOW_MILLIS);
            pending = null;
            return false;
        }
        Debug.log("chat click - accepting, prompt armed", age + "ms ago");

        ClickEvent button = pending;
        pending = null;

        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            ShinyHunterClient.LOGGER.info("Quest click with no connection — nothing sent");
            return false;
        }
        if (button instanceof ClickEvent.RunCommand run) {
            // Commands from a click event arrive with the leading slash already stripped by the
            // codec in some versions and not others, so normalise before sending.
            String command = run.command();
            client.getConnection().sendCommand(command.startsWith("/") ? command.substring(1) : command);
        } else if (button instanceof ClickEvent.Custom custom) {
            // Exactly what vanilla does when the button is clicked: echo the server's own id and
            // payload back to it. No client-side interpretation of either.
            client.getConnection().send(
                    new ServerboundCustomClickActionPacket(custom.id(), custom.payload()));
        } else {
            return false; // can't happen — findButton only returns those two — but never fire blind
        }
        ShinyHunterClient.LOGGER.info("Accepted quest — fired {}", describe(button));

        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §aAccepted."));
        }
        return true;
    }

    /** Drops any outstanding prompt — used when the world changes. */
    public static void reset() {
        pending = null;
        lastMobLineAt = 0L;
    }
}
