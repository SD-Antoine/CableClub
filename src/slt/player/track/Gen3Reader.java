package slt.player.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generation 3 (Game Boy Advance): Ruby, Sapphire, Emerald, FireRed, LeafGreen.
 *
 * <pre>
 * Pokémon (100 bytes in the party): 0x00 personality, 0x04 OT id (TID | SID << 16), 0x08 nickname,
 *   0x1C checksum, 0x20 48 bytes encrypted with personality ^ OT id, 4 substructures in the order
 *   personality % 24 (Growth, Attacks, EVs, Misc), 0x54 level, 0x56 HP, 0x58 max HP.
 *   The first 80 bytes are the PC form of the Pokémon; the nickname is outside the checksum.
 * The party and the enemy party are two arrays of 6 next to each other (enemy first in FireRed/LeafGreen).
 * Save blocks: SaveBlock2 = player name, trainer ID, encryption key (Emerald 0xAC, FireRed/LeafGreen 0xF20);
 *   SaveBlock1 = location, money, bag pockets, flags (badges, game clear);
 *   Emerald/FireRed/LeafGreen move them in memory and keep pointers in IWRAM
 *   (SaveBlock1, SaveBlock2, PC storage); Ruby/Sapphire use fixed addresses.
 * gMain (IWRAM): callbacks at 0x00-0x18, frame counter at 0x24, "in battle" = bit 1 of 0x439
 *   (Ruby/Sapphire: 0x43D, their key fields are longer).
 * Field controls locked (dialogue, menu, cutscene, battle transition): one byte in IWRAM, 1 = locked;
 *   palette fade running: bit 7 of byte 7 of gPaletteFade (EWRAM). Same addresses in the French and
 *   English versions; gMain itself moved in the French FireRed, it is found by its frame counter.
 * PC storage: u32 current box, 14 boxes x 30 Pokémon x 80 bytes, then 14 box names of 9 bytes.
 * </pre>
 */
public final class Gen3Reader implements GameReader {

    private static final long EWRAM = 0x02000000L;
    private static final int EWRAM_SIZE = 0x40000;
    private static final long IWRAM = 0x03000000L;
    private static final int IWRAM_SIZE = 0x8000;
    private static final int MON = 100;
    private static final int BOX_MON = 80;
    private static final int BOXES = 14;
    private static final int BOX_SLOTS = 30;
    private static final int STORAGE_SIZE = 4 + BOXES * BOX_SLOTS * BOX_MON + BOXES * 9;
    private static final int DEAD_BOX = BOXES - 1;
    private static final String DEAD_BOX_NAME = "MORTS";

    private static final String[] ORDERS = {"GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME", "AEGM", "AEMG",
        "AMGE", "AMEG", "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG"};

    private enum Kind { RS, E, FRLG }

    /** gMain of the known versions (English, French), tried before any other candidate. */
    private static final java.util.Set<Long> KNOWN_MAIN = java.util.Set.of(0x03001770L, 0x030022C0L, 0x030030F0L, 0x03003040L);

    /** A bag pocket: offset in SaveBlock1, number of slots. */
    private record Pocket(int offset, int slots) {
    }

    private final Kind kind;
    private final GameInfo info;
    private final int flagsOffset;
    private final int firstBadgeFlag;
    private final int gameClearFlag;
    private final int sb1PartyOffset;
    private final int moneyOffset;
    private final int keyOffset;     // in SaveBlock2, -1 = no encryption (Ruby/Sapphire)
    private final int partyCountDelta;
    private final Pocket items;
    private final Pocket keyItems;
    private final Pocket balls;
    private final Pocket tms;
    private final Pocket berries;

    private volatile long party = -1;
    private volatile long sb1 = -1;
    private volatile long sb2 = -1;
    private volatile long trainerId = -1;
    private volatile long storage = -1;
    private long pointerSlot = -1;

    // gMain, found by its frame counter
    private volatile long main = -1;
    private Map<Integer, long[]> mainCandidates;
    private boolean mainGivenUp;
    private volatile int inBattle = -1;
    private final int battleOffset;
    private final long lockAddress;
    private final long fadeAddress;

