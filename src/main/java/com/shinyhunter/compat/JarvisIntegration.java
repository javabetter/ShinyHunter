package com.shinyhunter.compat;

import com.shinyhunter.ShinyHunterClient;
import com.shinyhunter.gui.HudOverlay;
import com.shinyhunter.gui.HudPanel;
import com.shinyhunter.gui.ConfigScreen;
import moe.nea.jarvis.api.Jarvis;
import moe.nea.jarvis.api.JarvisConfigOption;
import moe.nea.jarvis.api.JarvisHud;
import moe.nea.jarvis.api.JarvisPlugin;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes this mod's HUD panels to the JARVIS editor, so they can be dragged and resized alongside
 * every other mod's HUD in one place ({@code /jarvis gui}).
 *
 * <p>Reached through the {@code jarvis} entrypoint, which only a mod providing JARVIS ever reads —
 * Firmament, for instance, which vendors the whole library. With none installed this class is never
 * loaded, which is exactly why the JARVIS types are confined to it: the rendering path stays free of
 * them and works unchanged on its own.
 *
 * <p>Each adapter is a thin view over a {@link HudPanel}: position and scale read and write straight
 * through to the panel's saved placement, so a drag in the editor persists to the config with no
 * separate copy of the layout to fall out of step.
 */
public class JarvisIntegration implements JarvisPlugin {

    @Override
    public String getModId() {
        return "shinyhunter";
    }

    @Override
    public List<JarvisHud> getAllHuds() {
        List<JarvisHud> huds = new ArrayList<>();
        for (HudPanel panel : HudOverlay.panels()) {
            huds.add(new PanelHud(panel));
        }
        return huds;
    }

    /**
     * Every setting, so {@code /jarvis options} — the search Firmament ships — can find them by
     * name or by what they do, and open this mod's screen with that one setting showing.
     */
    @Override
    public List<JarvisConfigOption> getAllConfigOptions() {
        List<JarvisConfigOption> options = new ArrayList<>();
        for (ConfigScreen.Option option : ConfigScreen.options()) {
            options.add(new SettingOption(option));
        }
        return options;
    }

    @Override
    public void onInitialize(Jarvis jarvis) {
        ShinyHunterClient.LOGGER.info(
                "JARVIS detected — {} HUD panel(s) in /jarvis gui, {} setting(s) in /jarvis options",
                HudOverlay.panels().size(), ConfigScreen.options().size());
    }

    /**
     * One setting, presented to JARVIS. Jumping opens the settings screen with the setting's own
     * name in the search box, which leaves that row (under its section heading) on screen by
     * itself — the screen's existing search is what does the scrolling.
     */
    private record SettingOption(ConfigScreen.Option option) implements JarvisConfigOption {

        @Override
        public Component title() {
            return Component.literal(option.label());
        }

        @Override
        public Component category() {
            return option.section() == null ? null : Component.literal(option.section());
        }

        @Override
        public List<Component> description() {
            return option.tooltip() == null || option.tooltip().isBlank()
                    ? List.of()
                    : List.of(Component.literal(option.tooltip()));
        }

        @Override
        public Screen jumpTo(Screen parentScreen) {
            // Straight to the option in the MoulConfig screen when the field is known; the label
            // search is the fallback for the list editors, which have no field of their own.
            return com.shinyhunter.gui.moul.MoulConfigBridge.screenAtLabel(
                    parentScreen, option.section(), option.label());
        }

        @Override
        public boolean match(String phrase) {
            if (phrase == null || phrase.isBlank()) {
                return false;
            }
            String needle = phrase.toLowerCase();
            // The section name counts as a search tag, so "safari manager" finds its settings.
            return option.section() != null && option.section().toLowerCase().contains(needle);
        }
    }

    /** One panel, presented to JARVIS. */
    private static final class PanelHud implements JarvisHud.Scalable {

        private final HudPanel panel;

        private PanelHud(HudPanel panel) {
            this.panel = panel;
        }

        @Override
        public Identifier getHudId() {
            return Identifier.fromNamespaceAndPath("shinyhunter", panel.id());
        }

        @Override
        public Vector2ic getPosition() {
            return new Vector2i(panel.x(), panel.y());
        }

        @Override
        public void setPosition(Vector2ic position) {
            panel.setPosition(position.x(), position.y());
        }

        @Override
        public boolean isEnabled() {
            return com.shinyhunter.ShinyConfig.get().showHud;
        }

        @Override
        public boolean isVisible() {
            // Always visible to the editor, even with nothing to show right now. A panel that
            // vanished whenever it was empty could only be positioned mid-run, which is the worst
            // possible moment to be fiddling with layout.
            return true;
        }

        @Override
        public int getUnscaledWidth() {
            return panel.width();
        }

        @Override
        public int getUnscaledHeight() {
            return panel.height();
        }

        @Override
        public Component getLabel() {
            return Component.literal(panel.label());
        }

        @Override
        public float getScale() {
            return panel.scale();
        }

        @Override
        public void setScale(float scale) {
            panel.setScale(scale);
        }
    }
}
