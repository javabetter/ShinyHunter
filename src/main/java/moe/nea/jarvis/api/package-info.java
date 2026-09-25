/**
 * Compile-time stand-ins for the JARVIS HUD API.
 *
 * <p>JARVIS is a shared HUD position/scale editor that several Hypixel SkyBlock mods plug into.
 * It has no published maven artifact — Firmament vendors the source — so rather than depend on a
 * mod jar by local path, these declare just enough of the interfaces to compile against.
 *
 * <p><b>These are excluded from the shipped jar</b> (see {@code jar { exclude }} in build.gradle).
 * At runtime the real classes come from whichever mod provides JARVIS; shipping a second copy
 * could shadow it and break the integration for everyone.
 *
 * <p>Only the abstract members this mod implements are declared. The real interfaces have extra
 * default methods, which are inherited normally at runtime.
 */
package moe.nea.jarvis.api;
