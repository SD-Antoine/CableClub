package slt.player.track;

/** Picks the memory reader of a game (identified like the randomizer: "red", "emerald", "black2"…). */
public final class Readers {

    private Readers() {
    }

    public static GameReader forGame(String game, GameInfo info) {
        if (game == null) {
            return null;
        }
        GameReader r = NdsReader.forGame(game, info);
        if (r == null) {
            r = Gen3Reader.forGame(game, info);
        }
        if (r == null) {
            r = GbReader.forGame(game, info);
        }
        return r;
    }
}
