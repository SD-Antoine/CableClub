package slt.server;

import slt.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finished runs the server recorded (point 3): one line per player of a room that ended, from the summary the
 * room keeps (what the server counted during the run). The statistics of a player and the leaderboards are built
 * from them, and from nothing else. These counts come from the events of each player's game: fit for friends, not
 * for a stake.
 */
final class Records {

    /** Players a leaderboard shows at most. */
    static final int MAX_RANKED = 50;

    /** One player's finished run. */
    record Run(String userId, String mode, String family, String gameId, int players, String outcome, boolean won,
               long captures, long deaths, long badges, long alive, long at) {
    }

    private Records() {
    }

    /** The runs of a room that ended (none otherwise); the caller holds the room's lock. */
    static List<Run> of(Room r) {
        List<Run> out = new ArrayList<>();
        if (r.ended == null) {
            return out;
        }
        String outcome = Json.str(r.ended, "outcome");
        List<Object> summary = Json.arr(r.ended.get("summary"));
        if (summary == null) {
            return out;
        }
        ModeSpec spec = r.spec();
        for (Object o : summary) {
            Map<String, Object> s = Json.obj(o);
            if (s == null || Json.str(s, "userId") == null) {
                continue;
            }
            // won: the player beat the Champion, or the linked players won together
            boolean won = "champion".equals(Json.str(s, "status")) || (spec.shared() && "victory".equals(outcome));
            out.add(new Run(Json.str(s, "userId"), r.mode, spec.family(), Json.str(r.game, "gameId"), summary.size(),
                    outcome, won, Json.num(s, "captures", 0), Json.num(s, "deaths", 0), Json.num(s, "badges", 0),
                    Json.num(s, "alive", 0), Json.num(r.ended, "at", 0)));
        }
        return out;
    }

    /** A player's statistics, by family of modes (the tab of each mode), then over every mode. */
    static Map<String, Object> stats(List<Run> runs, String userId) {
        Map<String, Totals> byFamily = new LinkedHashMap<>();
        Totals all = new Totals();
        for (Run run : runs) {
            if (userId.equals(run.userId())) {
                byFamily.computeIfAbsent(run.family(), k -> new Totals()).add(run);
                all.add(run);
            }
        }
        Map<String, Object> families = new LinkedHashMap<>();
        byFamily.forEach((k, t) -> families.put(k, t.toJson()));
        return Json.map("families", families, "all", all.toJson());
    }

    /**
     * The leaderboard of an official mode on this server: the players who chose to appear in it, by wins, then fewest
     * deaths a run, then the best survival. {@code gameId} null: every game; {@code players} 0: every size of room.
     * An abandoned run does not count.
     */
    static List<Object> leaderboard(List<Run> runs, Accounts accounts, String mode, String gameId, int players, String viewerId) {
        Map<String, Totals> byUser = new LinkedHashMap<>();
        for (Run run : runs) {
            if (!run.mode().equals(mode) || "abandon".equals(run.outcome())
                    || (gameId != null && !gameId.equals(run.gameId())) || (players > 0 && run.players() != players)) {
                continue;
            }
            byUser.computeIfAbsent(run.userId(), k -> new Totals()).add(run);
        }
        List<Map.Entry<String, Totals>> ranked = new ArrayList<>();
        for (Map.Entry<String, Totals> e : byUser.entrySet()) {
            Accounts.User u = accounts.get(e.getKey());
            if (u != null && u.ranked) {
                ranked.add(e);
            }
        }
        ranked.sort(Comparator.<Map.Entry<String, Totals>>comparingLong(e -> -e.getValue().wins)
                .thenComparingDouble(e -> e.getValue().deathsPerRun())
                .thenComparingDouble(e -> -e.getValue().survival()));
        List<Object> out = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<String, Totals> e : ranked.subList(0, Math.min(MAX_RANKED, ranked.size()))) {
            Accounts.User u = accounts.get(e.getKey());
            Map<String, Object> row = e.getValue().toJson();
            row.put("rank", (long) ++rank);
            row.put("user", u.pub());
            row.put("me", u.id.equals(viewerId));
            out.add(row);
        }
        return out;
    }

    /** Totals of runs. */
    private static final class Totals {
        long runs;
        long wins;
        long abandons;
        long captures;
        long deaths;
        long alive;
        long badgesBest;

        void add(Run run) {
            if ("abandon".equals(run.outcome())) {
                abandons++;
                return;
            }
            runs++;
            wins += run.won() ? 1 : 0;
            captures += run.captures();
            deaths += run.deaths();
            alive += run.alive();
            badgesBest = Math.max(badgesBest, run.badges());
        }

        double deathsPerRun() {
            return runs == 0 ? 0 : (double) deaths / runs;
        }

        /** Pokémon still alive at the end for each one caught (0 to 1); -1 when nothing was caught. */
        double survival() {
            return captures == 0 ? -1 : Math.min(1, (double) alive / captures);
        }

        Map<String, Object> toJson() {
            return Json.map("runs", runs, "wins", wins, "abandons", abandons, "captures", captures, "deaths", deaths,
                    "alive", alive, "badgesBest", badgesBest,
                    "survival", captures == 0 ? null : (long) Math.round(100 * survival()));
        }
    }
}
