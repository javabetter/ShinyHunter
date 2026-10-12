package com.shinyhunter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shinyhunter.api.HypixelApi;
import com.shinyhunter.api.PlayerDex;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Your own Safari numbers from Hypixel's API, so achievements and the dex count everything from
 * before the mod was installed: the sparkling dex, runs and critters caught.
 *
 * <p>Runs come from the bestiary: Doomspiral, Wumpa and Hideyho each appear once a run, so the
 * highest of their three kill counts is the number of runs. Critters caught is the sum of the 37
 * Safari critters' kill counts (your own catches). Read when you join SkyBlock, then every ten
 * minutes and after each run.
 */
public final class OwnStats {

    private static final long REFRESH_MILLIS = 10 * 60_000L;
    private static final String[] ONCE_A_RUN = {"doomspiral_1", "wumpa_1", "hideyho_1"};

    private static long fetchedAt;
    private static boolean fetching;
    private static Set<String> dex = Set.of();
    private static long runs;
    private static long caught;

    private OwnStats() {
    }

    public static Set<String> dex() {
        return dex;
    }

    public static long runs() {
        return runs;
    }

    public static long caught() {
        return caught;
    }

    /** Read again on the next tick, e.g. after a run ends. */
    public static void refreshSoon() {
        fetchedAt = 0;
    }

    public static void onClientTick(Minecraft client) {
        if (fetching || client.player == null || System.currentTimeMillis() - fetchedAt < REFRESH_MILLIS
                || !HypixelApi.hasKey() || !SkyblockSidebar.inSkyblock(client)) {
            return;
        }
        fetching = true;
        fetchedAt = System.currentTimeMillis();
        String name = client.player.getName().getString();
        HypixelApi.profiles(name).whenComplete((result, error) -> client.execute(() -> {
            fetching = false;
            if (error != null) {
                ShinyHunterClient.LOGGER.info("Own Safari stats lookup failed: {}", error.getMessage());
                return;
            }
            dex = Set.copyOf(PlayerDex.from(name, result.uuid(), result.profiles()).caught());
            JsonObject kills = kills(result.profiles(), result.uuid());
            if (kills != null) {
                long most = 0;
                for (String id : ONCE_A_RUN) {
                    most = Math.max(most, count(kills, id));
                }
                long total = 0;
                for (String biome : CritterDex.biomes()) {
                    for (String critter : CritterDex.critters(biome)) {
                        total += count(kills, critter.toLowerCase(Locale.ROOT).replace(' ', '_') + "_1");
                    }
                }
                runs = most;
                caught = total;
            }
            int earned = Achievements.afterApiStats();
            ShinyHunterClient.LOGGER.info("Own Safari stats: {} sparklings in the dex, {} runs, {} caught", dex.size(), runs, caught);
            if (earned > 0 && client.player != null) {
                client.player.sendSystemMessage(Component.literal("§b[Shiny Hunter] §fYour Hypixel profile earns §e" + earned
                        + "§f achievement" + (earned == 1 ? "" : "s") + ". §7See /shiny achievements."));
            }
        }));
    }

    /** The selected profile's {@code bestiary.kills} for this player, or null. */
    static JsonObject kills(JsonObject profiles, String uuid) {
        if (profiles == null || !profiles.has("profiles") || !profiles.get("profiles").isJsonArray()) {
            return null;
        }
        for (JsonElement element : profiles.getAsJsonArray("profiles")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject profile = element.getAsJsonObject();
            if (!profile.has("selected") || !profile.get("selected").getAsBoolean()) {
                continue;
            }
            JsonElement node = profile;
            for (String part : new String[] {"members", uuid.replace("-", ""), "bestiary", "kills"}) {
                if (node == null || !node.isJsonObject()) {
                    return null;
                }
                node = node.getAsJsonObject().get(part);
            }
            return node != null && node.isJsonObject() ? node.getAsJsonObject() : null;
        }
        return null;
    }

    private static long count(JsonObject kills, String id) {
        JsonElement value = kills.get(id);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsLong() : 0;
    }
}
