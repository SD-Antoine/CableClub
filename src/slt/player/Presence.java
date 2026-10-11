package slt.player;

import slt.player.track.GameReader;
import slt.player.track.GbReader;
import slt.player.track.Gen3Reader;

import java.nio.file.Path;

/**
 * Where the player stands and what they are doing, sampled a few times per
 * second, so the other players of the room can see them in their own game:
 * position to the pixel, map, state (battle, dialogue, trade, menu) and the
 * player's real sprite.
 */
interface Presence {

    /**
     * One reading: what is sent to the other players and to this player's own window.
     *
     * @param overworld the field screen is shown (the position is meaningful)
     * @param free      the player has the controls: the other players may be drawn
     */
    record Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
                  int anchorX, int anchorY) {
    }

    /** Reads the live memory between two frames; null while the game is not in a playable state. */
    Sample sample(CoreHost core);

    /** The player's sprite (PNG, base64), null until seen. */
    String spritePng();

    int spriteW();

    int spriteH();

    /** A Pokémon arrived by trade. */
    default void tradeHappened() {
    }

    /** The presence of this game's family, or null when the players cannot see each other in it yet. */
    static Presence forReader(GameReader reader, Path saves) {
        if (reader instanceof Gen3Reader g3) {
            return new Gen3Presence(g3, saves);
        }
        if (reader instanceof GbReader gb) {
            return new GbPresence(gb);
        }
        return null;
    }
}
