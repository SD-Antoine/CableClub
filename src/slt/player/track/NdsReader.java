package slt.player.track;

import java.util.ArrayList;
import java.util.List;

/**
 * Generations 4 and 5 (Nintendo DS). The games keep the whole save in main
 * RAM and play directly on it: the party block starts with
 * {@code u32 capacity = 6, u32 count} followed by six party structures.
 * The other values (trainer ID, badges, current map, boxes) sit at fixed
 * offsets of the same save buffer.
 */
public final class NdsReader implements GameReader {

    private static final long RAM = 0x02000000L;
    private static final int RAM_SIZE = 4 << 20;
    /** Full scans for wild Pokémon are spaced out (they read the whole 4 MB). */
    private static final long WILD_SCAN_NS = 900_000_000L;

    /**
     * Save buffer layout of one family (offsets from the start of the save buffer):
     * party block header, trainer ID (the player name is the 16 bytes before), badges,
     * money, current map, PC boxes (30 slots of 136 bytes per box), Poké Ball pocket, box names
     * (0x28 bytes each, terminated by 0xFFFF). -1 = unknown.
     */
    record Layout(int partySize, int maxSpecies, int party, int trainerId, int badges, int badgesExtra,
                  int money, int map, int boxes, int boxCount, int boxStride, int balls, int ballSlots, int boxNames) {
    }

    /**
     * Black / White / Black 2 / White 2 (party, trainer, money, badges and map verified on Black 2 / White 2).
     * Poké Balls are in the main item pocket.
     */
    static final Layout GEN5 = new Layout(NdsPkm.GEN5_PARTY_SIZE, 649, 0x18E00, 0x19414, 0x21104, -1,
            0x21100, 0x19504, 0x400, 24, 0x1000, 0x18400, 310, 0x4);

    /*
     * Generation 4: "general" save block (layout of the save file, as documented by save editors),
     * followed by the storage block (18 boxes, box names), verified on the French Diamond, Platinum and
     * HeartGold.
     * The current map is read only when it names a real map.
     */
    static final Layout DP = new Layout(NdsPkm.GEN4_PARTY_SIZE, 493, 0x90, 0x74, 0x7E, -1,
            0x78, 0x1238, 0xC104, 18, 0xFF0, 0xCE0, 15, 0x1DFE4);
    static final Layout PT = new Layout(NdsPkm.GEN4_PARTY_SIZE, 493, 0x98, 0x78, 0x82, -1,
            0x7C, 0x1280, 0xCF30, 18, 0xFF0, 0xCEC, 15, 0x1EE10);
    static final Layout HGSS = new Layout(NdsPkm.GEN4_PARTY_SIZE, 493, 0x90, 0x74, 0x7E, 0x83,
            0x78, 0x1234, 0xF700, 18, 0x1000, 0xD04, 24, 0x21708);

    /** Bag pockets of each family: {offset in the save buffer, slots} (documented save layouts). */
    private static final int[][] POCKETS_GEN5 = {{0x18400, 310}, {0x188D8, 83}, {0x18A24, 109}, {0x18BD8, 48}, {0x18C98, 64}};
    private static final int[][] POCKETS_DP = {{0x624, 165}, {0x8B8, 50}, {0x980, 100}, {0xB10, 12}, {0xB40, 40},
        {0xBE0, 64}, {0xCE0, 15}, {0xD1C, 30}};
    private static final int[][] POCKETS_PT = {{0x630, 165}, {0x8C4, 50}, {0x98C, 100}, {0xB1C, 12}, {0xB4C, 40},
        {0xBEC, 64}, {0xCEC, 15}, {0xD28, 30}};
    private static final int[][] POCKETS_HGSS = {{0x644, 165}, {0x8D8, 50}, {0x9A4, 100}, {0xB34, 12}, {0xB64, 40},
        {0xC04, 64}, {0xD04, 24}, {0xD64, 30}};

    /**
     * Maps only reached after beating the Champion (map headers of the decompilations: Platinum and
     * Diamond / Pearl: hallway to the Hall of Fame 186, Hall of Fame 187; HeartGold / SoulSilver: Hall
     * of Fame 306; Black / White and Black 2 / White 2: Hall of Fame 145, the map after the four Elite Four
     * rooms and the Champion's in the headers (a/0/1/2: same music 0x3E9, same index in the four ROMs).
     */
    private static int[] hallOfFame(Layout layout) {
        if (layout == HGSS) {
            return new int[]{306};
        }
        if (layout == DP || layout == PT) {
            return new int[]{186, 187};
        }
        return layout == GEN5 ? new int[]{145} : new int[0];
    }

