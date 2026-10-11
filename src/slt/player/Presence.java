package slt.player;

import slt.player.track.GameReader;
import slt.player.track.GbReader;
import slt.player.track.Gen3Reader;
import slt.player.track.NdsReader;

import java.nio.file.Path;

/**
 * Where the player stands and what they are doing, read on every image of
 * the game, so the other players of the room can see them in their own game:
 * position to the pixel, map, state (battle, dialogue, trade, menu) and the
 * player's real sprite.
 */
interface Presence {

    /**
     * One reading: what is sent to the other players and to this player's own window.
     *
     * @param overworld the field screen is shown (the position is meaningful)
     * @param free      the player has the controls: the other players may be drawn
     * @param x         position on the map in pixels (2D), or in the 3D world in 1/16 of a unit
     *                  (generation 5: x, y = height, z)
     * @param view      3D games: camera x projection matrix of the image shown (4x4, row vectors:
     *                  screen = [x y z 1] x view), with which the window places the others, then the depth
     *                  (clip w) of the player's feet, where sprites are drawn one texel per pixel; null in 2D
     */
    record Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
                  int anchorX, int anchorY, int z, float[] view) {

        Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
               int anchorX, int anchorY) {
            this(map, x, y, overworld, free, status, spriteId, anchorX, anchorY, 0, null);
        }
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

    /** Positions in a 3D world (x, y = height, z): the others are placed with the camera of each image. */
    default boolean world3d() {
        return false;
    }

    /** The presence of this game's family, or null when the players cannot see each other in it yet. */
    static Presence forReader(GameReader reader, Path saves, Path rom) {
        if (reader instanceof Gen3Reader g3) {
            return new Gen3Presence(g3, saves);
        }
        if (reader instanceof GbReader gb) {
            return new GbPresence(gb);
        }
        if (reader instanceof NdsReader nds && nds.gen5()) {
            Gen5Sprites.Set set = Gen5Sprites.forGame(nds.game());
            return set == null ? null : new Gen5Presence(nds, new Gen5Sprites(rom, set.narc()), set);
        }
        return null;
    }
}
