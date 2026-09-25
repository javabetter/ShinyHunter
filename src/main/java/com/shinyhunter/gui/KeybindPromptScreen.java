package com.shinyhunter.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.shinyhunter.Edition;
import com.shinyhunter.ShinyConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Asked once, on the first launch with the mod: "press the key you want for Reload chunks".
 *
 * <p>Any key (or an extra mouse button) binds it on the spot, the same as the Controls screen would;
 * Esc or Skip leaves it unbound. Either way the question isn't asked again — it can always be
 * changed in Options → Controls → Key Binds, or reopened with {@code /shiny reloadkey}.
 */
public final class KeybindPromptScreen extends Screen {

    private final Screen parent;
    private final KeyMapping mapping;

    /** Set once a key has been pressed: the screen then shows the result instead of the question. */
    private boolean bound;

    public KeybindPromptScreen(Screen parent, KeyMapping mapping) {
        super(Component.literal(Edition.NAME + " — Reload chunks key"));
        this.parent = parent;
        this.mapping = mapping;
    }

    @Override
    protected void init() {
        int y = height / 2 + 40;
        if (bound) {
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> finish())
                    .bounds(width / 2 - 104, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Pick another"), b -> {
                bound = false;
                rebuildWidgets();
            }).bounds(width / 2 + 4, y, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Skip (leave unbound)"), b -> finish())
                    .bounds(width / 2 - 75, y, 150, 20).build());
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (bound) {
            return super.keyPressed(event);
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            finish();
            return true;
        }
        bind(InputConstants.getKey(event));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Left and right click stay for the buttons; middle and side buttons can be bound.
        if (!bound && event.button() >= 2) {
            bind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void bind(InputConstants.Key key) {
        mapping.setKey(key);
        KeyMapping.resetMapping();
        minecraft.options.save();
        bound = true;
        rebuildWidgets();
    }

    private void finish() {
        ShinyConfig config = ShinyConfig.get();
        config.chunkReloadKeyPrompted = true;
        config.save();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        finish();
    }

    /** Other bindings on the same key, so a clash is visible before leaving the screen. */
    private List<String> clashes() {
        List<String> out = new ArrayList<>();
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && !other.isUnbound() && other.same(mapping)) {
                out.add(Component.translatable(other.getName()).getString());
            }
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = width / 2;
        int y = height / 2 - 60;
        g.centeredText(font, title, cx, y, 0xFF55FFFF);
        y += 20;

        if (!bound) {
            g.centeredText(font, "Shiny Hunter has a key that reloads all chunks, like F3+A,", cx, y, 0xFFFFFFFF);
            g.centeredText(font, "but with no chat message — and you can hold it to keep reloading.", cx, y + 11, 0xFFFFFFFF);
            g.centeredText(font, "Press the key you want to use now.", cx, y + 33, 0xFFFFFF55);
            g.centeredText(font, "(Esc to skip. You can change it later in Controls.)", cx, y + 44, 0xFFAAAAAA);
            return;
        }

        g.centeredText(font, Component.literal("Reload chunks is now bound to ")
                .append(mapping.getTranslatedKeyMessage().copy().withStyle(s -> s.withColor(0x55FF55))),
                cx, y + 11, 0xFFFFFFFF);
        List<String> clashes = clashes();
        if (!clashes.isEmpty()) {
            g.centeredText(font, "Also used by: " + String.join(", ", clashes), cx, y + 26, 0xFFFF5555);
            g.centeredText(font, "Both will fire. Pick another key if that's a problem.", cx, y + 37, 0xFFAAAAAA);
        } else {
            g.centeredText(font, "Change it any time in Options → Controls → Key Binds.", cx, y + 26, 0xFFAAAAAA);
        }
    }
}
