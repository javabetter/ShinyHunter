package com.shinyhunter.gui.moul;

import com.shinyhunter.Edition;
import com.shinyhunter.ShinyConfig;
import com.shinyhunter.ShinyHunterClient;
import com.shinyhunter.gui.ConfigScreen;
import io.github.notenoughupdates.moulconfig.gui.GuiContext;
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent;
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor;
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent;
import io.github.notenoughupdates.moulconfig.processor.ProcessedCategory;
import io.github.notenoughupdates.moulconfig.processor.ProcessedOption;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the settings with MoulConfig — the library Firmament's own config screen is built on — so
 * this mod's settings look and behave like the rest of a SkyBlock setup.
 *
 * <p><b>The mod's own config file stays authoritative.</b> {@link ShinyMoulConfig} is a mirror
 * whose fields are named after {@link ShinyConfig}'s, and values are copied across by reflection:
 * in when the screen opens, back out whenever MoulConfig saves. Everything else in the mod keeps
 * reading {@code ShinyConfig.get()}, an existing {@code shinyhunter.json} keeps working, and the
 * migration logic in {@link ShinyConfig} stays the only place that has to understand old files.
 *
 * <p>Settings that are lists — keywords, trackers, block trackers, particle and texture hashes,
 * per-biome critter limits — have no MoulConfig editor that fits, so each one keeps an "Edit"
 * button that opens the mod's own screen filtered to that section.
 */
public final class MoulConfigBridge {

    private static ManagedConfig<ShinyMoulConfig> managed;

    /** Set while the MoulConfig screen is open, so a save can be pushed back to the real config. */
    private static MoulConfigEditor<ShinyMoulConfig> editor;

    /** The screen we opened, watched so its closing can be turned into a save. */
    private static Screen openScreen;

    private MoulConfigBridge() {
    }

    // ------------------------------------------------------------------ screen

    /**
     * The settings screen. Falls back to the mod's own screen if MoulConfig isn't there — it's
     * bundled, so that only happens if a launcher has stripped the nested jar.
     */
    public static Screen screen(Screen parent) {
        try {
            return moulScreen(parent, null);
        } catch (Throwable e) {
            ShinyHunterClient.LOGGER.warn("MoulConfig unavailable — using the built-in screen", e);
            return new ConfigScreen(parent);
        }
    }

    /**
     * The settings screen scrolled to the option with this label in this section — how the JARVIS
     * search opens a setting. The label is matched against the mirror's own {@code @ConfigOption}
     * names, which are generated from the same declarations the search list came from.
     */
    public static Screen screenAtLabel(Screen parent, String section, String label) {
        return screenAt(parent, fieldForLabel(section, label));
    }

    private static String fieldForLabel(String section, String label) {
        for (Field categoryField : ShinyMoulConfig.class.getFields()) {
            var category = categoryField.getAnnotation(
                    io.github.notenoughupdates.moulconfig.annotations.Category.class);
            if (category == null || (section != null && !category.name().equals(section))) {
                continue;
            }
            for (Field option : categoryField.getType().getFields()) {
                var annotation = option.getAnnotation(
                        io.github.notenoughupdates.moulconfig.annotations.ConfigOption.class);
                if (annotation != null && annotation.name().equals(label)) {
                    return option.getName();
                }
            }
        }
        return null;
    }

    /** The same screen, scrolled to one setting. Used by the JARVIS config search. */
    public static Screen screenAt(Screen parent, String fieldName) {
        try {
            return moulScreen(parent, fieldName);
        } catch (Throwable e) {
            ShinyHunterClient.LOGGER.warn("MoulConfig unavailable — using the built-in screen", e);
            return new ConfigScreen(parent);
        }
    }

    private static Screen moulScreen(Screen parent, String fieldName) {
        ManagedConfig<ShinyMoulConfig> config = managed();
        pull(config.getInstance());

        editor = new MoulConfigEditor<>(config.getProcessor().getAllCategories(), config.getInstance());
        if (fieldName != null) {
            scrollTo(config, fieldName);
        }
        GuiContext context = new GuiContext(new GuiElementComponent(editor));
        openScreen = new MoulConfigScreenComponent(Component.literal(Edition.NAME), context, parent);
        return openScreen;
    }

    private static void scrollTo(ManagedConfig<ShinyMoulConfig> config, String fieldName) {
        for (ProcessedCategory category : config.getProcessor().getAllCategories().values()) {
            for (ProcessedOption option : category.getOptions()) {
                if (fieldOf(option) != null && fieldOf(option).getName().equals(fieldName)) {
                    editor.setSelectedCategory(category);
                    editor.scrollOptionIntoView(option, 0);
                    return;
                }
            }
        }
    }