    private final Layout layout;
    private final String game;
    private final GameInfo info;
    private long base = -1;
    private int trainerShift;
    private long lastWildScan;
    private List<Mon> lastWild = List.of();
    private volatile boolean fieldShown;

    public NdsReader(Layout layout, String game, GameInfo info) {
        this.layout = layout;
        this.game = game;
        this.info = info;
    }

    public static NdsReader forGame(String game, GameInfo info) {
        return switch (game) {
            case "black", "white", "black2", "white2" -> new NdsReader(GEN5, game, info);
            case "diamond", "pearl" -> new NdsReader(DP, game, info);
            case "platinum" -> new NdsReader(PT, game, info);
            case "heartgold", "soulsilver" -> new NdsReader(HGSS, game, info);
            default -> null;
        };
    }

    /**
     * The game shows the field with the camera on the player (told by the presence in game): there is no battle, so no
     * wild Pokémon to look for in the memory.
     */
    public void fieldShown(boolean on) {
        fieldShown = on;
    }

    /** The game ("white2"…). */
    public String game() {
        return game;
    }

    /** Generation 5 (Black / White, Black 2 / White 2). */
    public boolean gen5() {
        return layout == GEN5;
    }

    /** Console address of the current map number (u16) in the live save buffer, -1 until the save is found. */
    public long mapAddress() {
        return base < 0 || layout.map < 0 ? -1 : base + layout.map;
    }

    /**
     * Console address of the player's gender (u8: 0 boy, 1 girl) in the live save buffer, -1 until the save
     * is found: generation 5, trainer card (trainer ID at +0x14, gender at +0x21; verified on White 2);
     * generation 4, 8 bytes after the trainer ID (after the money; verified on Platinum).
     */
    public long genderAddress() {
        if (base < 0) {
            return -1;
        }
        return base + layout.trainerId + trainerShift + (layout == GEN5 ? 0x0D : 8);
    }

    @Override
    public long[][] areas() {
        return new long[][]{{RAM, RAM_SIZE}};
    }

    @Override
    public int structSize() {
        return layout.partySize;
    }

    @Override
    public Snapshot read(Mem m) {
        Mem.Block ram = m.block(RAM, RAM_SIZE);
        if (ram == null) {
            return null;
        }
        byte[] d = ram.data;
        if (base < 0 || partyAt(d, (int) (base - RAM) + layout.party) == null) {
            base = locate(d);
            if (base < 0) {
                return null;
            }
        }
        int b = (int) (base - RAM);
        List<Mon> party = partyAt(d, b + layout.party);
        if (party == null) {
            base = -1;
            return null;
        }
        int ot = NdsPkm.u16(d, b + layout.trainerId + trainerShift);
        int badges = Integer.bitCount(d[b + layout.badges + trainerShift] & 0xFF);
        if (layout.badgesExtra >= 0) {
            badges += Integer.bitCount(d[b + layout.badgesExtra + trainerShift] & 0xFF);
        }
        int map = layout.map >= 0 ? NdsPkm.u16(d, b + layout.map) : -1;
        if (map >= 0 && info != null && info.hasMaps() && info.map(map) == null) {
            map = -1;
        }
        long now = System.nanoTime();
        if (fieldShown) {
            // no battle on the field: a Pokémon of the player outside the party is a copy the game keeps (the starters
            // not chosen, a gift on its way), not a wild one; the next battle is scanned at once
            lastWild = List.of();
            lastWildScan = 0;
        } else if (!party.isEmpty() && now - lastWildScan > WILD_SCAN_NS) {
            lastWildScan = now;
            lastWild = scanWild(d, party, ot);
        }
        long money = layout.money >= 0 ? NdsPkm.u32(d, b + layout.money + (layout == GEN5 ? 0 : trainerShift)) : -1;
        if (money > 9_999_999) {
            money = -1;
        }
        // the Champion is beaten when the player stands in the Hall of Fame (with 8 badges: never by mistake)
        int cleared = -1;
        int[] hof = hallOfFame(layout);
        if (hof.length > 0 && map >= 0) {
            cleared = 0;
            for (int h : hof) {
                if (map == h && badges >= 8) {
                    cleared = 1;
                }
            }
        }
        return new Snapshot(party, badges, map, ot, lastWild, -1, balls(d, b), bag(d, b), money, cleared, List.of());
    }

