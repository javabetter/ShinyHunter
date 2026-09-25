package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Hypixel's sidebar, which is the only place the client is told where it is.
 *
 * <p>Each sidebar row is assembled the same way vanilla assembles it for rendering: the score
 * holder's name wrapped in its team's prefix and suffix. Hypixel abuses that — the holder name is
 * usually junk and the visible text lives entirely in the team prefix/suffix — so reading the score
 * holders alone would return nothing useful.
 */
public final class SkyblockSidebar {

    /** U+23E3, the glyph Hypixel prefixes the current-zone row with. */
    private static final char ZONE_GLYPH = '⏣';

    private SkyblockSidebar() {
    }

    /** The sidebar's title, formatting stripped. Empty when there's no sidebar. */
    public static String title(Minecraft client) {
        Objective objective = sidebar(client);
        return objective == null
                ? ""
                : EntityDataProbe.stripFormatting(objective.getDisplayName().getString()).trim();
    }

    /** Every visible sidebar row, top-level formatting stripped. */
    public static List<String> lines(Minecraft client) {
        List<String> lines = new ArrayList<>();
        Objective objective = sidebar(client);
        if (objective == null) {
            return lines;
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            if (entry.isHidden()) {
                continue;
            }
            PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
            Component rendered = team != null
                    ? PlayerTeam.formatNameForTeam(team, entry.ownerName())
                    : entry.ownerName();
            String text = EntityDataProbe.stripFormatting(rendered.getString()).trim();
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        return lines;
    }

    /**
     * True when the sidebar identifies the current server as Skyblock. Hypixel titles its Skyblock
     * sidebar "SKYBLOCK" in every Skyblock world including the lobbies, and something else
     * ("HYPIXEL", a minigame name) everywhere else.
     */
    public static boolean inSkyblock(Minecraft client) {
        return title(client).toUpperCase().contains("SKYBLOCK");
    }

    /**
     * The row naming where the player physically is — the one carrying Hypixel's zone glyph (⏣).
     * Empty when the sidebar has no such row.
     */
    public static String locationLine(Minecraft client) {
        for (String line : lines(client)) {
            if (line.indexOf(ZONE_GLYPH) >= 0) {
                return line;
            }
        }
        return "";
    }

    /**
     * True when the player is standing in the named location.
     *
     * <p>Deliberately checks the zone row alone rather than the whole sidebar. Other rows mention
     * area names too — an event objective naming the current hotspot, for instance — and matching
     * those made this read as "in Critter Safari" from anywhere in Skyblock. Only the ⏣ row tracks
     * where the player actually is.
     *
     * <p><b>The all-rows fallback is deliberate.</b> Removing it, to stop the entrance reading as
     * the Safari, broke far more than it fixed: inside the Safari the ⏣ row often isn't there to
     * read, so the strict test answered "no" from inside the hunting grounds and took the critter
     * list, {@code !missing} and the end-of-run bests down with it. Being occasionally too generous
     * at the door is much cheaper than being wrong in the middle of a run, so the fallback stays and
     * the entrance is knowingly counted as the Safari.
     */
    public static boolean isAtLocation(Minecraft client, String location) {
        if (location == null || location.isBlank()) {
            return false;
        }

        // Prefer the zone row when there is one: it's the only row that tracks where you actually
        // are, and other rows name areas for unrelated reasons.
        String zone = zoneName(client);
        if (!zone.isEmpty()) {
            return zone.equalsIgnoreCase(location.trim());
        }

        // No zone row — fall back to scanning every row, which beats never matching at all. But
        // as the name itself, not as a prefix: "Critter Safari Entrance" is a different zone on
        // Torrhus Canyon, and "contains" read it as the Safari.
        return anyRowNames(client, location, false);
    }

    /**
     * Whether any sidebar row names the location. With {@code allowSuffix} the row may carry more
     * words after it ("Critter Safari Entrance"); without, the name has to end there.
     */
    private static boolean anyRowNames(Minecraft client, String location, boolean allowSuffix) {
        String needle = location.trim().toUpperCase();
        for (String line : lines(client)) {
            String upper = line.toUpperCase();
            int at = upper.indexOf(needle);
            while (at >= 0) {
                boolean startOk = at == 0 || !Character.isLetter(upper.charAt(at - 1));
                int end = at + needle.length();
                boolean endOk = end >= upper.length() || !Character.isLetter(upper.charAt(end));
                boolean suffixOk = allowSuffix || !followedByWord(upper, end);
                if (startOk && endOk && suffixOk) {
                    return true;
                }
                at = upper.indexOf(needle, at + 1);
            }
        }
        return false;
    }

    /** True when more words follow position {@code from} after whitespace ("... Safari Entrance"). */
    private static boolean followedByWord(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i > from && i < text.length() && Character.isLetter(text.charAt(i));
    }

    /**
     * True when the player is in the named location <em>or</em> one of its named sub-areas — so
     * "Critter Safari" also covers "Critter Safari Entrance".
     *
     * <p>This is the test for things that belong to the whole area, and {@link #isAtLocation} is the
     * test for things that belong to the hunting grounds proper. The Safari Manager stands at the
     * entrance, so a guard on him has to accept the entrance; the list of critters still outstanding
     * describes the run, so it must not.
     */
    public static boolean isAtOrWithin(Minecraft client, String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        String zone = zoneName(client);
        if (zone.isEmpty()) {
            return anyRowNames(client, location, true); // the fallback, entrance included
        }
        String needle = location.trim();
        return zone.equalsIgnoreCase(needle)
                // A word boundary, so "Critter Safari Entrance" matches and a hypothetical
                // "Critter Safarium" would not.
                || zone.regionMatches(true, 0, needle, 0, needle.length())
                        && zone.length() > needle.length()
                        && Character.isWhitespace(zone.charAt(needle.length()));
    }

    /** The current zone's bare name: the ⏣ row with the glyph and surrounding space removed. */
    public static String zoneName(Minecraft client) {
        String line = locationLine(client);
        return line.isEmpty() ? "" : line.replace(String.valueOf(ZONE_GLYPH), " ").trim();
    }

    private static Objective sidebar(Minecraft client) {
        if (client.level == null) {
            return null;
        }
        return client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
    }
}
