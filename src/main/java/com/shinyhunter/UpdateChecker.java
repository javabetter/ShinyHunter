package com.shinyhunter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tells the player, once per game session, when a newer Shiny Hunter is out.
 *
 * <p>Checked on joining Hypixel rather than at launch, so the line lands in chat where it's seen.
 * The only request is one read of the public GitHub releases API; nothing about the player is sent.
 * The link opens the release page through the game's usual "open this link?" prompt.
 */
public final class UpdateChecker {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/javabetter/ShinyHunter/releases/latest";

    /** A short pause after joining so the notice lands after the server's own join messages. */
    private static final int DELAY_TICKS = 100;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private record Release(String version, String url) {
    }

    /** Set once a check has been started this session; the check happens at most once. */
    private static boolean checked;
    /** The newer release found, waiting to be shown; null when there's nothing to say. */
    private static volatile Release pending;
    private static int countdown = -1;

    private UpdateChecker() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientTickEvents.END_CLIENT_TICK.register(UpdateChecker::onClientTick);
    }

    private static void onJoin(Minecraft client) {
        if (checked || !ShinyConfig.get().checkForUpdates || !onHypixel(client)) {
            return;
        }
        checked = true;
        String current = currentVersion();
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Edition.NAME + " " + current)
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() != 200) {
                ShinyHunterClient.LOGGER.info("Update check: GitHub returned {}", response.statusCode());
                return;
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            String latest = body.get("tag_name").getAsString();
            String url = body.get("html_url").getAsString();
            if (isNewer(latest, current)) {
                ShinyHunterClient.LOGGER.info("Update available: {} (running {})", latest, current);
                pending = new Release(latest, url);
            } else {
                ShinyHunterClient.LOGGER.info("Up to date ({}; latest release {})", current, latest);
            }
        }).exceptionally(error -> {
            ShinyHunterClient.LOGGER.info("Update check failed: {}", error.toString());
            return null;
        });
    }

    private static void onClientTick(Minecraft client) {
        Release release = pending;
        if (release == null || client.player == null) {
            return;
        }
        if (countdown < 0) {
            countdown = DELAY_TICKS;
        }
        if (--countdown > 0) {
            return;
        }
        pending = null;
        show(client, release);
    }

    private static void show(Minecraft client, Release release) {
        Component link = Component.literal("[Open the release page]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(release.url())))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(release.url()))));
        // Straight into the chat box rather than through the player: the mod's chat listeners never
        // see it, so it can't be mistaken for a server line.
        client.gui.getChat().addClientSystemMessage(Component.literal("§b[Shiny Hunter] §fA new version is out: §a"
                + release.version() + " §7(you have v" + currentVersion() + ") ").append(link));
    }

    private static boolean onHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase().contains("hypixel");
    }

    private static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("shinyhunter")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("0");
    }

    /** Compares dotted version numbers ("v2.1" vs "2.0"); a leading "v" is ignored. */
    static boolean isNewer(String latest, String current) {
        int[] a = parts(latest);
        int[] b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        String[] pieces = version.trim().replaceFirst("^[vV]", "").split("[.\\-+]");
        int[] numbers = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                numbers[i] = Integer.parseInt(pieces[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
