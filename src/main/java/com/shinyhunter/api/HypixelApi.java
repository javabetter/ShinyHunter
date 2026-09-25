package com.shinyhunter.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shinyhunter.ShinyHunterClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches players' Skyblock profiles through the Shiny Hunter API, a small proxy that holds the
 * Hypixel API key so the mod never ships one. It answers with the same shape as Hypixel's own
 * {@code /v2/skyblock/profiles}, trimmed to the Safari fields the mod reads.
 *
 * <p><b>The proxy needs no sign-in.</b> Mojang's session server refuses requests from Cloudflare
 * Workers, so the proxy can't check who's asking; it limits by address instead and only ever
 * returns trimmed, public Safari data. Nothing about the player's login is sent anywhere.
 *
 * <p>Two calls per player: Mojang's name-to-UUID lookup, then the proxy. Both run off the render
 * thread; callers get a future and hop back onto the client thread themselves before touching any
 * game state.
 *
 * <p>Results are cached for a while because a party forms in a burst: four members join within a
 * minute, and the same names come and go across repeated runs. The proxy's limits are generous but
 * not infinite, and the data doesn't change faster than a run lasts.
 */
public final class HypixelApi {

    private static final String MOJANG_LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_MILLIS = 10 * 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Name (lower case) to dashless UUID. Names don't change often enough to expire these. */
    private static final Map<String, String> UUIDS = new ConcurrentHashMap<>();

    /** UUID to a timestamped profiles response. */
    private static final Map<String, Cached> PROFILES_CACHE = new ConcurrentHashMap<>();

    private record Cached(JsonObject body, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CACHE_MILLIS;
        }
    }

    private HypixelApi() {
    }

    /**
     * True when player data can be fetched at all — when the build names a proxy. (Named for the
     * API key it used to need; every caller means "is the API usable".)
     */
    public static boolean hasKey() {
        return !com.shinyhunter.Edition.PROXY_URL.isEmpty();
    }

    /** A resolved player: the UUID the profile is keyed by, and the profiles response. */
    public record Lookup(String uuid, JsonObject profiles) {
    }

    /** The full {@code /skyblock/profiles} response for the named player, cached. */
    public static CompletableFuture<Lookup> profiles(String name) {
        if (!hasKey()) {
            return CompletableFuture.failedFuture(new IllegalStateException("This build has no Shiny Hunter API address"));
        }
        return uuidOf(name).thenCompose(uuid ->
                profilesByUuid(uuid).thenApply(body -> new Lookup(uuid, body)));
    }

    private static CompletableFuture<String> uuidOf(String name) {
        String key = name.toLowerCase();
        String known = UUIDS.get(key);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(MOJANG_LOOKUP + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mojang lookup for " + name + " returned "
                        + response.statusCode());
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            String id = body.get("id").getAsString();
            UUIDS.put(key, id);
            return id;
        });
    }

    private static CompletableFuture<JsonObject> profilesByUuid(String uuid) {
        Cached cached = PROFILES_CACHE.get(uuid);
        if (cached != null && cached.fresh()) {
            return CompletableFuture.completedFuture(cached.body());
        }
        return viaProxy(uuid);
    }

    // ------------------------------------------------------------------ the proxy

    /**
     * The profile through the proxy. The answer has the same shape as Hypixel's own (trimmed to the
     * fields the mod reads), so everything downstream parses it with the same code. No sign-in:
     * the proxy limits by address instead (Mojang won't answer it, so it can't check accounts).
     */
    private static CompletableFuture<JsonObject> viaProxy(String uuid) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(com.shinyhunter.Edition.PROXY_URL + "/profile/" + uuid))
                .timeout(TIMEOUT)
                .header("User-Agent", com.shinyhunter.Edition.NAME)
                .GET()
                .build();
        ShinyHunterClient.LOGGER.info("Shiny Hunter API: fetching profiles for {}", uuid);
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() == 429) {
                throw new IllegalStateException("Shiny Hunter API is busy (429) — try again in a minute");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Shiny Hunter API returned " + response.statusCode());
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            PROFILES_CACHE.put(uuid, new Cached(body, System.currentTimeMillis()));
            return body;
        });
    }

    public static void clearCache() {
        PROFILES_CACHE.clear();
    }
}
