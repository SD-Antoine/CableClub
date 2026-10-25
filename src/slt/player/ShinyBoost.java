package slt.player;

import slt.Log;
import slt.player.track.GameReader;
import slt.player.track.GbReader;
import slt.player.track.Gen3Reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The room's shiny rate, applied while the game runs (generations 2 and 3;
 * the DS games get it in their ROM). When a wild battle starts, with the
 * chance that brings the game's 1/8192 to the room's rate, the wild Pokémon
 * becomes shiny before it appears, the way the game itself makes a shiny:
 *
 * <ul>
 *   <li>Generation 2: the game loads the wild Pokémon (battle mode, species,
 *       DVs) in the image where the encounter starts, then plays the transition,
 *       and only then reads the DVs (colours, sparkles): the DVs get shiny values
 *       right away (Defense, Speed, Special 10; Attack one of the shiny ones).
 *       Addresses read from the ROM, in the code that loads the wild Pokémon
 *       (next to the red Gyarados' forced shiny DVs).</li>
 *   <li>Generation 3: during the battle transition, the wild Pokémon (enemy
 *       party, the player's trainer ID) gets another personality value that is
 *       shiny, with the same nature, gender and ability; its data is encrypted
 *       again with the new key, in the order the new value gives.</li>
 * </ul>
 */
final class ShinyBoost {

    /** Live memory of the console (the emulation thread, between two images). */
    interface Live {
        byte[] read(long address, int length);

        boolean write(long address, byte[] bytes);
    }

    private static final int NATURAL = 8192;
    private static final String[] ORDERS = {"GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME", "AEGM", "AEMG",
        "AMGE", "AMEG", "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG"};
    private static final int UNOWN = 201;

    private final GameReader reader;
    private final Random rng = new Random();
    private volatile double chance;
    private volatile int rate;

    // generation 2: work RAM addresses (bus), -1 when the ROM did not show them
    private long gbMode = -1;
    private long gbType = -1;
    private long gbEnemySpecies = -1;
    private boolean gbDecided;

    // generation 3: the last wild personality looked at
    private long lastPid = -1;

    private ShinyBoost(GameReader reader) {
        this.reader = reader;
    }

    /** The boost for this game, or null when it does not apply (generation 1, DS games, unknown ROM layout). */
    static ShinyBoost create(GameReader reader, Path rom) {
        ShinyBoost b = new ShinyBoost(reader);
        if (reader instanceof GbReader gb && gb.gen() == 2) {
            try {
                if (!b.findGen2(Files.readAllBytes(rom))) {
                    Log.info("Taux de chromatiques : code des combats introuvable dans cette ROM (2e génération)");
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
            return b;
        }
        return reader instanceof Gen3Reader ? b : null;
    }

    /** 1 in {@code denominator} wild Pokémon shiny (0 or 8192 and more: the game's own rate). Scripts (tests). */
    void setRate(int denominator) {
        setNumerator(denominator <= 0 || denominator >= NATURAL ? 0 : (int) Math.round((double) NATURAL / denominator));
    }

    /** {@code num} wild Pokémon out of 8192 shiny (0 or 1: the game's own rate; 8192: all of them). */
    void setNumerator(int num) {
        rate = slt.ShinyOdds.oneIn(num);
        if (num <= 1) {
            chance = 0;
        } else {
            // natural shinies stay: the extra chance makes up the rest
            double p = slt.ShinyOdds.probability(num);
            chance = Math.min(1.0, (p - 1.0 / NATURAL) / (1.0 - 1.0 / NATURAL));
        }
    }

    int rate() {
        return rate;
    }

    /** Called before every image of the game. */
    void tick(Live live) {
        if (chance <= 0) {
            gbDecided = false;
            lastPid = -1;
            return;
        }
        if (reader instanceof GbReader) {
            tickGen2(live);
        } else if (reader instanceof Gen3Reader g3) {
            tickGen3(live, g3);
        }
    }

    // ---------------------------------------------------------- generation 2

    /**
     * In the code that loads the wild Pokémon: {@code ld a,[wBattleMode]; dec a; jr nz; ld a,[wBattleType];
     * cp ROAMING; jr nz} a little before {@code cp FORCESHINY (7); jr nz,+6; ld b,$EA; ld c,$AA; jr +8;
     * call BattleRandom; ld b,a; call BattleRandom; ld c,a; ld hl,wEnemyMonDVs}.
     */
    private boolean findGen2(byte[] rom) {
        byte[] sig = {(byte) 0xFE, 0x07, 0x20, 0x06, 0x06, (byte) 0xEA, 0x0E, (byte) 0xAA, 0x18, 0x08};
        for (int i = 64; i + 32 < rom.length; i++) {
            if (!matches(rom, i, sig)) {
                continue;
            }
            int j = i + sig.length;
            // call x; ld b,a; call x; ld c,a; ld hl,wEnemyMonDVs
            if ((rom[j] & 0xFF) != 0xCD || (rom[j + 3] & 0xFF) != 0x47 || (rom[j + 4] & 0xFF) != 0xCD
                    || (rom[j + 7] & 0xFF) != 0x4F || (rom[j + 8] & 0xFF) != 0x21) {
                continue;
            }
            int dvs = u16(rom, j + 9);
            for (int k = i - 4; k > i - 80; k--) {
                // ld a,[mode]; dec a; jr nz,x; ld a,[type]; cp 5
                if ((rom[k] & 0xFF) == 0xFA && (rom[k + 3] & 0xFF) == 0x3D && (rom[k + 4] & 0xFF) == 0x20
                        && (rom[k + 6] & 0xFF) == 0xFA && (rom[k + 9] & 0xFF) == 0xFE && rom[k + 10] == 0x05) {
                    gbMode = u16(rom, k + 1);
                    gbType = u16(rom, k + 7);
                    // wEnemyMon: species, item, 4 moves, then the DVs
                    gbEnemySpecies = dvs - 6;
                    boolean ok = gbMode >= 0xC000 && gbMode < 0xE000 && gbType >= 0xC000 && gbType < 0xE000
                            && gbEnemySpecies >= 0xC000 && gbEnemySpecies < 0xE000;
                    if (ok) {
                        Log.info(String.format("Taux de chromatiques : combat %04X, type %04X, ennemi %04X", gbMode, gbType, gbEnemySpecies));
                    }
                    return ok;
                }
            }
        }
        return false;
    }

    private void tickGen2(Live live) {
        byte[] mode = live.read(gbMode, 1);
        if (mode == null) {
            return;
        }
        if (mode[0] != 1) {
            gbDecided = mode[0] != 0; // trainer battle: nothing; out of battle: ready for the next wild one
            return;
        }
        if (gbDecided) {
            return;
        }
        // a wild battle starts: decided once, while the transition plays
        gbDecided = true;
        byte[] type = live.read(gbType, 1);
        byte[] mon = live.read(gbEnemySpecies, 8);
        if (type == null || mon == null) {
            return;
        }
        // normal, fishing, Bug-Catching Contest, Headbutt; not the roaming dogs (their DVs are kept apart),
        // nor the forced or scripted battles
        int t = type[0] & 0xFF;
        if (t != 0 && t != 4 && t != 6 && t != 8) {
            return;
        }
        int species = mon[0] & 0xFF;
        int atkDef = mon[6] & 0xFF;
        int spdSpc = mon[7] & 0xFF;
        boolean shiny = (atkDef & 0x0F) == 10 && spdSpc == 0xAA && ((atkDef >> 4) & 2) != 0;
        // Unown's letter comes from its DVs
        if (species == 0 || species == UNOWN || shiny || rng.nextDouble() >= chance) {
            return;
        }
        int[] atk = {2, 3, 6, 7, 10, 11, 14, 15};
        byte[] dvs = {(byte) ((atk[rng.nextInt(atk.length)] << 4) | 0x0A), (byte) 0xAA};
        if (live.write(gbEnemySpecies + 6, dvs)) {
            Log.info("Taux de chromatiques : Pokémon sauvage chromatique (1/" + rate + ")");
        }
    }

    // ---------------------------------------------------------- generation 3

    private void tickGen3(Live live, Gen3Reader g3) {
        long enemy = g3.wildAddress();
        long player = g3.playerTrainerId();
        if (enemy < 0 || player < 0) {
            return;
        }
        byte[] d = live.read(enemy, 0x50);
        if (d == null) {
            return;
        }
        long pid = u32(d, 0);
        long ot = u32(d, 4);
        if (pid == 0 || pid == lastPid) {
            return;
        }
        lastPid = pid;
        // the player's ID = a wild Pokémon (trainers' Pokémon have their own); already in battle = too late
        if (ot != player || g3.inBattleFlag() != 0 || chance <= 0) {
            return;
        }
        long key = pid ^ ot;
        long[] w = new long[12];
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            w[i] = u32(d, 0x20 + 4 * i) ^ key;
            sum += (int) (w[i] & 0xFFFF) + (int) (w[i] >>> 16);
        }
        if ((sum & 0xFFFF) != u16(d, 0x1C)) {
            return; // not written completely yet: looked at again with the next image
        }
        String order = ORDERS[(int) (pid % 24)];
        int growth = order.indexOf('G') * 3;
        int species = (int) (w[growth] & 0xFFFF);
        long tsv = (ot >>> 16) ^ (ot & 0xFFFF);
        if (species == UNOWN || species == 0 || ((pid >>> 16) ^ (pid & 0xFFFF) ^ tsv) < 8) {
            return; // Unown's letter comes from its personality; already shiny
        }
        if (rng.nextDouble() >= chance) {
            return;
        }
        long np = shinyPid(pid, tsv);
        if (np < 0) {
            return;
        }
        // the four parts of the data, moved to the order of the new value, encrypted with the new key
        String newOrder = ORDERS[(int) (np % 24)];
        long[] nw = new long[12];
        for (char part : "GAEM".toCharArray()) {
            System.arraycopy(w, order.indexOf(part) * 3, nw, newOrder.indexOf(part) * 3, 3);
        }
        long newKey = np ^ ot;
        byte[] out = new byte[0x50];
        System.arraycopy(d, 0, out, 0, 0x50);
        put32(out, 0, np);
        for (int i = 0; i < 12; i++) {
            put32(out, 0x20 + 4 * i, nw[i] ^ newKey);
        }
        // the checksum (sum of the data) does not change
        if (live.write(enemy, out)) {
            lastPid = np;
            Log.info("Taux de chromatiques : Pokémon sauvage chromatique (1/" + rate + ")");
        }
    }

    /** A shiny personality value with the same nature (value % 25) and the same low byte (gender, ability). */
    private long shinyPid(long pid, long tsv) {
        long low = pid & 0xFF;
        long nature = pid % 25;
        for (int i = 0; i < 100_000; i++) {
            long lo = ((long) rng.nextInt(256) << 8) | low;
            long hi = lo ^ tsv ^ rng.nextInt(8);
            long np = (hi << 16) | lo;
            if (np != pid && np % 25 == nature) {
                return np;
            }
        }
        return -1;
    }

    // --------------------------------------------------------------- bytes

    private static boolean matches(byte[] d, int at, byte[] sig) {
        for (int i = 0; i < sig.length; i++) {
            if (d[at + i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int o) {
        return ((long) u16(d, o) | ((long) u16(d, o + 2) << 16)) & 0xFFFFFFFFL;
    }

    private static void put32(byte[] d, int o, long v) {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >>> 8);
        d[o + 2] = (byte) (v >>> 16);
        d[o + 3] = (byte) (v >>> 24);
    }
}