    private Gen3Reader(Kind kind, GameInfo info) {
        this.kind = kind;
        this.info = info;
        battleOffset = kind == Kind.RS ? 0x43D : 0x439;
        lockAddress = switch (kind) {
            case RS -> 0x030006A4L;
            case E -> 0x03000F2CL;
            default -> 0x03000F9CL;
        };
        fadeAddress = switch (kind) {
            case RS -> 0x0202F388L;
            case E -> 0x02037FD4L;
            default -> 0x02037AB8L;
        };
        switch (kind) {
            case RS -> {
                flagsOffset = 0x1220; firstBadgeFlag = 0x807; gameClearFlag = 0x804; sb1PartyOffset = 0x238;
                moneyOffset = 0x490; keyOffset = -1; partyCountDelta = -0x10;
                items = new Pocket(0x560, 20); keyItems = new Pocket(0x5B0, 20); balls = new Pocket(0x600, 16);
                tms = new Pocket(0x640, 64); berries = new Pocket(0x740, 46);
            }
            case E -> {
                flagsOffset = 0x1270; firstBadgeFlag = 0x867; gameClearFlag = 0x864; sb1PartyOffset = 0x238;
                moneyOffset = 0x490; keyOffset = 0xAC; partyCountDelta = -3;
                items = new Pocket(0x560, 30); keyItems = new Pocket(0x5D8, 30); balls = new Pocket(0x650, 16);
                tms = new Pocket(0x690, 64); berries = new Pocket(0x790, 46);
            }
            default -> {
                flagsOffset = 0x0EE0; firstBadgeFlag = 0x820; gameClearFlag = 0x82C; sb1PartyOffset = 0x38;
                moneyOffset = 0x290; keyOffset = 0xF20; partyCountDelta = -0x25B;
                items = new Pocket(0x310, 42); keyItems = new Pocket(0x3B8, 30); balls = new Pocket(0x430, 13);
                tms = new Pocket(0x464, 58); berries = new Pocket(0x54C, 43);
            }
        }
    }

    /** First Pokémon of the enemy party (a wild one during a wild battle), -1 while the party is unknown. */
    public long wildAddress() {
        long p = party;
        return p < 0 ? -1 : kind == Kind.FRLG ? p - 6 * MON : p + 6 * MON;
    }

    /** The player's trainer ID and secret ID (32 bits, as in a Pokémon's data), -1 while unknown. */
    public long playerTrainerId() {
        return sb2 < 0 ? -1 : trainerId;
    }

    /** 1 in battle, 0 not, -1 unknown (gMain not found yet). */
    public int inBattleFlag() {
        return inBattle;
    }

    public static Gen3Reader forGame(String game, GameInfo info) {
        return switch (game) {
            case "ruby", "sapphire" -> new Gen3Reader(Kind.RS, info);
            case "emerald" -> new Gen3Reader(Kind.E, info);
            case "firered", "leafgreen" -> new Gen3Reader(Kind.FRLG, info);
            default -> null;
        };
    }

    @Override
    public long[][] areas() {
        return new long[][]{{EWRAM, EWRAM_SIZE}, {IWRAM, IWRAM_SIZE}};
    }

    @Override
    public int structSize() {
        return MON;
    }

    // ------------------------------------------------------------- decoding

    static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    static long u32(byte[] d, int o) {
        return (u16(d, o) | ((long) u16(d, o + 2) << 16)) & 0xFFFFFFFFL;
    }

