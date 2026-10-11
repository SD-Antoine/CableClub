package slt.player.track;

import java.util.ArrayList;
import java.util.List;

/**
 * Generations 1 and 2 (Game Boy / Game Boy Color). Work RAM is copied as
 * 0xC000-0xDFFF (bank 1 at 0xD000, where the save data lives); cartridge RAM
 * (gen 2 PC box) as {@link #SRAM} + offset.
 *
 * <pre>
 * Party: count, species list (6 + 0xFF), 6 structures (44 bytes gen 1 / 48 bytes gen 2),
 *        6 OT names (11 bytes), 6 nicknames (11 bytes). Values are big-endian.
 * A Pokémon has no personality value: the trainer ID and the DVs identify it.
 * The other variables used here sit at fixed distances from the party count
 * (same save-data block): the French versions move the whole block (gen 1: +5 bytes).
 * PC: the current box lives in work RAM (gen 1) or in cartridge RAM bank 1 (gen 2); the other
 *   boxes in cartridge RAM banks 2 and 3, each: count, species list (20 + 0xFF), 20 box structures
 *   (33 / 32 bytes: the start of the party structure), 20 OT names, 20 nicknames.
 *   The emulator only shows a copy of the cartridge RAM (written back over by the game): it is
 *   read, never written.
 * Hall of Fame: number of teams entered (more than 0 = the Champion was beaten).
 * </pre>
 */
public final class GbReader implements GameReader {

    static final long WRAM = 0xC000L;
    static final int WRAM_SIZE = 0x8000;
    static final long SRAM = 0x100000L;
    static final int SRAM_SIZE = 0x8000;

    /**
     * Offsets relative to the party count. The player ID, badges (and the current map in gen 1)
     * sit in the player data block: they are located from the player ID found near {@code id},
     * so a version laid out a few bytes differently is still read correctly.
     */
    /**
     * @param box      current box: gen 1 relative to the party (work RAM), gen 2 absolute (cartridge RAM)
     * @param curBox   current box number, relative to the party
     * @param hof      Hall of Fame teams, relative to the party
     * @param boxNames gen 2 box names, relative to the party (MIN_VALUE: no names)
     * @param x        player X (then Y one byte before), relative to the party
     * @param tilemap  copy of the screen tiles (absolute)
     */
    private record Layout(int gen, int party, int structSize, int id, int map, int badges, int badges2,
                          int battleMode, int enemyMon, int enemyDvs, int enemyLevel, long box, int boxStructSize,
                          int boxStart, int balls, int curBox, int hof, int boxNames, int x, int tilemap) {
    }

    private static final Layout RB = new Layout(1, 0xD163, 44, 0x1F6, 0x1FB, 0x1F3, Integer.MIN_VALUE, -0x10C,
            -0x17E, 12, 14, 0x91D, 33, 0x16, 0x1BA, 0x43D, 0x43F, Integer.MIN_VALUE, 0x1FF, 0xC3A0);
    private static final Layout YELLOW = new Layout(1, 0xD162, 44, 0x1F6, 0x1FB, 0x1F3, Integer.MIN_VALUE, -0x10C,
            -0x17E, 12, 14, 0x91D, 33, 0x16, 0x1BA, 0x43D, 0x43F, Integer.MIN_VALUE, 0x1FF, 0xC3A0);
    private static final Layout GS = new Layout(2, 0xDA22, 48, -0x881, -0x22, -0x4A6, -0x4A5, Integer.MIN_VALUE,
            0, 0, 0, SRAM + 0x2D6C, 32, 0x16, -0x426, -0x166, -0x39F, -0x163, -0x1F, 0xC3A0);
    private static final Layout CRYSTAL = new Layout(2, 0xDCD7, 48, -0x85C, -0x22, -0x480, -0x47F, -0xAAA,
            -0xAD1, 6, 13, SRAM + 0x2D10, 32, 0x16, -0x400, -0x165, -0x379, -0x162, -0x1F, 0xC4A0);

    private final Layout layout;
    private final GameInfo info;
    private final String game;
    private long party = -1;
    private volatile int lastShift;

    private GbReader(Layout layout, GameInfo info, String game) {
        this.layout = layout;
        this.info = info;
        this.game = game;
    }

    public static GbReader forGame(String game, GameInfo info) {
        return switch (game) {
            case "red", "blue" -> new GbReader(RB, info, game);
            case "yellow" -> new GbReader(YELLOW, info, game);
            case "gold", "silver" -> new GbReader(GS, info, game);
            case "crystal" -> new GbReader(CRYSTAL, info, game);
            default -> null;
        };
    }