    private int[][] pockets() {
        if (layout == GEN5) {
            return POCKETS_GEN5;
        }
        if (layout == PT) {
            return POCKETS_PT;
        }
        return layout == HGSS ? POCKETS_HGSS : POCKETS_DP;
    }

    /** The pocket each item was last seen in (to give an item back where it was). */
    private final java.util.Map<Integer, Integer> pocketOf = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every item of the bag, or null if the bytes do not look like a bag. */
    private java.util.Map<Integer, Integer> bag(byte[] d, int b) {
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        int[][] pockets = pockets();
        for (int k = 0; k < pockets.length; k++) {
            int[] pocket = pockets[k];
            for (int i = 0; i < pocket[1]; i++) {
                int o = b + pocket[0] + 4 * i;
                if (o + 4 > d.length) {
                    return null;
                }
                int id = NdsPkm.u16(d, o);
                if (id == 0) {
                    continue;
                }
                int qty = NdsPkm.u16(d, o + 2);
                if (id > 700 || qty > 999) {
                    return null;
                }
                out.merge(id, qty, Integer::sum);
                seen.put(id, k);
            }
        }
        pocketOf.putAll(seen);
        return out;
    }

    /**
     * Items given back (zone cancelled): in the pocket where the item was seen (or the usual one: Poké Balls, medicine,
     * battle items), added to its stack or in the first free slot, 999 at most. The bag of the live save buffer is the
     * one the game uses.
     */
    @Override
    public List<Write> addItem(Mem m, int item, int quantity) {
        if (base < 0 || item <= 0 || item > 700 || quantity <= 0) {
            return null;
        }
        int[][] pockets = pockets();
        Integer known = pocketOf.get(item);
        int k = known != null ? known : usualPocket(item);
        if (k < 0 || k >= pockets.length) {
            return null;
        }
        int[] pocket = pockets[k];
        long at = base + pocket[0];
        byte[] slots = m.read(at, 4 * pocket[1]);
        if (slots == null) {
            return null;
        }
        int free = -1;
        for (int i = 0; i < pocket[1]; i++) {
            int id = NdsPkm.u16(slots, 4 * i);
            if (id == item) {
                int qty = Math.min(999, NdsPkm.u16(slots, 4 * i + 2) + quantity);
                return List.of(new Write(at + 4L * i + 2, new byte[]{(byte) qty, (byte) (qty >>> 8)}));
            }
            if (id == 0 && free < 0) {
                free = i;
            }
        }
        if (free < 0) {
            return null;
        }
        int qty = Math.min(999, quantity);
        return List.of(new Write(at + 4L * free, new byte[]{(byte) item, (byte) (item >>> 8), (byte) qty, (byte) (qty >>> 8)}));
    }

    /** Every slot of {@code item} out of its pocket: the next slots move up (the games keep their pockets packed). */
    @Override
    public List<Write> removeItem(Mem m, int item) {
        if (base < 0 || item <= 0 || item > 700) {
            return null;
        }
        for (int[] pocket : pockets()) {
            long at = base + pocket[0];
            byte[] slots = m.read(at, 4 * pocket[1]);
            if (slots == null) {
                return null;
            }
            byte[] out = new byte[slots.length];
            int n = 0;
            boolean found = false;
            for (int i = 0; i < pocket[1]; i++) {
                int id = NdsPkm.u16(slots, 4 * i);
                if (id == item) {
                    found = true;
                } else if (id != 0) {
                    System.arraycopy(slots, 4 * i, out, 4 * n++, 4);
                }
            }
            if (found) {
                return List.of(new Write(at, out));
            }
        }
        return null;
    }

    /** The party block (capacity, count, structures) of the live save buffer, or null. */
    private byte[] liveParty(Mem m) {
        long hdr = partyHeader();
        byte[] block = hdr < 0 ? null : m.read(hdr, 8 + 6 * layout.partySize);
        return block == null || NdsPkm.u32(block, 0) != 6 ? null : block;
    }

    @Override
    public int heldItem(Mem m, Mon mon) {
        byte[] block = mon.egg() ? null : liveParty(m);
        int i = block == null ? -1 : partyIndex(block, mon.uid());
        return i < 0 ? -1 : NdsPkm.heldItem(block, 8 + i * layout.partySize);
    }

