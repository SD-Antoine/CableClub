package slt.player.track;

import java.util.List;

/** Reads the state of one family of games from a memory copy, and writes back what the Soul Link decides. */
public interface GameReader {

    /** Memory areas to copy before each reading: {start, length} pairs on the console bus. */
    long[][] areas();

    /** The current state, or null while no save is loaded (title screen, intro…). */
    Snapshot read(Mem m);

    /**
     * Whether the PC storage holds this Pokémon: TRUE / FALSE, or null when
     * this game's storage cannot be read.
     */
    Boolean stored(Mem m, long uid);

    /** Size of a party structure (bytes read back before applying a shared death). */
    int structSize();

    /**
     * Change that sets the HP of {@code mon} to zero, computed on the live
     * bytes of its structure (re-read just before writing), or null if those
     * bytes no longer hold this Pokémon or it is already K.O.
     */
    Patch zeroHp(byte[] live, Mon mon);

    /** Bytes to write at {@code offset} from the structure start. */
    record Patch(int offset, byte[] bytes) {
    }

    /** Bytes to write at an absolute console address. */
    record Write(long address, byte[] bytes) {
    }

    /**
     * Writes giving {@code mon} (in the party) the nickname chosen for its link:
     * empty when it already has it, null when this game is not supported.
     */
    default List<Write> nickname(Mem m, Mon mon, String name) {
        return null;
    }

    /** Writes adding items to the bag (items given back), or null if unsupported / no room. */
    default List<Write> addItem(Mem m, int item, int quantity) {
        return null;
    }

    /**
     * Writes moving a dead Pokémon from the party to the dedicated PC box, or
     * null when unsupported or not safe right now (battle, last Pokémon…).
     */
    default List<Write> deposit(Mem m, Mon mon) {
        return null;
    }

    /**
     * Writes moving a living Pokémon from the party to the PC (Sololocke: one Pokémon in the team), in the first free
     * place from the first box, never the dead box; null when unsupported or not safe right now. Generations 1 and 2
     * have only the current box.
     */
    default List<Write> store(Mem m, Mon mon) {
        return deposit(m, mon);
    }

    /**
     * Trade between partners: the bytes of party Pokémon {@code uid}, everything the
     * partner's game (same game) needs to hold it, or null when not in the party / unsupported.
     */
    default byte[] tradeOut(Mem m, long uid) {
        return null;
    }

    /**
     * Writes putting the Pokémon received in a trade ({@code blob}, from {@link #tradeOut} in the
     * partner's game) in place of party Pokémon {@code uid}, or null when not possible right now.
     */
    default List<Write> tradeIn(Mem m, long uid, byte[] blob) {
        return null;
    }

    /** Where {@link #deposit} puts the dead Pokémon, as told to the player. */
    /**
     * Everything a battle simulator needs about a Pokémon of the party (in-room battles): national species,
     * level, moves (ids), ivs / evs (generations 1-2: DVs x 2 and the square root of the stat experience, as
     * Showdown counts them), nature (0-24), ability (id, or slot 0/1 in generation 3), held item (generations
     * 4-5: same ids as Showdown), gender, happiness, shiny; null when unknown.
     */
    default java.util.Map<String, Object> battleSet(Mem m, Mon mon) {
        return null;
    }

    /**
     * Hardcore Nuzlocke: writes setting the game's battle style to "Set" (no switch offered after the foe's Pokémon is
     * knocked out), empty when it already is, null when the options are not found in this game.
     */
    default List<Write> setBattleStyle(Mem m) {
        return null;
    }

    /** Game Boy: whether writes to the cartridge RAM reach the game (false after a save state was loaded). */
    default void cartridgeRamLive(boolean live) {
    }

    default String deadBox() {
        return "la boîte MORTS du PC";
    }

    /**
     * Writes removing a released Pokémon from the game (party or PC), or null
     * when unsupported, not found or not safe right now.
     */
    default List<Write> release(Mem m, long uid) {
        return null;
    }
}
