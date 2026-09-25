package com.shinyhunter.gui;

import com.shinyhunter.ShinyConfig;

import java.util.List;
import java.util.function.Supplier;

/**
 * One on-screen panel: what it's called, what it draws, and where it sits.
 *
 * <p>Deliberately free of any JARVIS types. The HUD has to work with JARVIS absent, and referencing
 * its classes from the rendering path would mean a missing-class failure on every frame for anyone
 * without it. The JARVIS adapter lives in the compat package and reads through this instead.
 *
 * <p>Placement is stored per panel in the config. A panel that has never been moved has none, and
 * falls back to the automatic vertical stack — so the HUD is tidy out of the box, and stays exactly
 * where it's put once someone arranges it.
 */
public final class HudPanel {

    private final String id;
    private final String label;
    private final Supplier<List<String>> lines;

    /** Size of the last frame this panel drew, so the editor knows how big its box is. */
    private int lastWidth;
    private int lastHeight;

    HudPanel(String id, String label, Supplier<List<String>> lines) {
        this.id = id;
        this.label = label;
        this.lines = lines;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public List<String> lines() {
        return lines.get();
    }

    // ------------------------------------------------------------------ placement

    /** The saved placement, or null when this panel still uses the automatic stack. */
    public ShinyConfig.PanelPlacement placement() {
        return ShinyConfig.get().hudPanels.get(id);
    }

    public boolean isPlaced() {
        return placement() != null;
    }

    public int x() {
        ShinyConfig.PanelPlacement placement = placement();
        return placement == null ? ShinyConfig.get().hudX : placement.x;
    }

    public int y() {
        ShinyConfig.PanelPlacement placement = placement();
        return placement == null ? ShinyConfig.get().hudY : placement.y;
    }

    public float scale() {
        ShinyConfig.PanelPlacement placement = placement();
        return placement == null ? 1.0f : placement.scale;
    }

    /** Moving a panel gives it a placement, taking it out of the automatic stack for good. */
    public void setPosition(int x, int y) {
        ShinyConfig config = ShinyConfig.get();
        ShinyConfig.PanelPlacement placement = config.hudPanels
                .computeIfAbsent(id, key -> new ShinyConfig.PanelPlacement());
        placement.x = x;
        placement.y = y;
        config.save();
    }

    public void setScale(float scale) {
        ShinyConfig config = ShinyConfig.get();
        ShinyConfig.PanelPlacement placement = config.hudPanels
                .computeIfAbsent(id, key -> new ShinyConfig.PanelPlacement());
        placement.scale = scale;
        config.save();
    }

    // ------------------------------------------------------------------ measured size

    void recordSize(int width, int height) {
        lastWidth = width;
        lastHeight = height;
    }

    /**
     * Width of the last draw. Falls back to a placeholder when the panel has never had anything to
     * show, so it still has a grabbable box in the editor rather than collapsing to nothing.
     */
    public int width() {
        return lastWidth > 0 ? lastWidth : 90;
    }

    public int height() {
        return lastHeight > 0 ? lastHeight : 16;
    }
}
