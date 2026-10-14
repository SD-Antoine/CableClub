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
     *                  (clip w) of the player's feet, where sprites are drawn one texel per pixel, then where
     *                  the player's feet are on that image (x, y in pixels of the top screen), then how many
     *                  rows of a sprite lie under the point of the ground it stands on; null in 2D
     * @param box       a message box covers the top (1) or the bottom (2) of the screen: the others stay
     *                  drawn behind it; 0 none
     * @param others    3D games: the other characters drawn on this image (non-player characters, the player's
     *                  own Pokémon), as x, y (where their feet are on the top screen) and the depth (clip w) of
     *                  their feet and the size of their sprite in pixels (32; 64 for a big Pokémon), four numbers
     *                  each: the window keeps the players it draws behind them
     * @param pet       the Pokémon following the player (HeartGold / SoulSilver), null without
     */
    record Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
                  int anchorX, int anchorY, int z, float[] view, int box, float[] others, Pet pet) {

        Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
               int anchorX, int anchorY, int z, float[] view, int box) {
            this(map, x, y, overworld, free, status, spriteId, anchorX, anchorY, z, view, box, null, null);
        }

        Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
               int anchorX, int anchorY, int z, float[] view) {
            this(map, x, y, overworld, free, status, spriteId, anchorX, anchorY, z, view, 0, null, null);
        }

        Sample(int map, int x, int y, boolean overworld, boolean free, String status, String spriteId,
               int anchorX, int anchorY) {
            this(map, x, y, overworld, free, status, spriteId, anchorX, anchorY, 0, null, 0, null, null);
        }
    }

    /**
     * The Pokémon following the player: where it stands in the 3D world (as the player: 1/16 of a unit), its
     * sprite (id, PNG base64, size).
     */
    record Pet(int x, int y, int z, String spriteId, String png, int w, int h) {
    }

    /** Reads the live memory between two frames; null while the game is not in a playable state. */
    Sample sample(CoreHost core);

    /** The player's sprite (PNG, base64), null until seen. */
    String spritePng();

    int spriteW();

    int spriteH();

    /**
     * The sprite of the last reading is exactly the one the game draws for the player (not a stand-in such
     * as the standing walking image on a bike): the window may redraw it over the others walking behind.
     */
    default boolean spriteExact() {
        return false;
    }

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
        if (reader instanceof NdsReader nds) {
            Gen5Sprites.Set set = Gen4Presence.setFor(nds.game());
            return set == null ? null : new Gen4Presence(nds, new Gen5Sprites(rom, set.narc(), true), set, nds.game());
        }
        return null;
    }
}
