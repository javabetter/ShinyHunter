package com.shinyhunter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bazaar prices for critter shards, for the coin column in the run history.
 *
 * <p>Read from Hypixel's public bazaar endpoint, which needs no key — an ordinary HTTP request,
 * nothing sent to the game server. Every critter's shard is listed as {@code SHARD_<NAME>}
 * ({@code SHARD_MANTIS_SHRIMP} for Mantis Shrimp). Refreshed at most every half hour, and only
 * when something asks for a price.
 */
public final class BazaarPrices {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long REFRESH_MILLIS = 30 * 60 * 1000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Product id -> {instant sell price, sell offer price}. */
    private static final Map<String, double[]> PRICES = new HashMap<>();

    /** What every sparkling catch (and loot share of one) gives, worth millions. */
    public static final String RAINBOW_FEATHER = "RAINBOW_FEATHER";

    private static long fetchedAt;
    private static boolean fetching;

    private BazaarPrices() {
    }

    /** Starts a refresh if the prices are missing or stale. Never blocks. */
    public static void ensureFresh() {
        if (fetching || System.currentTimeMillis() - fetchedAt < REFRESH_MILLIS) {
            return;
        }
        fetching = true;
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", Edition.NAME)
                .GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            Map<String, double[]> read = new HashMap<>();
            if (error == null && response.statusCode() == 200) {
                try {
                    JsonObject products = JsonParser.parseString(response.body())
                            .getAsJsonObject().getAsJsonObject("products");
                    for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
                        if (!entry.getKey().startsWith("SHARD_") && !entry.getKey().equals(RAINBOW_FEATHER)) {
                            continue;
                        }
                        JsonObject quick = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                        read.put(entry.getKey(), new double[]{
                                quick.get("sellPrice").getAsDouble(), quick.get("buyPrice").getAsDouble()});
                    }
                } catch (RuntimeException e) {
                    ShinyHunterClient.LOGGER.warn("Couldn't read bazaar prices", e);
                }
            } else {
                ShinyHunterClient.LOGGER.warn("Bazaar lookup failed: {}",
                        error != null ? error.getMessage() : "HTTP " + response.statusCode());
            }
            Minecraft.getInstance().execute(() -> {
                fetching = false;
                if (read.isEmpty()) {
                    fetchedAt = System.currentTimeMillis() - REFRESH_MILLIS + 60_000L; // retry in a minute
                    return;
                }
                PRICES.clear();
                PRICES.putAll(read);
                fetchedAt = System.currentTimeMillis();
                ShinyHunterClient.LOGGER.info("Bazaar prices: {} shards", PRICES.size());
                RunHistory.fillMissingCoins();
            });
        });
    }

    public static boolean ready() {
        return !PRICES.isEmpty();
    }

    /** One shard of this critter, in coins; 0 when it isn't on the bazaar. */
    public static double shardPrice(String critter) {
        double[] price = PRICES.get("SHARD_" + critter.toUpperCase(Locale.ROOT).replace(' ', '_'));
        if (price == null) {
            return 0;
        }
        return ShinyConfig.get().runCoinsInstaSell ? price[0] : price[1];
    }

    /** What a pile of shards is worth, or -1 while the prices aren't loaded. */
    public static long value(Map<String, Integer> shards) {
        return value(shards, 0);
    }

    /** What a run's shards and Rainbow Feathers are worth, or -1 while the prices aren't loaded. */
    public static long value(Map<String, Integer> shards, int feathers) {
        if (!ready()) {
            ensureFresh();
            return -1;
        }
        double[] feather = PRICES.get(RAINBOW_FEATHER);
        double total = feather == null ? 0
                : feathers * (ShinyConfig.get().runCoinsInstaSell ? feather[0] : feather[1]);
        for (Map.Entry<String, Integer> entry : shards.entrySet()) {
            total += shardPrice(entry.getKey()) * entry.getValue();
        }
        return Math.round(total);
    }

    /** {@code 2.85M}, {@code 19k}, {@code 5.52B}. */
    public static String format(long coins) {
        if (coins < 0) {
            return "—";
        }
        if (coins >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB", coins / 1e9);
        }
        if (coins >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", coins / 1e6);
        }
        if (coins >= 1_000L) {
            return Math.round(coins / 1e3) + "k";
        }
        return Long.toString(coins);
    }
}