    /**
     * Writes the screen's changes back when it closes.
     *
     * <p>MoulConfig edits the object in memory and leaves persistence to the mod — it has no
     * "changed" callback of its own — so without this every toggle looked like it reverted the
     * next time the screen was opened, because opening copies the real config back over the
     * mirror. Also pushed periodically while the screen is up, so a crash mid-session doesn't
     * throw the changes away.
     */
    public static void onClientTick(Minecraft client) {
        if (openScreen == null) {
            return;
        }
        if (client.screen == openScreen) {
            if (++ticksOpen % PUSH_INTERVAL_TICKS == 0) {
                push(managed.getInstance());
            }
            return;
        }
        // Gone: either closed, or swapped for the list editor, which pushes for itself.
        ticksOpen = 0;
        Screen closed = openScreen;
        openScreen = null;
        if (managed != null) {
            push(managed.getInstance());
            ShinyHunterClient.LOGGER.info("Settings saved ({} closed)", closed.getClass().getSimpleName());
        }
    }

    private static int ticksOpen;

    /** Often enough that a crash costs at most a few seconds of edits. */
    private static final int PUSH_INTERVAL_TICKS = 40;

    // ------------------------------------------------------------------ the mirror

    private static ManagedConfig<ShinyMoulConfig> managed() {
        if (managed == null) {
            // The file only ever holds a copy; it's rewritten from shinyhunter.json every time the
            // screen opens, so nothing is lost if it's deleted.
            java.io.File file = FabricLoader.getInstance().getConfigDir()
                    .resolve("shinyhunter-gui.json").toFile();
            managed = ManagedConfig.create(file, ShinyMoulConfig.class);
            // MoulConfig saves the mirror on every change; that's where the real config is written.
            managed.getInstance().saveRunnables.add(() -> push(managed.getInstance()));
        }
        return managed;
    }

    /** Copies the real config into the mirror. */
    private static void pull(ShinyMoulConfig mirror) {
        copy(mirror, true);
    }

    /** Copies the mirror back into the real config and saves it. */
    private static void push(ShinyMoulConfig mirror) {
        copy(mirror, false);
        ShinyConfig.get().save();
    }

    /**
     * Copies every mirror field to or from the {@link ShinyConfig} field of the same name.
     *
     * <p>Names are the contract between the two classes, and the mirror is generated from the same
     * declarations, so a mismatch means a stale generated file rather than a runtime surprise — it
     * is logged once and skipped rather than throwing in the middle of opening a screen.
     */
    private static void copy(ShinyMoulConfig mirror, boolean intoMirror) {
        ShinyConfig real = ShinyConfig.get();
        for (Field categoryField : ShinyMoulConfig.class.getFields()) {
            if (categoryField.getAnnotation(io.github.notenoughupdates.moulconfig.annotations.Category.class) == null) {
                continue;
            }
            Object category;
            try {
                category = categoryField.get(mirror);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (category == null) {
                continue;
            }
            for (Field option : category.getClass().getFields()) {
                if (Modifier.isStatic(option.getModifiers())
                        || option.getAnnotation(io.github.notenoughupdates.moulconfig.annotations.ConfigOption.class) == null
                        || option.getAnnotation(io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton.class) != null) {
                    continue;
                }
                try {
                    Field target = ShinyConfig.class.getField(option.getName());
                    if (intoMirror) {
                        option.set(category, target.get(real));
                    } else {
                        target.set(real, option.get(category));
                    }
                } catch (NoSuchFieldException e) {
                    ShinyHunterClient.LOGGER.warn("Settings mirror has no match for {} — regenerate it",
                            option.getName());
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    ShinyHunterClient.LOGGER.warn("Couldn't copy setting {}", option.getName(), e);
                }
            }
        }
    }

    private static Field fieldOf(ProcessedOption option) {
        // ProcessedOption has no getField(); the path is "category.field", which is enough to find
        // the mirror field by name without depending on the implementation class.
        String path = option.getPath();
        if (path == null) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('.') + 1);
        for (Field categoryField : ShinyMoulConfig.class.getFields()) {
            for (Field candidate : categoryField.getType().getFields()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ list editors

    /** Opens the mod's own screen filtered to one section, for the settings MoulConfig can't draw. */
    public static void openListEditor(String section) {
        Minecraft client = Minecraft.getInstance();
        // Bank whatever was changed before this screen goes away — the list editor writes straight
        // to the real config, and a later push from a stale mirror would undo it.
        if (managed != null) {
            push(managed.getInstance());
        }
        openScreen = null;
        Screen parent = client.screen;
        client.setScreen(new ConfigScreen(parent, section));
    }
}
