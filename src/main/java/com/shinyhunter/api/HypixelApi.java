package com.shinyhunter.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shinyhunter.ShinyConfig;
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
 * Talks to the Hypixel API — directly on the player's own key (private edition), or through the
 * Shiny Hunter proxy (public edition), which holds the key so the mod never ships one.
 *
 * <p><b>The proxy needs no sign-in.</b> Mojang's session server refuses requests from Cloudflare
 * Workers, so the proxy can't check who's asking; it limits by address instead and only ever
 * returns trimmed, public Safari data. Nothing about the player's login is sent anywhere.
 *
 * <p>Original notes for the direct path:
 *
 * <p>Two calls per player: Mojang's name-to-UUID lookup (the Hypixel API takes UUIDs only), then
 * {@code /v2/skyblock/profiles}. Both run off the render thread; callers get a future and hop back
 * onto the client thread themselves before touching any game state.
 *
 * <p><b>The key never leaves the request header and never reaches the log.</b> It is read from the
 * config at call time — not cached here — so changing it takes effect on the next lookup, and
 * nothing in this class prints a request beyond its host and path.
 *
 * <p>Results are cached for a while because a party forms in a burst: four members join within a
 * minute, and the same names come and go across repeated runs. Hypixel's key limit is generous but
 * not infinite, and the data doesn't change faster than a run lasts.
 */
public final class HypixelApi {

    private static final String MOJANG_LOOKUP = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";

    private static final String PROFILES = "https://api.hypixel.net/v2/skyblock/profiles?uuid=";

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
     * True when player data can be fetched at all: through the proxy, or on a configured key.
     * (Named for the key it used to be the only way in; every caller means "is the API usable".)
     */
    public static boolean hasKey() {
        return useProxy() || ownKey() != null;
    }

    private static String ownKey() {
        String key = ShinyConfig.get().hypixelApiKey;
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * The public edition always uses the proxy. The private one uses its own key when it has one,
     * and falls back to the proxy when it doesn't.
     */
    private static boolean useProxy() {
        if (com.shinyhunter.Edition.PROXY_URL.isEmpty()) {
            return false;
        }
        return com.shinyhunter.Edition.PUBLIC || ownKey() == null;
    }

    /** A resolved player: the UUID the profile is keyed by, and the profiles response. */
    public record Lookup(String uuid, JsonObject profiles) {
    }

    /** The full {@code /skyblock/profiles} response for the named player, cached. */
    public static CompletableFuture<Lookup> profiles(String name) {
        if (!hasKey()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No Hypixel API key set"));
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
        if (useProxy()) {
            return viaProxy(uuid);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILES + uuid))
                .timeout(TIMEOUT)
                .header("API-Key", ownKey())
                .GET()
                .build();
        ShinyHunterClient.LOGGER.info("Hypixel API: fetching profiles for {}", uuid);
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException(describeFailure(response));
            }
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement success = body.get("success");
            if (success == null || !success.getAsBoolean()) {
                JsonElement cause = body.get("cause");
                throw new IllegalStateException("Hypixel API refused: "
                        + (cause == null ? "unknown" : cause.getAsString()));
            }
            PROFILES_CACHE.put(uuid, new Cached(body, System.currentTimeMillis()));
            return body;
        });
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

    /** A message a person can act on, for the two failures that are almost always the key. */
    private static String describeFailure(HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 403 -> "Hypixel API rejected the key (403) — check the API key in settings";
            case 429 -> "Hypixel API rate limit hit (429) — try again in a minute";
            default -> "Hypixel API returned " + response.statusCode();
        };
    }

    public static void clearCache() {
        PROFILES_CACHE.clear();
    }
}