    @Override
    public long[][] areas() {
        return new long[][]{{WRAM, WRAM_SIZE}, {SRAM, SRAM_SIZE}};
    }

    @Override
    public int structSize() {
        return layout.structSize;
    }

    private static int be16(byte[] d, int o) {
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    private int species(int raw) {
        if (layout.gen == 1) {
            return info.national(raw);
        }
        return raw >= 1 && raw <= 251 ? raw : -1;
    }

    private int levelOffset() {
        return layout.gen == 1 ? 33 : 31;
    }

    private int hpOffset() {
        return layout.gen == 1 ? 1 : 34;
    }

    private int maxHpOffset() {
        return layout.gen == 1 ? 34 : 36;
    }

    private int otOffset() {
        return layout.gen == 1 ? 12 : 6;
    }

    private int dvOffset() {
        return layout.gen == 1 ? 27 : 21;
    }

    static long uid(int ot, int dvs) {
        return ((long) ot << 16) | dvs;
    }

    /** Gen 2 shininess: Defense, Speed and Special DVs of 10, Attack DV 2, 3, 6, 7, 10, 11, 14 or 15. */
    private boolean shiny(int dvs) {
        if (layout.gen != 2) {
            return false;
        }
        int atk = (dvs >> 12) & 0xF;
        int def = (dvs >> 8) & 0xF;
        int spd = (dvs >> 4) & 0xF;
        int spc = dvs & 0xF;
        return def == 10 && spd == 10 && spc == 10 && (atk & 2) != 0;
    }

    /** Party at {@code p} (count byte), or null if the bytes are not a party. */
    private List<Mon> partyAt(byte[] d, int p) {
        int size = layout.structSize;
        if (p < 0 || p + 8 + 6 * (size + 22) > d.length) {
            return null;
        }
        int n = d[p] & 0xFF;
        if (n > 6 || (d[p + 1 + n] & 0xFF) != 0xFF) {
            return null;
        }
        List<Mon> out = new ArrayList<>();
        int structs = p + 8;
        int nicks = structs + 6 * size + 6 * 11;
        for (int i = 0; i < n; i++) {
            int listed = d[p + 1 + i] & 0xFF;
            int s = structs + i * size;
            int raw = d[s] & 0xFF;
            boolean egg = layout.gen == 2 && listed == 0xFD;
            if (!egg && listed != raw) {
                return null;
            }
            int species = species(raw);
            int level = d[s + levelOffset()] & 0xFF;
            int hp = be16(d, s + hpOffset());
            int maxHp = be16(d, s + maxHpOffset());
            if (species <= 0 || level < 1 || level > 100 || maxHp == 0 || maxHp > 999 || hp > maxHp) {
                return null;
            }
            int ot = be16(d, s + otOffset());
            int dvs = be16(d, s + dvOffset());
            String nick = info.text(d, nicks + i * 11, 11, 0x50);
            out.add(new Mon(uid(ot, dvs), species, level, hp, maxHp, nick, ot, -1, egg, shiny(dvs), WRAM + s));
        }
        return out;
    }

    @Override
    public Snapshot read(Mem m) {
        Mem.Block w = m.block(WRAM, WRAM_SIZE);
        if (w == null) {
            return null;
        }
        byte[] d = w.data;
        List<Mon> mons = party >= 0 ? partyAt(d, (int) (party - WRAM)) : null;
        if (mons == null) {
            party = locate(d);
            if (party < 0) {
                return null;
            }
            mons = partyAt(d, (int) (party - WRAM));
            if (mons == null) {
                return null;
            }
        }
        int p = (int) (party - WRAM);
        int ot = mons.isEmpty() ? -1 : majorityOt(mons);
        int shift = idShift(d, p, ot);
        if (shift != Integer.MIN_VALUE) {
            lastShift = shift;
        }
        int map = -1;
        if (layout.gen == 1) {
            if (shift != Integer.MIN_VALUE) {
                map = d[p + layout.map + shift] & 0xFF;
            }
        } else {
            map = (d[p + layout.map] & 0xFF) * 256 + (d[p + layout.map + 1] & 0xFF);
        }
        if (info.hasMaps() && info.map(map) == null) {
            map = -1;
        }
        int badges = -1;
        if (shift != Integer.MIN_VALUE) {
            badges = Integer.bitCount(d[p + layout.badges + shift] & 0xFF);
            if (layout.badges2 != Integer.MIN_VALUE) {
                badges += Integer.bitCount(d[p + layout.badges2 + shift] & 0xFF);
            }
        }
        int battle = -1;
        List<Mon> wild = new ArrayList<>();
        if (layout.battleMode != Integer.MIN_VALUE) {
            int mode = d[p + layout.battleMode] & 0xFF;
            battle = mode == 1 ? 1 : mode == 2 ? 2 : 0;
            if (mode == 1 && ot >= 0) {
                int e = p + layout.enemyMon;
                int species = species(d[e] & 0xFF);
                int level = d[e + layout.enemyLevel] & 0xFF;
                if (species > 0 && level >= 1 && level <= 100) {
                    int dvs = be16(d, e + layout.enemyDvs);
                    // battle copy of the wild Pokémon: HP at +1 / max HP at +15 (gen 1), +16 / +18 (Crystal)
                    int hp = be16(d, e + (layout.gen == 1 ? 1 : 16));
                    int maxHp = be16(d, e + (layout.gen == 1 ? 15 : 18));
                    if (maxHp == 0 || maxHp > 999 || hp > maxHp) {
                        hp = 1;
                        maxHp = 1;
                    }
                    wild.add(new Mon(uid(ot, dvs), species, level, hp, maxHp, null, ot, -1, false, shiny(dvs), WRAM + e));
                }
            }
        }
        java.util.Map<Integer, Integer> bag = null;
        long money = -1;
        if (layout.gen == 1) {
            int o = bagStart(d, p, shift);
            if (o >= 0) {
                bag = new java.util.HashMap<>();
                int n = d[o] & 0xFF;
                for (int i = 0; i < n; i++) {
                    bag.merge(d[o + 1 + 2 * i] & 0xFF, d[o + 2 + 2 * i] & 0xFF, Integer::sum);
                }
                money = bcd(d, o + 0x2A, 3);
            }
        }
        int cleared = -1;
        int hofAt = p + layout.hof + (layout.gen == 1 && shift != Integer.MIN_VALUE ? shift : 0);
        if (!mons.isEmpty() && hofAt >= 0 && hofAt < d.length) {
            cleared = (d[hofAt] & 0xFF) > 0 ? 1 : 0;
        }
        return new Snapshot(mons, badges, map, ot, wild, battle, balls(d, p), bag, money, cleared, List.of());
    }

    /** Gen 1 bag (count, then item / quantity pairs, then 0xFF), right after the party data or shifted like the player ID. */
    private int bagStart(byte[] d, int p, int shift) {
        for (int s : new int[]{0, shift}) {
            if (s == Integer.MIN_VALUE) {
                continue;
            }
            int o = p + layout.balls + s;
            if (o < 0 || o + 0x2D > d.length) {
                continue;
            }
            int n = d[o] & 0xFF;
            if (n <= 20 && (d[o + 1 + 2 * n] & 0xFF) == 0xFF && bcd(d, o + 0x2A, 3) >= 0) {
                return o;
            }
        }
        return -1;
    }

    /** Binary-coded decimal number (money), -1 if a digit is not decimal. */
    private static long bcd(byte[] d, int o, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            int b = d[o + i] & 0xFF;
            int hi = b >> 4;
            int lo = b & 0xF;
            if (hi > 9 || lo > 9) {
                return -1;
            }
            v = v * 100 + hi * 10 + lo;
        }
        return v;
    }