    /** Held item: word 1 of block A, in the encrypted structure. */
    @Override
    public List<Write> setHeldItem(Mem m, Mon mon, int item) {
        return rewrite(m, mon, (data, a, b, c, d) -> {
            if (data[a + 1] == item || item < 0 || item > 0xFFFF) {
                return false;
            }
            data[a + 1] = item;
            return true;
        });
    }

    @Override
    public List<Write> forgetMove(Mem m, Mon mon, int move) {
        return rewrite(m, mon, (data, a, b, c, d) -> NdsPkm.forget(data, b, move));
    }

    /** Writes changing the encrypted structure of the party Pokémon {@code mon}, null when nothing changes. */
    private List<Write> rewrite(Mem m, Mon mon, NdsPkm.Edit edit) {
        byte[] block = mon.egg() ? null : liveParty(m);
        int i = block == null ? -1 : partyIndex(block, mon.uid());
        if (i < 0) {
            return null;
        }
        int off = 8 + i * layout.partySize;
        byte[] fresh = NdsPkm.rewritten(block, off, edit);
        return fresh == null ? null : List.of(new Write(partyHeader() + off, fresh));
    }

    /**
     * The pocket of an item never seen in the bag: generation 4 Poké Balls (1-16, Apricorn balls 492-500) in the
     * ball pocket, medicine (17-54) and battle items (55-64) in theirs; generation 5 has its balls and battle items in
     * the main pocket, its medicine in the fourth.
     */
    private int usualPocket(int item) {
        boolean ball = (item >= 1 && item <= 16) || (item >= 492 && item <= 500) || item == 576;
        if (layout == GEN5) {
            return item >= 17 && item <= 54 ? 3 : 0;
        }
        if (ball) {
            return 6;
        }
        return item >= 17 && item <= 54 ? 4 : item >= 55 && item <= 64 ? 7 : 0;
    }

    /** 1 if a Poké Ball (items 1-16, HeartGold / SoulSilver Apricorn balls 492-500, Dream Ball 576) is in the bag, 0 if not, -1 if unknown. */
    private int balls(byte[] d, int b) {
        if (layout.balls < 0) {
            return -1;
        }
        for (int i = 0; i < layout.ballSlots; i++) {
            int o = b + layout.balls + 4 * i;
            if (o + 4 > d.length) {
                return -1;
            }
            int id = NdsPkm.u16(d, o);
            int qty = NdsPkm.u16(d, o + 2);
            if (qty > 0 && ((id >= 1 && id <= 16) || (id >= 492 && id <= 500) || id == 576)) {
                return 1;
            }
        }
        return 0;
    }