    /** Decrypted 12 words of the substructures, or null if the checksum fails. */
    private static long[] substructs(byte[] d, int o) {
        long key = u32(d, o) ^ u32(d, o + 4);
        long[] w = new long[12];
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            w[i] = u32(d, o + 0x20 + 4 * i) ^ key;
            sum += (int) (w[i] & 0xFFFF) + (int) (w[i] >>> 16);
        }
        return (sum & 0xFFFF) == u16(d, o + 0x1C) ? w : null;
    }

    /** The party Pokémon at {@code o} of {@code block}, or null. */
    Mon decode(byte[] d, int o, long base) {
        if (o < 0 || o + MON > d.length) {
            return null;
        }
        long pid = u32(d, o);
        long ot = u32(d, o + 4);
        if (pid == 0 && ot == 0) {
            return null;
        }
        int level = d[o + 0x54] & 0xFF;
        int hp = u16(d, o + 0x56);
        int maxHp = u16(d, o + 0x58);
        if (level > 100 || maxHp > 999 || hp > maxHp) {
            return null;
        }
        long[] w = substructs(d, o);
        if (w == null) {
            return null;
        }
        String order = ORDERS[(int) (pid % 24)];
        int g = order.indexOf('G') * 3;
        int m = order.indexOf('M') * 3;
        int internalSpecies = (int) (w[g] & 0xFFFF);
        int species = info.national(internalSpecies);
        if (internalSpecies == 0 || species <= 0 || species > 386) {
            return null;
        }
        int met = (int) ((w[m] >>> 8) & 0xFF);
        boolean egg = ((w[m + 1] >>> 30) & 1) != 0;
        if (!egg && (level < 1 || maxHp == 0)) {
            return null;
        }
        String nick = info.text(d, o + 8, 10, 0xFF);
        boolean shiny = ((ot >>> 16) ^ (ot & 0xFFFF) ^ (pid >>> 16) ^ (pid & 0xFFFF)) < 8;
        return new Mon(pid, species, level, hp, maxHp, nick, (int) (ot & 0xFFFF), met, egg, shiny, base + o);
    }

    /**
     * Battle set from the decrypted data: Growth (species, held item, happiness), Attacks (4 moves), EVs (HP,
     * Attack, Defense, Speed, Special Attack, Special Defense), Misc (IVs 5 bits each in the same order, egg,
     * ability slot in bit 31). Nature and gender come from the personality. The held item keeps the game's
     * id ({@code item3}): generation 3 numbers differ from Showdown's.
     */
    @Override
    public Map<String, Object> battleSet(Mem m, Mon mon) {
        byte[] d = m.read(mon.address(), MON);
        long[] w = d == null || mon.egg() ? null : substructs(d, 0);
        if (w == null) {
            return null;
        }
        long pid = u32(d, 0);
        String order = ORDERS[(int) (pid % 24)];
        int g = order.indexOf('G') * 3;
        int a = order.indexOf('A') * 3;
        int e = order.indexOf('E') * 3;
        int mi = order.indexOf('M') * 3;
        List<Object> moves = new ArrayList<>();
        List<Object> ppUps = new ArrayList<>();
        long[] ids = {w[a] & 0xFFFF, w[a] >>> 16, w[a + 1] & 0xFFFF, w[a + 1] >>> 16};
        for (int i = 0; i < 4; i++) {
            if (ids[i] != 0) {
                moves.add(ids[i]);
                ppUps.add((w[g + 2] >>> (2 * i)) & 3);
            }
        }
        long iv = w[mi + 1];
        Map<String, Object> ivs = slt.Json.map("hp", iv & 31, "atk", (iv >>> 5) & 31, "def", (iv >>> 10) & 31,
                "spe", (iv >>> 15) & 31, "spa", (iv >>> 20) & 31, "spd", (iv >>> 25) & 31);
        Map<String, Object> evs = slt.Json.map("hp", w[e] & 0xFF, "atk", (w[e] >>> 8) & 0xFF, "def", (w[e] >>> 16) & 0xFF,
                "spe", (w[e] >>> 24) & 0xFF, "spa", w[e + 1] & 0xFF, "spd", (w[e + 1] >>> 8) & 0xFF);
        return slt.Json.map("gen", 3L, "species", (long) mon.species(), "level", (long) mon.level(),
                "nickname", mon.nickname(), "moves", moves, "ppUps", ppUps, "ivs", ivs, "evs", evs, "nature", pid % 25,
                "abilitySlot", (iv >>> 31) & 1, "genderValue", pid & 0xFF, "happiness", (w[g + 2] >>> 8) & 0xFF,
                "item3", w[g] >>> 16, "shiny", mon.shiny(), "exp", w[g + 1]);
    }

    /** The 100 bytes of the party Pokémon {@code mon} as they are now, or null when its place holds another one. */
    private static byte[] liveMon(Mem m, Mon mon) {
        byte[] d = m.read(mon.address(), MON);
        return d == null || u32(d, 0) != mon.uid() || mon.egg() || substructs(d, 0) == null ? null : d;
    }

    /**
     * The write putting back the substructures {@code w} (decrypted words) of the Pokémon whose structure is {@code d}:
     * from the checksum (0x1C, the sum of their 16-bit halves) to the end of the 48 encrypted bytes.
     */
    private static Write sealed(Mon mon, byte[] d, long[] w) {
        long key = u32(d, 0) ^ u32(d, 4);
        byte[] out = new byte[0x34];
        out[2] = d[0x1E];
        out[3] = d[0x1F];
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (int) (w[i] & 0xFFFF) + (int) (w[i] >>> 16);
            long e = (w[i] ^ key) & 0xFFFFFFFFL;
            for (int b = 0; b < 4; b++) {
                out[4 + 4 * i + b] = (byte) (e >>> (8 * b));
            }
        }
        out[0] = (byte) sum;
        out[1] = (byte) (sum >>> 8);
        return new Write(mon.address() + 0x1C, out);
    }

    /** Held item: the high half of the first word of Growth. */
    @Override
    public int heldItem(Mem m, Mon mon) {
        byte[] d = liveMon(m, mon);
        if (d == null) {
            return -1;
        }
        long[] w = substructs(d, 0);
        return (int) (w[ORDERS[(int) (u32(d, 0) % 24)].indexOf('G') * 3] >>> 16);
    }

    @Override
    public List<Write> setHeldItem(Mem m, Mon mon, int item) {
        byte[] d = item < 0 || item > 0xFFFF || inBattle != 0 ? null : liveMon(m, mon);
        if (d == null) {
            return null;
        }
        long[] w = substructs(d, 0);
        int g = ORDERS[(int) (u32(d, 0) % 24)].indexOf('G') * 3;
        w[g] = (w[g] & 0xFFFF) | ((long) item << 16);
        return List.of(sealed(mon, d, w));
    }

    /**
     * Attacks: 4 moves (u16) then their PP (u8); Growth: PP Ups of each move (2 bits each, byte 8). The next moves move
     * up with their PP and PP Ups.
     */
    @Override
    public List<Write> forgetMove(Mem m, Mon mon, int move) {
        byte[] d = inBattle != 0 ? null : liveMon(m, mon);
        if (d == null) {
            return null;
        }
        long[] w = substructs(d, 0);
        String order = ORDERS[(int) (u32(d, 0) % 24)];
        int g = order.indexOf('G') * 3;
        int a = order.indexOf('A') * 3;
        int[] moves = {(int) (w[a] & 0xFFFF), (int) (w[a] >>> 16), (int) (w[a + 1] & 0xFFFF), (int) (w[a + 1] >>> 16)};
        int at = -1;
        int known = 0;
        for (int i = 0; i < 4; i++) {
            known += moves[i] != 0 ? 1 : 0;
            if (moves[i] == move && at < 0) {
                at = i;
            }
        }
        if (at < 0 || known <= 1) {
            return null;
        }
        int[] nm = new int[4];
        int[] pp = new int[4];
        int bonus = 0;
        int n = 0;
        for (int i = 0; i < 4; i++) {
            if (i != at) {
                nm[n] = moves[i];
                pp[n] = (int) ((w[a + 2] >>> (8 * i)) & 0xFF);
                bonus |= (int) (((w[g + 2] >>> (2 * i)) & 3) << (2 * n));
                n++;
            }
        }
        w[a] = nm[0] | ((long) nm[1] << 16);
        w[a + 1] = nm[2] | ((long) nm[3] << 16);
        w[a + 2] = pp[0] | ((long) pp[1] << 8) | ((long) pp[2] << 16) | ((long) pp[3] << 24);
        w[g + 2] = (w[g + 2] & ~0xFFL) | bonus;
        return List.of(sealed(mon, d, w));
    }

    /** Valid Pokémon structures following each other from {@code o} (a party array). */
    private List<Mon> run(byte[] d, int o, long base) {
        List<Mon> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Mon mon = decode(d, o + i * MON, base);
            if (mon == null) {
                break;
            }
            out.add(mon);
        }
        return out;
    }

    // -------------------------------------------------------------- reading

    @Override
    public Snapshot read(Mem m) {
        Mem.Block ew = m.block(EWRAM, EWRAM_SIZE);
        Mem.Block iw = m.block(IWRAM, IWRAM_SIZE);
        if (ew == null || iw == null) {
            return null;
        }
        locateSaveBlocks(ew, iw);
        locateMain(iw);
        Mem.Block pb = kind == Kind.RS ? iw : ew;
        List<Mon> mons = party >= 0 ? run(pb.data, (int) (party - pb.start), pb.start) : List.of();
        if (mons.isEmpty()) {
            party = locateParty(pb);
            mons = party >= 0 ? run(pb.data, (int) (party - pb.start), pb.start) : List.of();
        }
        int ot = -1;
        int badges = -1;
        int map = -1;
        int ballsFlag = -1;
        int cleared = -1;
        long money = -1;
        Map<Integer, Integer> bag = null;
        if (sb2 >= 0) {
            trainerId = u32(ew.data, (int) (sb2 - EWRAM) + 0xA);
            ot = (int) (trainerId & 0xFFFF);
        }
        if (sb1 >= 0) {
            int s1 = (int) (sb1 - EWRAM);
            map = (ew.data[s1 + 4] & 0xFF) * 256 + (ew.data[s1 + 5] & 0xFF);
            badges = 0;
            for (int i = 0; i < 8; i++) {
                if (flag(ew.data, s1, firstBadgeFlag + i)) {
                    badges++;
                }
            }
            cleared = flag(ew.data, s1, gameClearFlag) ? 1 : 0;
            long key = encryptionKey(ew);
            if (key >= 0) {
                money = u32(ew.data, s1 + moneyOffset) ^ key;
                if (money > 999_999) {
                    money = -1;
                }
                bag = bag(ew.data, s1, (int) (key & 0xFFFF));
            }
            // Poké Ball pocket: item id (u16) + quantity
            ballsFlag = 0;
            for (int i = 0; i < balls.slots(); i++) {
                int id = u16(ew.data, s1 + balls.offset() + 4 * i);
                if (id >= 1 && id <= 12) {
                    ballsFlag = 1;
                }
            }
        }
        if (mons.isEmpty() && sb1 < 0) {
            return null; // title screen / intro
        }
        List<Mon> wild = new ArrayList<>();
        List<Mon> enemy = new ArrayList<>();
        int battle = -1;
        if (party >= 0 && !mons.isEmpty()) {
            long enemyAddr = kind == Kind.FRLG ? party - 6 * MON : party + 6 * MON;
            Mem.Block eb = m.block(enemyAddr, 6 * MON);
            int playerOt = ot >= 0 ? ot : mons.get(0).otId();
            if (eb != null) {
                enemy = run(eb.data, (int) (enemyAddr - eb.start), eb.start);
                Mon e = enemy.isEmpty() ? null : enemy.get(0);
                if (e != null && e.otId() == playerOt && !e.egg() && mons.stream().noneMatch(x -> x.uid() == e.uid())) {
                    wild.add(e);
                }
            }
            int ib = inBattle;
            if (ib == 0) {
                battle = 0;
            } else if (ib == 1) {
                Mon e = enemy.isEmpty() ? null : enemy.get(0);
                battle = e != null && e.otId() != playerOt ? 2 : 1;
            }
        }
        return new Snapshot(mons, badges, map, ot, wild, battle, ballsFlag, bag, money, cleared, enemy);
    }

    /**
     * Battle style "Set": SaveBlock2 +0x15 bit 1 (+0x14: text speed 0-2 in bits 0-2, frame 0-19 in bits 3-7; +0x15:
     * sound, battle style, battle scene, map zoom), the same in Ruby / Sapphire, Emerald and FireRed / LeafGreen
     * (verified on the French Ruby). The block moves in Emerald and FireRed / LeafGreen: its trainer ID is checked.
     */
    @Override
    public List<Write> setBattleStyle(Mem m) {
        long at = sb2;
        long id = trainerId;
        if (at < 0) {
            return null;
        }
        byte[] b = m.read(at, 0x16);
        if (b == null || u32(b, 0xA) != id) {
            return null;
        }
        int o = b[0x14] & 0xFF;
        int v = b[0x15] & 0xFF;
        if ((o & 7) > 2 || (o >> 3) > 19 || (v & 0xF0) != 0) {
            return null;
        }
        return (v & 2) != 0 ? List.of() : List.of(new Write(at + 0x15, new byte[]{(byte) (v | 2)}));
    }

    private boolean flag(byte[] d, int s1, int flag) {
        int o = s1 + flagsOffset + (flag >> 3);
        return o < d.length && (d[o] & (1 << (flag & 7))) != 0;
    }

    /** XOR key of money and item quantities (0 in Ruby/Sapphire), or -1 when SaveBlock2 is unknown. */
    private long encryptionKey(Mem.Block ew) {
        if (keyOffset < 0) {
            return 0;
        }
        if (sb2 < 0) {
            return -1;
        }
        int o = (int) (sb2 - EWRAM) + keyOffset;
        return o + 4 <= ew.data.length ? u32(ew.data, o) : -1;
    }

    /** Every item of the bag (all pockets), or null if the bytes do not look like a bag. */
    private Map<Integer, Integer> bag(byte[] d, int s1, int key16) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Pocket p : new Pocket[]{items, keyItems, balls, tms, berries}) {
            for (int i = 0; i < p.slots(); i++) {
                int o = s1 + p.offset() + 4 * i;
                if (o + 4 > d.length) {
                    return null;
                }
                int id = u16(d, o);
                if (id == 0) {
                    continue;
                }
                int qty = u16(d, o + 2) ^ key16;
                if (id > 400 || qty > 999) {
                    return null;
                }
                out.merge(id, qty, Integer::sum);
            }
        }
        return out;
    }

    /**
     * Party array: a run of valid structures that is not the copy kept in
     * SaveBlock1; when the enemy array is filled too, the order of the two
     * arrays in memory tells which one is the player's.
     */
    private long locateParty(Mem.Block b) {
        long skip = sb1 >= 0 ? sb1 + sb1PartyOffset : -1;
        List<Long> starts = new ArrayList<>();
        for (int o = 0; o + MON <= b.data.length; o += 4) {
            if (b.start + o == skip) {
                continue;
            }
            if (decode(b.data, o, b.start) != null && (o < MON || decode(b.data, o - MON, b.start) == null)) {
                starts.add(b.start + o);
            }
        }
        if (starts.isEmpty()) {
            return -1;
        }
        for (long s : starts) {
            // Ruby/Sapphire/Emerald: player then enemy; FireRed/LeafGreen: enemy then player
            long other = kind == Kind.FRLG ? s - 6 * MON : s + 6 * MON;
            if (starts.contains(other)) {
                return s;
            }
        }
        // only one array filled: the player's (the enemy array is empty until the first battle)
        for (long s : starts) {
            long enemy = kind == Kind.FRLG ? s - 6 * MON : s + 6 * MON;
            if (b.contains(enemy, MON) && allZero(b.data, (int) (enemy - b.start), MON)) {
                return s;
            }
        }
        return starts.get(0);
    }

    private static boolean allZero(byte[] d, int o, int len) {
        for (int i = 0; i < len; i++) {
            if (d[o + i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** SaveBlock1 / SaveBlock2 / PC storage addresses. */
    private void locateSaveBlocks(Mem.Block ew, Mem.Block iw) {
        if (kind == Kind.RS) {
            if (sb2 < 0 && plausibleSb2(ew, 0x02024EA4L)) {
                sb2 = 0x02024EA4L;
                sb1 = 0x02025734L;
                storage = 0x020300A0L;
            }
            return;
        }
        // pointers to SaveBlock1, SaveBlock2 and the PC storage follow each other in IWRAM
        if (sb2 >= 0) {
            int o = (int) (pointerSlot - IWRAM);
            if (u32(iw.data, o) == sb1 && u32(iw.data, o + 4) == sb2 && plausibleSb2(ew, sb2)) {
                storage = u32(iw.data, o + 8);
                return;
            }
            sb1 = sb2 = storage = -1;
        }
        for (int o = 0; o + 12 <= iw.data.length; o += 4) {
            long p1 = u32(iw.data, o);
            long p2 = u32(iw.data, o + 4);
            long p3 = u32(iw.data, o + 8);
            if (!inEwram(p1, 0x3D00) || !inEwram(p2, 0xF00) || !inEwram(p3, 0x8400)) {
                continue;
            }
            if (!plausibleSb2(ew, p2)) {
                continue;
            }
            int s1 = (int) (p1 - EWRAM);
            int group = ew.data[s1 + 4];
            int num = ew.data[s1 + 5];
            if (group < 0 || num < 0 || (info.hasMaps() && info.map(group * 256 + num) == null)) {
                continue;
            }
            pointerSlot = IWRAM + o;
            sb1 = p1;
            sb2 = p2;
            storage = p3;
            return;
        }
    }

    /**
     * gMain: callbacks pointing into the cartridge, then a frame counter at
     * 0x24 that grows by the number of frames between two readings (about
     * 30); at 0x20 another counter (Ruby, Sapphire, Emerald) or a pointer,
     * often null (FireRed, LeafGreen), which rules out the same pattern 4 bytes earlier.
     * Found once, then its "in battle" bit is read at every reading.
     */
    private void locateMain(Mem.Block iw) {
        byte[] d = iw.data;
        if (main >= 0) {
            int o = (int) (main - IWRAM);
            if (romPointer(u32(d, o + 4))) {
                inBattle = (d[o + battleOffset] >> 1) & 1;
                return;
            }
            main = -1;
            inBattle = -1;
        }
        if (mainGivenUp) {
            return;
        }
        Map<Integer, long[]> found = new java.util.TreeMap<>();
        for (int o = 0; o + 0x440 <= d.length; o += 4) {
            long cb1 = u32(d, o);
            long cb2 = u32(d, o + 4);
            long vblank = u32(d, o + 0xC);
            if (!romPointer(cb2) || (cb1 != 0 && !romPointer(cb1)) || (vblank != 0 && !romPointer(vblank))) {
                continue;
            }
            long counter = u32(d, o + 0x24);
            if (counter < 60) {
                continue;
            }
            found.put(o, new long[]{counter, u32(d, o + 0x20)});
        }
        Map<Integer, long[]> previous = mainCandidates;
        mainCandidates = found;
        if (previous == null) {
            return;
        }
        // the known addresses first, then a second counter at 0x20, then a pointer, then a null one
        int best = -1;
        int bestScore = -1;
        for (Map.Entry<Integer, long[]> e : found.entrySet()) {
            long[] before = previous.get(e.getKey());
            if (before == null) {
                continue;
            }
            long delta = e.getValue()[0] - before[0];
            long delta20 = e.getValue()[1] - before[1];
            long at20 = e.getValue()[1];
            if (delta < 20 || delta > 40) {
                continue;
            }
            int score;
            if (KNOWN_MAIN.contains(IWRAM + e.getKey())) {
                score = 4;
            } else if (delta20 >= 20 && delta20 <= 40) {
                score = 3;
            } else if ((at20 >= IWRAM && at20 < IWRAM + IWRAM_SIZE) || (at20 >= EWRAM && at20 < EWRAM + EWRAM_SIZE)) {
                score = 2;
            } else if (at20 == 0) {
                score = 1;
            } else {
                continue;
            }
            if (score > bestScore) {
                best = e.getKey();
                bestScore = score;
            }
        }
        if (best >= 0) {
            main = IWRAM + best;
            inBattle = (d[best + battleOffset] >> 1) & 1;
            mainCandidates = null;
        }
    }

    /** The "in battle" bit was never seen while a wild battle was clearly running: stop trusting it. */
    void distrustBattleFlag() {
        mainGivenUp = true;
        main = -1;
        inBattle = -1;
    }

    /** Address of SaveBlock1 (player position at +0, map at +4), -1 while unknown. */
    public long saveBlock1() {
        return sb1;
    }

    /** Address of gMain (callbacks, "in battle" bit), -1 while unknown. */
    public long gMain() {
        return main;
    }

    /** Offset in gMain of the byte whose bit 1 means "in battle". */
    public int battleOffset() {
        return battleOffset;
    }

    /** Byte set to 1 while the player cannot move (dialogue, menu, cutscene, battle transition). */
    public long fieldLockAddress() {
        return lockAddress;
    }

    /** gPaletteFade: bit 7 of byte 7 = a fade of the screen is running. */
    public long paletteFadeAddress() {
        return fadeAddress;
    }

    private static boolean romPointer(long p) {
        return p >= 0x08000000L && p < 0x0A000000L && (p & 1) == 1;
    }

    private static boolean inEwram(long p, int size) {
        return p >= EWRAM && p + size <= EWRAM + EWRAM_SIZE && (p & 3) == 0;
    }

    /** Player name (1-7 letters terminated by 0xFF), gender 0/1, then trainer ID. */
    private static boolean plausibleSb2(Mem.Block ew, long addr) {
        int o = (int) (addr - EWRAM);
        if (o < 0 || o + 0x10 > ew.data.length) {
            return false;
        }
        int n = 0;
        while (n < 8 && (ew.data[o + n] & 0xFF) != 0xFF) {
            int c = ew.data[o + n] & 0xFF;
            if (c == 0 && n == 0) {
                return false;
            }
            n++;
        }
        int gender = ew.data[o + 8] & 0xFF;
        return n >= 1 && n <= 7 && gender <= 1 && u32(ew.data, o + 0xA) != 0;
    }

    @Override
    public Boolean stored(Mem m, long uid) {
        if (storage < 0) {
            return null;
        }
        Mem.Block b = m.block(storage, 4 + 14 * 30 * BOX_MON);
        if (b == null) {
            return null;
        }
        int o = (int) (storage - b.start) + 4;
        for (int i = 0; i < 14 * 30; i++) {
            if (u32(b.data, o + i * BOX_MON) == uid && uid != 0) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    @Override
    public Patch zeroHp(byte[] live, Mon mon) {
        if (live.length < MON || u32(live, 0) != mon.uid() || u16(live, 0x56) == 0) {
            return null;
        }
        return new Patch(0x56, new byte[]{0, 0});
    }

    // --------------------------------------------------------------- writing

    @Override
    public List<Write> nickname(Mem m, Mon mon, String name) {
        byte[] enc = info.encode(name, 10, 0xFF);
        byte[] live = m.read(mon.address(), MON);
        if (enc == null || live == null || u32(live, 0) != mon.uid()) {
            return null;
        }
        boolean same = true;
        for (int i = 0; i < 10; i++) {
            same &= live[8 + i] == enc[i];
        }
        return same ? List.of() : List.of(new Write(mon.address() + 8, enc));
    }

    @Override
    public List<Write> addItem(Mem m, int item, int quantity) {
        if (sb1 < 0 || item <= 0 || quantity <= 0) {
            return null;
        }
        Mem.Block ew = m.block(EWRAM, EWRAM_SIZE);
        long key = ew == null ? -1 : encryptionKey(ew);
        if (key < 0) {
            return null;
        }
        int key16 = (int) (key & 0xFFFF);
        Pocket p = item <= 12 ? balls : item >= 133 && item <= 175 ? berries : items;
        int s1 = (int) (sb1 - EWRAM);
        byte[] d = ew.data;
        for (int i = 0; i < p.slots(); i++) {
            int o = s1 + p.offset() + 4 * i;
            if (u16(d, o) == item) {
                int qty = Math.min(99, (u16(d, o + 2) ^ key16) + quantity);
                return List.of(new Write(sb1 + p.offset() + 4 * i + 2, le16(qty ^ key16)));
            }
        }
        for (int i = 0; i < p.slots(); i++) {
            int o = s1 + p.offset() + 4 * i;
            if (u16(d, o) == 0) {
                byte[] slot = new byte[4];
                System.arraycopy(le16(item), 0, slot, 0, 2);
                System.arraycopy(le16(Math.min(99, quantity) ^ key16), 0, slot, 2, 2);
                return List.of(new Write(sb1 + p.offset() + 4 * i, slot));
            }
        }
        return null;
    }

    /**
     * Every slot of {@code item} out of its pocket: the next slots move up, the last ones become empty (no item, its
     * quantity 0 encrypted with the key, as the games clear a slot).
     */
    @Override
    public List<Write> removeItem(Mem m, int item) {
        if (sb1 < 0 || item <= 0) {
            return null;
        }
        Mem.Block ew = m.block(EWRAM, EWRAM_SIZE);
        long key = ew == null ? -1 : encryptionKey(ew);
        if (key < 0) {
            return null;
        }
        int s1 = (int) (sb1 - EWRAM);
        byte[] d = ew.data;
        for (Pocket p : new Pocket[]{items, keyItems, balls, tms, berries}) {
            int o = s1 + p.offset();
            if (o + 4 * p.slots() > d.length) {
                return null;
            }
            byte[] out = new byte[4 * p.slots()];
            int n = 0;
            boolean found = false;
            for (int i = 0; i < p.slots(); i++) {
                int id = u16(d, o + 4 * i);
                if (id == item) {
                    found = true;
                } else if (id != 0) {
                    System.arraycopy(d, o + 4 * i, out, 4 * n++, 4);
                }
            }
            if (!found) {
                continue;
            }
            byte[] empty = le16((int) (key & 0xFFFF));
            for (int i = n; i < p.slots(); i++) {
                System.arraycopy(empty, 0, out, 4 * i + 2, 2);
            }
            return List.of(new Write(sb1 + p.offset(), out));
        }
        return null;
    }

    /**
     * Moves a dead Pokémon from the party to the "MORTS" box (last box, then
     * the previous ones when full). Only outside battles, never the last
     * Pokémon of the party, and only when the party counter is where expected.
     */
    @Override
    public List<Write> deposit(Mem m, Mon mon) {
        return toBox(m, mon, true);
    }

    @Override
    public List<Write> store(Mem m, Mon mon) {
        return toBox(m, mon, false);
    }

    /** Party to PC: a dead Pokémon to the MORTS box (then the boxes before it), a living one from the first box. */
    private List<Write> toBox(Mem m, Mon mon, boolean dead) {
        if (party < 0 || storage < 0 || inBattle != 0) {
            return null;
        }
        Mem.Block pb = m.block(party, 6 * MON);
        if (pb == null) {
            return null;
        }
        int po = (int) (party - pb.start);
        List<Mon> mons = run(pb.data, po, pb.start);
        long countAddr = party + partyCountDelta;
        int count = m.u8(countAddr);
        if (count != mons.size() || count <= 1) {
            return null;
        }
        int index = -1;
        for (int i = 0; i < mons.size(); i++) {
            if (mons.get(i).uid() == mon.uid()) {
                index = i;
            }
        }
        if (index < 0) {
            return null;
        }
        Mem.Block sb = m.block(storage, STORAGE_SIZE);
        if (sb == null) {
            return null;
        }
        int so = (int) (storage - sb.start);
        long target = -1;
        for (int i = 0; i < (dead ? DEAD_BOX + 1 : DEAD_BOX) && target < 0; i++) {
            int box = dead ? DEAD_BOX - i : i;
            for (int slot = 0; slot < BOX_SLOTS; slot++) {
                int o = so + 4 + (box * BOX_SLOTS + slot) * BOX_MON;
                if (allZero(sb.data, o, BOX_MON)) {
                    target = storage + 4 + (long) (box * BOX_SLOTS + slot) * BOX_MON;
                    break;
                }
            }
        }
        if (target < 0) {
            return null;
        }
        List<Write> out = new ArrayList<>();
        byte[] boxMon = new byte[BOX_MON];
        System.arraycopy(pb.data, po + index * MON, boxMon, 0, BOX_MON);
        out.add(new Write(target, boxMon));
        // party without this Pokémon: the next ones move up, the last slot is cleared
        byte[] compact = new byte[count * MON];
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (i != index) {
                System.arraycopy(pb.data, po + i * MON, compact, w, MON);
                w += MON;
            }
        }
        out.add(new Write(party, compact));
        out.add(new Write(countAddr, new byte[]{(byte) (count - 1)}));
        byte[] name = dead ? info.encode(DEAD_BOX_NAME, 9, 0xFF) : null;
        if (name != null) {
            out.add(new Write(storage + 4 + (long) BOXES * BOX_SLOTS * BOX_MON + DEAD_BOX * 9L, name));
        }
        return out;
    }

    /**
     * Releases a Pokémon (capture against the rules, cancelled zone): it
     * disappears from the party, or from the PC when it was sent there.
     * Only outside battles, never the last Pokémon of the party.
     */
    @Override
    public List<Write> release(Mem m, long uid) {
        if (party < 0 || storage < 0 || inBattle != 0 || uid == 0) {
            return null;
        }
        Mem.Block pb = m.block(party, 6 * MON);
        if (pb == null) {
            return null;
        }
        int po = (int) (party - pb.start);
        List<Mon> mons = run(pb.data, po, pb.start);
        for (int index = 0; index < mons.size(); index++) {
            if (mons.get(index).uid() != uid) {
                continue;
            }
            long countAddr = party + partyCountDelta;
            int count = m.u8(countAddr);
            if (count != mons.size() || count <= 1) {
                return null;
            }
            byte[] compact = new byte[count * MON];
            int w = 0;
            for (int i = 0; i < count; i++) {
                if (i != index) {
                    System.arraycopy(pb.data, po + i * MON, compact, w, MON);
                    w += MON;
                }
            }
            return List.of(new Write(party, compact), new Write(countAddr, new byte[]{(byte) (count - 1)}));
        }
        Mem.Block sb = m.block(storage, STORAGE_SIZE);
        if (sb == null) {
            return null;
        }
        int so = (int) (storage - sb.start);
        for (int i = 0; i < BOXES * BOX_SLOTS; i++) {
            if (u32(sb.data, so + 4 + i * BOX_MON) == uid) {
                return List.of(new Write(storage + 4 + (long) i * BOX_MON, new byte[BOX_MON]));
            }
        }
        return null;
    }

    /** Party index of {@code uid} in the live party, -1 if absent. */
    private int partyIndex(Mem m, long uid) {
        if (party < 0) {
            return -1;
        }
        Mem.Block pb = m.block(party, 6 * MON);
        if (pb == null) {
            return -1;
        }
        List<Mon> mons = run(pb.data, (int) (party - pb.start), pb.start);
        for (int i = 0; i < mons.size(); i++) {
            if (mons.get(i).uid() == uid) {
                return i;
            }
        }
        return -1;
    }

    /** The 100 bytes of the party structure (encrypted part and battle values). */
    @Override
    public byte[] tradeOut(Mem m, long uid) {
        int i = partyIndex(m, uid);
        return i < 0 ? null : m.read(party + (long) i * MON, MON);
    }

    @Override
    public List<Write> tradeIn(Mem m, long uid, byte[] blob) {
        int i = partyIndex(m, uid);
        if (i < 0 || inBattle != 0 || blob == null || blob.length != MON || decode(blob, 0, 0) == null) {
            return null;
        }
        return List.of(new Write(party + (long) i * MON, blob));
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    @Override
    public String toString() {
        return "GBA " + kind + " (équipe " + (party < 0 ? "?" : Long.toHexString(party)) + ", SB1 "
                + (sb1 < 0 ? "?" : Long.toHexString(sb1)) + ", SB2 " + (sb2 < 0 ? "?" : Long.toHexString(sb2))
                + ", PC " + (storage < 0 ? "?" : Long.toHexString(storage)) + ", gMain " + (main < 0 ? "?" : Long.toHexString(main)) + ")";
    }
}
