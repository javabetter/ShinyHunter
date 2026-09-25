package moe.nea.jarvis.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Vector2ic;

/** Stand-in for a positionable HUD element. See package docs. */
public interface JarvisHud {
    Identifier getHudId();

    Vector2ic getPosition();

    void setPosition(Vector2ic position);

    boolean isEnabled();

    boolean isVisible();

    int getUnscaledWidth();

    int getUnscaledHeight();

    Component getLabel();

    /** Stand-in for a HUD element the editor can also resize. */
    interface Scalable extends JarvisHud {
        float getScale();

        void setScale(float scale);
    }
}