    /** Party at {@code hdr} (capacity, count, structures), or null if the bytes are not a party. */
    private List<Mon> partyAt(byte[] d, int hdr) {
        if (hdr < 0 || hdr + 8 + 6 * layout.partySize > d.length) {
            return null;
        }
        if (NdsPkm.u32(d, hdr) != 6) {
            return null;
        }
        long count = NdsPkm.u32(d, hdr + 4);
        if (count > 6) {
            return null;
        }
        List<Mon> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = hdr + 8 + i * layout.partySize;
            Mon mon = NdsPkm.decode(d, off, layout.partySize, layout.maxSpecies, RAM);
            if (mon == null) {
                mon = NdsPkm.decodeAny(d, off, layout.partySize, layout.maxSpecies, RAM);
                if (mon == null || !mon.egg()) {
                    return null;
                }
            }
            out.add(mon);
        }
        return out;
    }

    /**
     * Finds the save buffer: a party block whose trainer block (same buffer)
     * holds the player's ID and a name. When several copies exist, the live
     * one is the one with money (the backup copy is blank there).
     */
    private long locate(byte[] d) {
        long best = -1;
        int bestScore = -1;
        int bestShift = 0;
        for (int hdr = 0; hdr + 8 <= d.length; hdr += 4) {
            if (d[hdr] != 6 || d[hdr + 1] != 0 || d[hdr + 2] != 0 || d[hdr + 3] != 0) {
                continue;
            }
            int start = hdr - layout.party;
            if (start < 0 || start + Math.max(layout.badges, layout.money) + 0x48 > d.length) {
                continue;
            }
            List<Mon> party = partyAt(d, hdr);
            if (party == null) {
                continue;
            }
            int shift = trainerShift(d, start, party);
            if (shift == Integer.MIN_VALUE) {
                continue;
            }
            long money = NdsPkm.u32(d, start + layout.money + shift);
            if (money > 999_999) {
                continue; // not the player's money: another buffer that happens to look alike
            }
            int score = party.size() * 2 + (money > 0 ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = RAM + start;
                bestShift = shift;
            }
        }
        trainerShift = bestShift;
        return best;
    }

    /**
     * Where the trainer block really is, relative to the documented layout:
     * 0 when the player name and ID are found at the usual place; otherwise the
     * nearby position holding a name followed by the ID of the party's trainer
     * (a version may be laid out slightly differently). MIN_VALUE if not found.
     */
    private int trainerShift(byte[] d, int start, List<Mon> party) {
        if (trainerOk(d, start, 0, party)) {
            return 0;
        }
        if (party.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        for (int s = 2; s <= 0x40; s += 2) {
            if (trainerOk(d, start, -s, party)) {
                return -s;
            }
            if (trainerOk(d, start, s, party)) {
                return s;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean trainerOk(byte[] d, int start, int shift, List<Mon> party) {
        int t = start + layout.trainerId + shift;
        if (t - 0x10 < 0 || t + 2 > d.length || !plausibleName(d, t - 0x10, layout != GEN5)) {
            return false;
        }
        int ot = NdsPkm.u16(d, t);
        return party.isEmpty() || party.stream().anyMatch(p -> p.otId() == ot);
    }

    /** Player name: 1 to 7 characters terminated by 0xFFFF (gen 4: the game's codes, all below 0x200; gen 5: UTF-16). */
    private static boolean plausibleName(byte[] d, int off, boolean gen4) {
        if (off < 0 || off + 16 > d.length) {
            return false;
        }
        int n = 0;
        for (int i = 0; i < 8; i++) {
            int ch = NdsPkm.u16(d, off + 2 * i);
            if (ch == 0xFFFF) {
                break;
            }
            if (gen4 ? ch == 0 || ch >= 0x200 : ch < 0x20) {
                return false;
            }
            n++;
        }
        return n >= 1 && n <= 7;
    }

    /**
     * Wild Pokémon in battle: the battle engine works on copies of the
     * Pokémon structures; a wild one carries the player's ID (trainers' Pokémon
     * have their own) and is not in the party.
     */
    private List<Mon> scanWild(byte[] d, List<Mon> party, int ot) {
        long[] mine = party.stream().mapToLong(Mon::uid).toArray();
        List<Mon> out = new ArrayList<>();
        int size = layout.partySize;
        for (int off = 0; off + size <= d.length; off += 4) {
            if (!NdsPkm.plausible(d, off, size)) {
                continue;
            }
            Mon mon = NdsPkm.decode(d, off, size, layout.maxSpecies, RAM);
            if (mon == null || mon.otId() != ot || mon.egg() || contains(mine, mon.uid())) {
                continue;
            }
            if (out.stream().noneMatch(x -> x.uid() == mon.uid())) {
                out.add(mon);
            }
            if (out.size() > 8) {
                break;
            }
        }
        return out;
    }

    private static boolean contains(long[] a, long v) {
        for (long x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Battle style "Set": bit 6 of the options (u16: text speed 0-2 in bits 0-3, sound in bits 4-5, battle style,
     * battle scene…), the 4 bytes before the player name in generations 4 and 5 (verified on the French SoulSilver
     * and White 2). The battles read them from the live save buffer.
     */
    @Override
    public List<Write> setBattleStyle(Mem m) {
        if (base < 0) {
            return null;
        }
        long at = base + layout.trainerId + trainerShift - 0x14;
        byte[] b = m.read(at, 2);
        if (b == null) {
            return null;
        }
        int v = b[0] & 0xFF;
        if ((v & 0x0F) > 2 || ((v >> 4) & 3) > 1) {
            return null;
        }
        return (v & 0x40) != 0 ? List.of() : List.of(new Write(at, new byte[]{(byte) (v | 0x40)}));
    }

    @Override
    public java.util.Map<String, Object> battleSet(Mem m, Mon mon) {
        byte[] d = m.read(mon.address(), layout.partySize);
        return d == null ? null : NdsPkm.battleSet(d, 0, layout.partySize, mon);
    }

    @Override
    public Boolean stored(Mem m, long uid) {
        if (base < 0 || layout.boxes < 0) {
            return null;
        }
        Mem.Block ram = m.block(RAM, RAM_SIZE);
        if (ram == null) {
            return null;
        }
        byte[] d = ram.data;
        int start = (int) (base - RAM) + layout.boxes;
        for (int box = 0; box < layout.boxCount; box++) {
            for (int slot = 0; slot < 30; slot++) {
                int off = start + box * layout.boxStride + slot * NdsPkm.BOX_SIZE;
                if (off + NdsPkm.BOX_SIZE > d.length) {
                    return null;
                }
                if (NdsPkm.u32(d, off) == uid && d[off + 4] == 0 && d[off + 5] == 0) {
                    return Boolean.TRUE;
                }
            }
        }
        return Boolean.FALSE;
    }

    // --------------------------------------------------------------- writing

    /** Party header (capacity, count) of the live save buffer, -1 while unknown. */
    private long partyHeader() {
        return base < 0 ? -1 : base + layout.party;
    }

    /** Index of {@code uid} in the party at {@code hdr} (copy {@code block}), -1 if absent. */
    private int partyIndex(byte[] block, long uid) {
        long count = NdsPkm.u32(block, 4);
        for (int i = 0; i < count && i < 6; i++) {
            if (NdsPkm.u32(block, 8 + i * layout.partySize) == uid) {
                return i;
            }
        }
        return -1;
    }

    /** The party block without slot {@code index}: the next ones move up, the last slot is cleared. */
    private byte[] partyWithout(byte[] block, int index) {
        int size = layout.partySize;
        int count = (int) NdsPkm.u32(block, 4);
        byte[] out = block.clone();
        for (int i = index; i < count - 1; i++) {
            System.arraycopy(block, 8 + (i + 1) * size, out, 8 + i * size, size);
        }
        System.arraycopy(NdsPkm.blank(size), 0, out, 8 + (count - 1) * size, size);
        out[4] = (byte) (count - 1);
        return out;
    }

    /** Nickname of the link, written in the encrypted structure (party Pokémon). */
    @Override
    public List<Write> nickname(Mem m, Mon mon, String name) {
        long hdr = partyHeader();
        byte[] block = hdr < 0 ? null : m.read(hdr, 8 + 6 * layout.partySize);
        if (block == null || NdsPkm.u32(block, 0) != 6) {
            return null;
        }
        int i = partyIndex(block, mon.uid());
        if (i < 0) {
            return null;
        }
        int off = 8 + i * layout.partySize;
        Mon live = NdsPkm.decodeAny(block, off, layout.partySize, layout.maxSpecies, 0);
        if (live != null && name.equals(live.nickname())) {
            return List.of();
        }
        byte[] fresh = NdsPkm.withNickname(block, off, name, layout != GEN5);
        return fresh == null ? null : List.of(new Write(hdr + off, fresh));
    }

    /** The whole party structure (encrypted parts included). */
    @Override
    public byte[] tradeOut(Mem m, long uid) {
        long hdr = partyHeader();
        byte[] block = hdr < 0 ? null : m.read(hdr, 8 + 6 * layout.partySize);
        if (block == null || NdsPkm.u32(block, 0) != 6) {
            return null;
        }
        int i = partyIndex(block, uid);
        return i < 0 ? null : java.util.Arrays.copyOfRange(block, 8 + i * layout.partySize, 8 + (i + 1) * layout.partySize);
    }

    @Override
    public List<Write> tradeIn(Mem m, long uid, byte[] blob) {
        long hdr = partyHeader();
        byte[] block = hdr < 0 ? null : m.read(hdr, 8 + 6 * layout.partySize);
        if (block == null || NdsPkm.u32(block, 0) != 6 || blob == null || blob.length != layout.partySize
                || NdsPkm.decodeAny(blob, 0, layout.partySize, layout.maxSpecies, 0) == null) {
            return null;
        }
        int i = partyIndex(block, uid);
        return i < 0 ? null : List.of(new Write(hdr + 8 + (long) i * layout.partySize, blob));
    }

    private static final String DEAD_BOX_NAME = "MORTS";

    /**
     * Moves a dead Pokémon from the party to the last PC box (renamed MORTS), or the previous
     * ones when it is full: its first 136 bytes are the PC form. Never the last Pokémon.
     */
    @Override
    public List<Write> deposit(Mem m, Mon mon) {
        return toBox(m, mon, true);
    }

    @Override
    public List<Write> store(Mem m, Mon mon) {
        return toBox(m, mon, false);
    }

    /** Party to PC: a dead Pokémon to the last box (MORTS, then the ones before), a living one from the first box. */
    private List<Write> toBox(Mem m, Mon mon, boolean dead) {
        long hdr = partyHeader();
        if (hdr < 0 || layout.boxes < 0) {
            return null;
        }
        byte[] block = m.read(hdr, 8 + 6 * layout.partySize);
        if (block == null || NdsPkm.u32(block, 0) != 6 || NdsPkm.u32(block, 4) <= 1) {
            return null;
        }
        int i = partyIndex(block, mon.uid());
        if (i < 0) {
            return null;
        }
        List<Write> out = new ArrayList<>();
        if (boxSlotOf(m, mon.uid()) < 0) {
            long target = -1;
            for (int k = 0; k < (dead ? layout.boxCount : layout.boxCount - 1) && target < 0; k++) {
                int box = dead ? layout.boxCount - 1 - k : k;
                for (int slot = 0; slot < 30; slot++) {
                    long a = base + layout.boxes + (long) box * layout.boxStride + (long) slot * NdsPkm.BOX_SIZE;
                    byte[] cur = m.read(a, NdsPkm.BOX_SIZE);
                    if (cur != null && NdsPkm.isEmpty(cur)) {
                        target = a;
                        break;
                    }
                }
            }
            if (target < 0) {
                return null;
            }
            out.add(new Write(target, java.util.Arrays.copyOfRange(block, 8 + i * layout.partySize, 8 + i * layout.partySize + NdsPkm.BOX_SIZE)));
        }
        out.add(new Write(hdr, partyWithout(block, i)));
        if (dead && layout.boxNames >= 0) {
            byte[] name = new byte[0x28];
            boolean gen4 = layout != GEN5;
            int n = 0;
            for (char ch : DEAD_BOX_NAME.toCharArray()) {
                int code = gen4 ? NdsPkm.gen4Code(String.valueOf(ch)) : ch;
                name[n * 2] = (byte) code;
                name[n * 2 + 1] = (byte) (code >>> 8);
                n++;
            }
            name[n * 2] = (byte) 0xFF;
            name[n * 2 + 1] = (byte) 0xFF;
            out.add(new Write(base + layout.boxNames + (layout.boxCount - 1) * 0x28L, name));
        }
        return out;
    }

    /** Removes a released Pokémon from the party (never the last one) or from the PC. */
    @Override
    public List<Write> release(Mem m, long uid) {
        long hdr = partyHeader();
        byte[] block = hdr < 0 ? null : m.read(hdr, 8 + 6 * layout.partySize);
        if (block == null || NdsPkm.u32(block, 0) != 6 || uid == 0) {
            return null;
        }
        int i = partyIndex(block, uid);
        if (i >= 0) {
            return NdsPkm.u32(block, 4) <= 1 ? null : List.of(new Write(hdr, partyWithout(block, i)));
        }
        long slot = boxSlotOf(m, uid);
        return slot < 0 ? null : List.of(new Write(slot, NdsPkm.blank(NdsPkm.BOX_SIZE)));
    }

    /** Address of the PC slot holding {@code uid}, -1 if none (or the PC is unknown). */
    private long boxSlotOf(Mem m, long uid) {
        if (base < 0 || layout.boxes < 0) {
            return -1;
        }
        for (int box = 0; box < layout.boxCount; box++) {
            for (int slot = 0; slot < 30; slot++) {
                long a = base + layout.boxes + (long) box * layout.boxStride + (long) slot * NdsPkm.BOX_SIZE;
                byte[] head = m.read(a, 8);
                if (head != null && NdsPkm.u32(head, 0) == uid && head[4] == 0 && head[5] == 0) {
                    return a;
                }
            }
        }
        return -1;
    }

    @Override
    public Patch zeroHp(byte[] live, Mon mon) {
        if (live.length < layout.partySize || NdsPkm.u32(live, 0) != mon.uid() || NdsPkm.hp(live, 0) == 0) {
            return null;
        }
        return new Patch(0x8E, NdsPkm.zeroHpWord(live, 0));
    }

    @Override
    public String toString() {
        return "DS " + game + " (" + (base < 0 ? "sauvegarde non trouvée" : "sauvegarde @" + Long.toHexString(base)) + ")";
    }
}
