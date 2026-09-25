package com.shinyhunter.gui;

import com.shinyhunter.ShinyConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Arranges the HUD by hand: drag a panel to move it, scroll over it to resize it.
 *
 * <p>The panels can't draw themselves here — the HUD elements suppress themselves whenever a screen
 * is open, which is the right behaviour everywhere except inside their own editor — so this screen
 * draws each one again from the same {@link HudPanel} contents the HUD uses. What you arrange is
 * therefore exactly what you get back.
 *
 * <p>Panels with nothing to show still get a labelled placeholder box. Most of them are empty
 * outside a run, and a HUD editor that shows nothing until you're mid-Safari would be useless.
 */
public class HudEditorScreen extends Screen {

    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;

    private static final int BACKGROUND = 0x90000000;
    private static final int OUTLINE = 0xFF8FE3FF;
    private static final int OUTLINE_DRAGGING = 0xFFFFDD55;
    private static final int PLACEHOLDER = 0x60000000;

    private final Screen parent;

    private HudPanel dragging;
    /** Grab offset inside the panel, so it doesn't jump its corner to the cursor. */
    private int grabX;
    private int grabY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Shiny Hunter HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Reset positions"), b -> resetAll())
                .bounds(width / 2 - 105, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 + 5, height - 28, 100, 20).build());
    }

    private void resetAll() {
        ShinyConfig config = ShinyConfig.get();
        config.hudPanels.clear();
        config.save();
    }

    // ------------------------------------------------------------------ layout

    /**
     * Where a panel sits right now. Unplaced panels are laid out in the same running stack the HUD
     * uses, so dragging one out of the stack starts it from where it actually was.
     */
    private int[] boxOf(HudPanel panel, int stackY) {
        ShinyConfig config = ShinyConfig.get();
        int x = panel.isPlaced() ? panel.x() : config.hudX;
        int y = panel.isPlaced() ? panel.y() : stackY;
        return new int[]{x, y};
    }

    private int widthOf(HudPanel panel, List<String> lines) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        if (lines.isEmpty()) {
            width = font.width(panel.label() + " (empty)");
        }
        return Math.round((width + PADDING * 2) * panel.scale());
    }

    private int heightOf(List<String> lines, float scale) {
        int rows = Math.max(1, lines.size());
        return Math.round((rows * LINE_HEIGHT + PADDING * 2) * scale);
    }

    /** The panel under the cursor, topmost first so overlapping panels grab predictably. */
    private HudPanel panelAt(double mouseX, double mouseY) {
        List<HudPanel> panels = HudOverlay.panels();
        int stackY = ShinyConfig.get().hudY;

        HudPanel hit = null;
        for (HudPanel panel : panels) {
            List<String> lines = panel.lines();
            int[] box = boxOf(panel, stackY);
            int w = widthOf(panel, lines);
            int h = heightOf(lines, panel.scale());
            if (!panel.isPlaced()) {
                stackY += h + 2;
            }
            if (mouseX >= box[0] && mouseX <= box[0] + w && mouseY >= box[1] && mouseY <= box[1] + h) {
                hit = panel; // keep going: later panels draw on top, so they win the click
            }
        }
        return hit;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        HudPanel panel = panelAt(event.x(), event.y());
        if (panel == null) {
            return false;
        }

        int stackY = ShinyConfig.get().hudY;
        for (HudPanel other : HudOverlay.panels()) {
            if (other == panel) {
                break;
            }
            if (!other.isPlaced()) {
                stackY += heightOf(other.lines(), other.scale()) + 2;
            }
        }
        int[] box = boxOf(panel, stackY);

        dragging = panel;
        grabX = (int) Math.round(event.x() - box[0]);
        grabY = (int) Math.round(event.y() - box[1]);
        // Grabbing a stacked panel places it, so it stops moving when the panels above it change.
        panel.setPosition(box[0], box[1]);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging == null) {
            return super.mouseDragged(event, dragX, dragY);
        }
        List<String> lines = dragging.lines();
        int w = widthOf(dragging, lines);
        int h = heightOf(lines, dragging.scale());

        // Clamped so a panel can't be dragged off-screen and lost.
        int x = (int) Math.round(event.x()) - grabX;
        int y = (int) Math.round(event.y()) - grabY;
        dragging.setPosition(
                Math.max(0, Math.min(width - w, x)),
                Math.max(0, Math.min(height - h, y)));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        HudPanel panel = panelAt(mouseX, mouseY);
        if (panel == null) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        float scale = (float) Math.clamp(panel.scale() + scrollY * 0.1, 0.25, 4.0);
        // Scrolling also places the panel: a resized panel in the automatic stack would jump around.
        if (!panel.isPlaced()) {
            panel.setPosition(panel.x(), panel.y());
        }
        panel.setScale(scale);
        return true;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x50000000);

        HudPanel hovered = panelAt(mouseX, mouseY);
        int stackY = ShinyConfig.get().hudY;

        for (HudPanel panel : HudOverlay.panels()) {
            List<String> lines = panel.lines();
            int[] box = boxOf(panel, stackY);
            int w = widthOf(panel, lines);
            int h = heightOf(lines, panel.scale());
            if (!panel.isPlaced()) {
                stackY += h + 2;
            }
            drawPanel(g, panel, box[0], box[1], w, h, lines, panel == hovered || panel == dragging);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("§7Drag to move · scroll to resize"),
                width / 2, 20, 0xFFAAAAAA);

        if (hovered != null) {
            g.centeredText(font, Component.literal(
                            "§f" + hovered.label() + " §7· " + Math.round(hovered.scale() * 100) + "%"),
                    width / 2, height - 42, 0xFFFFFFFF);
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, HudPanel panel, int x, int y, int w, int h,
                           List<String> lines, boolean highlighted) {
        g.fill(x, y, x + w, y + h, lines.isEmpty() ? PLACEHOLDER : BACKGROUND);

        if (highlighted) {
            int colour = panel == dragging ? OUTLINE_DRAGGING : OUTLINE;
            g.fill(x, y, x + w, y + 1, colour);
            g.fill(x, y + h - 1, x + w, y + h, colour);
            g.fill(x, y, x + 1, y + h, colour);
            g.fill(x + w - 1, y, x + w, y + h, colour);
        }

        float scale = panel.scale();
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);

        if (lines.isEmpty()) {
            g.text(font, Component.literal("§8" + panel.label() + " (empty)"), PADDING, PADDING,
                    0xFFAAAAAA, false);
        } else {
            int lineY = PADDING;
            for (String line : lines) {
                g.text(font, Component.literal(line), PADDING, lineY, 0xFFFFFFFF, true);
                lineY += LINE_HEIGHT;
            }
        }
        pose.popMatrix();
    }

    @Override
    public void onClose() {
        ShinyConfig.get().save();
        minecraft.setScreenAndShow(parent);
    }
}
