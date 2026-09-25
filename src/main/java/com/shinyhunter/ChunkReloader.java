package com.shinyhunter;

import com.mojang.blaze3d.platform.InputConstants;
import com.shinyhunter.gui.KeybindPromptScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.Identifier;

/**
 * A key that does what F3+A does — rebuild every loaded chunk — without the chat line, and that
 * keeps firing while held.
 *
 * <p>F3+A is just {@code LevelRenderer.allChanged()} plus a debug message, so this calls the same
 * method. It's entirely client-side: the chunks are re-meshed from data already in memory and
 * nothing is sent to the server.
 *
 * <p>Unbound by default (there's no key that's safe to take from everyone), so the first launch
 * shows {@link KeybindPromptScreen} once to ask for one.
 */
public final class ChunkReloader {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("shinyhunter", "main"));

    public static final KeyMapping RELOAD_CHUNKS = new KeyMapping(
            "key.shinyhunter.reload_chunks", InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), CATEGORY);

    /** Ticks the key has been held; 0 when it's up. */
    private static int heldTicks;

    private ChunkReloader() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(RELOAD_CHUNKS);
    }

    public static void onClientTick(Minecraft client) {
        promptOnFirstLaunch(client);

        // A tap shorter than a tick still queues a click, so count that as a press as well.
        boolean tapped = false;
        while (RELOAD_CHUNKS.consumeClick()) {
            tapped = true;
        }
        boolean down = RELOAD_CHUNKS.isDown();

        if (client.level == null || (!down && !tapped)) {
            heldTicks = 0;
            return;
        }

        int every = Math.max(1, ShinyConfig.get().chunkReloadRepeatTicks);
        if (heldTicks == 0 || heldTicks % every == 0) {
            client.levelRenderer.allChanged();
        }
        heldTicks = down ? heldTicks + 1 : 0;
    }

    /** The one-time ask, on the first title screen after installing. */
    private static void promptOnFirstLaunch(Minecraft client) {
        if (client.screen instanceof TitleScreen title && !ShinyConfig.get().chunkReloadKeyPrompted) {
            client.setScreen(new KeybindPromptScreen(title, RELOAD_CHUNKS));
        }
    }
}