    /** Gen 1-2: nicknames of the party are 11-byte strings after the six OT names. */
    @Override
    public List<Write> nickname(Mem m, Mon mon, String name) {
        long p = party;
        if (p < 0) {
            return null;
        }
        long structs = p + 8;
        long off = mon.address() - structs;
        if (off < 0 || off % layout.structSize != 0 || off / layout.structSize >= 6) {
            return null;
        }
        int slot = (int) (off / layout.structSize);
        byte[] live = m.read(mon.address(), layout.structSize);
        if (live == null || uid(be16(live, otOffset()), be16(live, dvOffset())) != mon.uid()) {
            return null;
        }
        byte[] enc = info.encode(name, 10, 0x50);
        if (enc == null) {
            return null;
        }
        byte[] full = new byte[11];
        java.util.Arrays.fill(full, (byte) 0x50);
        System.arraycopy(enc, 0, full, 0, 10);
        long addr = structs + 6L * layout.structSize + 6 * 11 + slot * 11L;
        byte[] cur = m.read(addr, 11);
        if (cur == null) {
            return null;
        }
        return java.util.Arrays.equals(cur, full) ? List.of() : List.of(new Write(addr, full));
    }

    /**
     * Gen 1: bag = count then (item, quantity) pairs, balls are items 1-4 and 8 (Safari).
     * Gen 2: the ball pocket only holds balls, its count is enough.
     */
    private int balls(byte[] d, int p) {
        int o = p + layout.balls;
        if (o < 0 || o + 41 > d.length) {
            return -1;
        }
        int n = d[o] & 0xFF;
        if (layout.gen == 2) {
            return n > 12 ? -1 : n > 0 ? 1 : 0;
        }
        if (n > 20) {
            return -1;
        }
        for (int i = 0; i < n; i++) {
            int id = d[o + 1 + 2 * i] & 0xFF;
            int qty = d[o + 2 + 2 * i] & 0xFF;
            if (qty > 0 && ((id >= 1 && id <= 4) || id == 8)) {
                return 1;
            }
        }
        return 0;
    }

