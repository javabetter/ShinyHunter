package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Detects the start and end of a Safari run and clears what's scoped to one.
 *
 * <p>A run begins when the player enters the hunt area, or when the level instance changes — a
 * Hypixel server hop. Both are genuine boundaries, and either alone would miss cases: a party that
 * loops Safari → Canyon → Safari without changing server never changes level, while joining a fresh
 * instance may put you straight into the Safari with no transition to observe.
 *
 * <p>Entering is the boundary for clearing, because some of what's cleared is consumed on the way
 * out — a downtime request is cashed in exactly when the party leaves for the canyon.
 */
public final class RunTracker {

    /** Runs shorter than this are a mis-detection — walking through a corner, or a zone flicker. */
    private static final long MIN_RUN_MILLIS = 30_000L;

    private static ClientLevel lastLevel;
    private static boolean wasInHuntArea;

    private static long runStartedAt;

    private RunTracker() {
    }

    public static void onClientTick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            Debug.log("level changed — wasInHuntArea =", wasInHuntArea);
            // Committed regardless of wasInHuntArea: a server hop ends a run whether or not the
            // sidebar ever told us we were in one.
            SplitTracker.commitBests();
            if (wasInHuntArea) {
                endRun("left the server");
            }
            wasInHuntArea = false;
            if (client.level == null) {
                // Left the world entirely: drop everything, timers included.
                ShardTradeWatcher.clearOffers();
                DowntimeTracker.reset();
                TimerWatcher.reset();
                SafariTimer.reset();
                SplitTracker.reset();
                PartyChat.clear();
            } else {
                startNewRun("joined a server");
            }
            return;
        }
        if (client.level == null || client.player == null) {
            return;
        }

        ShinyConfig config = ShinyConfig.get();
        boolean inHuntArea = SkyblockSidebar.isAtLocation(client, config.huntLocation);

        if (inHuntArea) {
            if (!wasInHuntArea) {
                Debug.log("entered", config.huntLocation, "- zone reads",
                        "\"" + SkyblockSidebar.zoneName(client) + "\"");
                startNewRun("entered " + config.huntLocation);
            }
        } else if (wasInHuntArea) {
            Debug.log("left", config.huntLocation, "- zone reads",
                    "\"" + SkyblockSidebar.zoneName(client) + "\"");
            endRun("left " + config.huntLocation);
        }
        wasInHuntArea = inHuntArea;
    }

    // ------------------------------------------------------------------ boundaries

    private static void startNewRun(String why) {
        // Bank the previous run before anything is wiped. This is the backstop that makes losing a
        // run's bests impossible: whatever else did or didn't detect the run ending, splits are
        // never cleared without a commit attempt first. It's a no-op if they're already committed.
        SplitTracker.commitBests();

        ShardTradeWatcher.clearOffers();
        SplitTracker.reset();
        RockmiteTracker.reset();
        // resetRun, not reset: a ticket paid at the entrance hops you here, and the countdown has
        // to survive that hop.
        SafariTimer.resetRun();
        runStartedAt = System.currentTimeMillis();
        ShinyHunterClient.LOGGER.info("New run ({}) — cleared shard trades and splits", why);
    }

    private static void endRun(String why) {
        long duration = runStartedAt == 0 ? 0 : System.currentTimeMillis() - runStartedAt;
        runStartedAt = 0;
        SafariGuard.clearKickHold();

        // Before the clock is reset: the bests are read off this run's splits, and which of them
        // count as personal depends on catches that are only all in once the run is over.
        SplitTracker.commitBests();
        long onClock = SafariTimer.elapsedMillis();
        SafariTimer.resetRun();

        // A banked !dt is cashed in here, whatever the run's length — it only exists if somebody
        // asked, so a zone flicker can't invent one.
        DowntimeTracker.onLeftSafari();

        if (duration < MIN_RUN_MILLIS) {
            ShinyHunterClient.LOGGER.info("Run ended ({}) after only {}ms", why, duration);
            return;
        }
        String time = onClock > 0 ? SafariTimer.format(onClock) : formatDuration(duration);
        ShinyHunterClient.LOGGER.info("Run ended ({}) — {}", why, time);
    }


    static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
