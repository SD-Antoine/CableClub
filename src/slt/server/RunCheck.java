package slt.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * A check of the runs (point 3e): told every event a player's game sends, in order, it may flag the run. A flagged
 * run enters no leaderboard until a moderator has looked at it (the player sees it « en cours de vérification »).
 * The server published with Cable Club has none: the official server loads its own from a jar on its classpath
 * ({@link ServiceLoader}), kept out of the public repository so that nobody can read what is checked. A check never
 * changes the game nor the room: it only flags.
 */
public interface RunCheck {

    /**
     * What a check sees of a run: the room, the player, the mode, the game, the room's rules (save states allowed…),
     * the time on the server, and {@code state}: the check's own memory of this player's run, kept with the room
     * (saved with it, read again when the server starts). Each check keeps its entries under its own keys.
     */
    record Run(String roomId, String userId, String mode, String gameId, int gen, Map<String, Object> rules,
               Map<String, Object> state, long now) {
    }

    /** A flag on a run: a short code, and what the moderators read (never shown to the players). */
    record Flag(String code, String text) {
    }

    /** The flags this event raises on the run (empty or null: nothing wrong). Called with the room's lock held. */
    List<Flag> check(Run run, Map<String, Object> event);

    /** The checks on the classpath of this server (none for the server published with Cable Club). */
    static List<RunCheck> load() {
        List<RunCheck> out = new ArrayList<>();
        for (RunCheck c : ServiceLoader.load(RunCheck.class)) {
            out.add(c);
        }
        return List.copyOf(out);
    }
}
