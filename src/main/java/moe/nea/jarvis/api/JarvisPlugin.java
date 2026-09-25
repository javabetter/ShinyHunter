package moe.nea.jarvis.api;

import java.util.List;

/** Stand-in for a mod's JARVIS plugin, found via the {@code jarvis} entrypoint. See package docs. */
public interface JarvisPlugin {
    String getModId();

    List<JarvisHud> getAllHuds();

    List<JarvisConfigOption> getAllConfigOptions();

    void onInitialize(Jarvis jarvis);
}