    /** Distance between the expected and the actual player ID (big-endian), MIN_VALUE if not found. */
    private int idShift(byte[] d, int p, int ot) {
        if (ot < 0) {
            return Integer.MIN_VALUE;
        }
        for (int i = 0; i <= 0x10; i++) {
            for (int s : new int[]{i, -i}) {
                int o = p + layout.id + s;
                if (o >= 0 && o + 2 <= d.length && be16(d, o) == ot) {
                    return s;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int majorityOt(List<Mon> mons) {
        int best = mons.get(0).otId();
        int bestCount = 0;
        for (Mon a : mons) {
            int c = 0;
            for (Mon b : mons) {
                if (b.otId() == a.otId()) {
                    c++;
                }
            }
            if (c > bestCount) {
                bestCount = c;
                best = a.otId();
            }
        }
        return best;
    }

    /** The usual address first (all Western versions), then a scan of bank 1. */
    private long locate(byte[] d) {
        int usual = layout.party - (int) WRAM;
        if (partyAt(d, usual) != null) {
            return layout.party;
        }
        for (int p = 0x1000; p < 0x2000 - 0x200; p++) {
            List<Mon> mons = partyAt(d, p);
            if (mons != null && !mons.isEmpty()) {
                return WRAM + p;
            }
        }
        return -1;
    }

    /** Address of the current box (gen 1: work RAM next to the party; gen 2: cartridge RAM). */
    private long currentBox() {
        if (layout.gen == 1) {
            return party < 0 ? -1 : party + layout.box;
        }
        return layout.box;
    }

    @Override
    public Boolean stored(Mem m, long uid) {
        long box = currentBox();
        Mem.Block b = box < 0 ? null : m.block(box, 1);
        if (b == null) {
            return null;
        }
        int o = (int) (box - b.start);
        int n = b.data[o] & 0xFF;
        if (n > 20 || (b.data[o + 1 + n] & 0xFF) != 0xFF) {
            return null;
        }
        for (int i = 0; i < n; i++) {
            int s = o + layout.boxStart + i * layout.boxStructSize;
            if (s + layout.boxStructSize > b.data.length) {
                return null;
            }
            if (uid(be16(b.data, s + otOffset()), be16(b.data, s + dvOffset())) == uid) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    // ------------------------------------------------------------------ PC

    /** Bytes of one box (count, species list, structures, OT names, nicknames). */
    private int boxBytes() {
        return 1 + 21 + 20 * layout.boxStructSize + 20 * 11 + 20 * 11;
    }

    /** A box that looks like a box: count up to 20 and the species list ended by 0xFF. */
    private static boolean validBox(byte[] b) {
        int n = b[0] & 0xFF;
        return n <= 20 && (b[1 + n] & 0xFF) == 0xFF;
    }

    /** The party block (count, species, structures, OT names, nicknames) without slot {@code index}. */
    private byte[] partyWithout(byte[] block, int index) {
        int size = layout.structSize;
        int n = block[0] & 0xFF;
        byte[] out = block.clone();
        int structs = 8;
        int ots = structs + 6 * size;
        int nicks = ots + 6 * 11;
        for (int i = index; i < n - 1; i++) {
            out[1 + i] = block[2 + i];
            System.arraycopy(block, structs + (i + 1) * size, out, structs + i * size, size);
            System.arraycopy(block, ots + (i + 1) * 11, out, ots + i * 11, 11);
            System.arraycopy(block, nicks + (i + 1) * 11, out, nicks + i * 11, 11);
        }
        out[0] = (byte) (n - 1);
        out[n] = (byte) 0xFF;
        return out;
    }

    /** The box bytes without slot {@code index}. */
    private byte[] boxWithout(byte[] box, int index) {
        int size = layout.boxStructSize;
        int n = box[0] & 0xFF;
        byte[] out = box.clone();
        int structs = 22;
        int ots = structs + 20 * size;
        int nicks = ots + 20 * 11;
        for (int i = index; i < n - 1; i++) {
            out[1 + i] = box[2 + i];
            System.arraycopy(box, structs + (i + 1) * size, out, structs + i * size, size);
            System.arraycopy(box, ots + (i + 1) * 11, out, ots + i * 11, 11);
            System.arraycopy(box, nicks + (i + 1) * 11, out, nicks + i * 11, 11);
        }
        out[0] = (byte) (n - 1);
        out[n] = (byte) 0xFF;
        return out;
    }

    private int boxIndexOf(byte[] box, long uid) {
        int n = box[0] & 0xFF;
        for (int i = 0; i < n; i++) {
            int s = 22 + i * layout.boxStructSize;
            if (uid(be16(box, s + otOffset()), be16(box, s + dvOffset())) == uid) {
                return i;
            }
        }
        return -1;
    }

    private int partyBlockBytes() {
        return 8 + 6 * layout.structSize + 6 * 11 + 6 * 11;
    }

    /**
     * Gen 1: moves a dead Pokémon from the party to the current PC box (work RAM, saved with the
     * game; gen 1 boxes have no names). Gen 2 keeps its current box in the cartridge RAM, which the
     * emulator only shows as a copy: dead Pokémon stay K.O. in the party there.
     * Outside battles, never the last Pokémon, only when the box has room.
     */
    @Override
    public List<Write> deposit(Mem m, Mon mon) {
        if (layout.gen != 1 || party < 0) {
            return null;
        }
        byte[] block = m.read(party, partyBlockBytes());
        List<Mon> mons = block == null ? null : partyAt(block, 0);
        if (mons == null || mons.size() <= 1) {
            return null;
        }
        int index = -1;
        for (int i = 0; i < mons.size(); i++) {
            if (mons.get(i).uid() == mon.uid()) {
                index = i;
            }
        }
        long target = currentBox();
        byte[] box = target < 0 ? null : m.read(target, boxBytes());
        if (index < 0 || box == null || !validBox(box)) {
            return null;
        }
        int n = box[0] & 0xFF;
        List<Write> out = new ArrayList<>();
        if (boxIndexOf(box, mon.uid()) < 0) {
            if (n >= 20) {
                return null; // box full: the Pokémon stays K.O. in the party
            }
            int size = layout.structSize;
            int boxSize = layout.boxStructSize;
            byte[] nb = box.clone();
            nb[0] = (byte) (n + 1);
            nb[1 + n] = block[1 + index];
            nb[2 + n] = (byte) 0xFF;
            System.arraycopy(block, 8 + index * size, nb, 22 + n * boxSize, boxSize);
            nb[22 + n * boxSize + 3] = block[8 + index * size + 33]; // level kept in the box structure
            System.arraycopy(block, 8 + 6 * size + index * 11, nb, 22 + 20 * boxSize + n * 11, 11);
            System.arraycopy(block, 8 + 6 * size + 6 * 11 + index * 11, nb, 22 + 20 * boxSize + 20 * 11 + n * 11, 11);
            out.add(new Write(target, nb));
        }
        out.add(new Write(party, partyWithout(block, index)));
        return out;
    }

    /** Party slot of {@code uid}, -1 if absent. */
    private int partySlot(byte[] block, long uid) {
        List<Mon> mons = block == null ? null : partyAt(block, 0);
        if (mons == null) {
            return -1;
        }
        for (int i = 0; i < mons.size(); i++) {
            if (mons.get(i).uid() == uid) {
                return i;
            }
        }
        return -1;
    }

    /** Structure, then species byte, OT name (11) and nickname (11): the party holds them apart. */
    @Override
    public byte[] tradeOut(Mem m, long uid) {
        if (party < 0) {
            return null;
        }
        byte[] block = m.read(party, partyBlockBytes());
        int i = partySlot(block, uid);
        if (i < 0) {
            return null;
        }
        int size = layout.structSize;
        byte[] out = new byte[size + 1 + 22];
        System.arraycopy(block, 8 + i * size, out, 0, size);
        out[size] = block[1 + i];
        System.arraycopy(block, 8 + 6 * size + i * 11, out, size + 1, 11);
        System.arraycopy(block, 8 + 6 * size + 6 * 11 + i * 11, out, size + 12, 11);
        return out;
    }

    @Override
    public List<Write> tradeIn(Mem m, long uid, byte[] blob) {
        if (party < 0 || blob == null || blob.length != layout.structSize + 23) {
            return null;
        }
        byte[] block = m.read(party, partyBlockBytes());
        int i = partySlot(block, uid);
        if (i < 0) {
            return null;
        }
        int size = layout.structSize;
        byte[] nb = block.clone();
        System.arraycopy(blob, 0, nb, 8 + i * size, size);
        nb[1 + i] = blob[size];
        System.arraycopy(blob, size + 1, nb, 8 + 6 * size + i * 11, 11);
        System.arraycopy(blob, size + 12, nb, 8 + 6 * size + 6 * 11 + i * 11, 11);
        return partyAt(nb, 0) == null ? null : List.of(new Write(party, nb));
    }

    @Override
    public String deadBox() {
        return "la boîte actuelle du PC";
    }

    /** Removes a released Pokémon from the party (never the last one) or, in gen 1, from the current box. */
    @Override
    public List<Write> release(Mem m, long uid) {
        if (party < 0 || uid == 0) {
            return null;
        }
        byte[] block = m.read(party, partyBlockBytes());
        List<Mon> mons = block == null ? null : partyAt(block, 0);
        if (mons == null) {
            return null;
        }
        for (int i = 0; i < mons.size(); i++) {
            if (mons.get(i).uid() == uid) {
                return mons.size() <= 1 ? null : List.of(new Write(party, partyWithout(block, i)));
            }
        }
        if (layout.gen != 1) {
            return null;
        }
        long cur = currentBox();
        byte[] box = cur < 0 ? null : m.read(cur, boxBytes());
        if (box != null && validBox(box)) {
            int i = boxIndexOf(box, uid);
            if (i >= 0) {
                return List.of(new Write(cur, boxWithout(box, i)));
            }
        }
        return null;
    }

    @Override
    public Patch zeroHp(byte[] live, Mon mon) {
        if (live.length < layout.structSize) {
            return null;
        }
        if (uid(be16(live, otOffset()), be16(live, dvOffset())) != mon.uid() || be16(live, hpOffset()) == 0) {
            return null;
        }
        return new Patch(hpOffset(), new byte[]{0, 0});
    }

    // ------------------------------------------------------------- presence

    public int gen() {
        return layout.gen;
    }

    /** Party count address, -1 while the party is not found. */
    public long partyAddress() {
        return party;
    }

    /** Player X in steps of 16 pixels (Y is the byte before), -1 while unknown. */
    public long xAddress() {
        return party < 0 ? -1 : party + layout.x + (layout.gen == 1 ? lastShift : 0);
    }

    /** Current map: one byte (gen 1) or group then number (gen 2), -1 while unknown. */
    public long mapAddress() {
        return party < 0 ? -1 : party + layout.map + (layout.gen == 1 ? lastShift : 0);
    }

    /** Battle mode byte (0 = no battle), -1 when unknown for this game. */
    public long battleAddress() {
        return party < 0 || layout.battleMode == Integer.MIN_VALUE ? -1 : party + layout.battleMode;
    }

    /** The 20 x 18 tiles shown on the screen (text box frames use tiles 0x79-0x7E). */
    public long tilemapAddress() {
        return layout.tilemap;
    }

    /**
     * Crystal: the game's copy of the 8 object palettes (work RAM bank 5, 0xD0C0; 4 colours of
     * 15 bits each), whose colours are the player's when the sprite tiles sit in video bank 1.
     * Work-RAM address as used here (0xC000 + offset in the 8 banks), -1 for the other games.
     */
    public long objectPalettesAddress() {
        return layout == CRYSTAL ? WRAM + 5 * 0x1000 + 0xC0 : -1;
    }

    @Override
    public String toString() {
        return "GB " + game + " (équipe " + (party < 0 ? "?" : Long.toHexString(party)) + ")";
    }
}
