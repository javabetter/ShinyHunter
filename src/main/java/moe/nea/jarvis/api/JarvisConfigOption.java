package moe.nea.jarvis.api;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Stand-in for one searchable setting in the JARVIS config index. See package docs: only the
 * members this mod implements are declared; the real interface adds defaults we inherit at runtime.
 */
public interface JarvisConfigOption {
    Component title();

    Component category();

    List<Component> description();

    Screen jumpTo(Screen parentScreen);

    boolean match(String phrase);
}
