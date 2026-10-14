package slt.player.track;

/**
 * Pokémon data structure of the Nintendo DS games (generations 4 and 5).
 *
 * <pre>
 * 0x00 personality (u32)      0x04 flags (0 when valid)   0x06 checksum of the decrypted 0x08..0x87
 * 0x08..0x87 four 32-byte blocks, order given by the personality,
 *            encrypted by the LCG 0x41C64E6D/0x6073 seeded with the checksum
 * 0x88..     party part (status, level, HP…), encrypted with the personality as seed
 * Block A: species, item, OT id…   Block B: moves, IVs (egg flag), gen 4 locations (Pt/HGSS)
 * Block C: nickname (UTF-16, 11 chars)   Block D: OT name, dates, locations (DP, gen 5)
 * </pre>
 */
public final class NdsPkm {

    /**
     * A Pokémon is shiny when its shiny value is under this: 8 in the games, the room's threshold when its ROM
     * got another shiny rate (SltPatches), given by the room's directives.
     */
    static volatile int shinyThreshold = 8;

    public static void shinyRate(int rate) {
        shinyThreshold = rate <= 0 || rate >= 8192 ? 8 : Math.max(8, Math.min(255, Math.round(65536f / rate)));
    }

    public static final int GEN4_PARTY_SIZE = 236;
    public static final int GEN5_PARTY_SIZE = 220;
    public static final int BOX_SIZE = 136;

    /** Position of blocks A, B, C, D for each of the 24 orders. */
    private static final int[][] ORDER = new int[24][4];

    static {
        String[] orders = {"ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
            "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA"};
        for (int o = 0; o < 24; o++) {
            for (int pos = 0; pos < 4; pos++) {
                ORDER[o][orders[o].charAt(pos) - 'A'] = pos;
            }
        }
    }

    private static volatile java.util.Map<Integer, String> gen4Chars;

    private NdsPkm() {
    }

    /** Character table of generation 4 (shipped with the randomizer engine). */
    static java.util.Map<Integer, String> gen4Chars() {
        java.util.Map<Integer, String> t = gen4Chars;
        if (t != null) {
            return t;
        }
        t = new java.util.HashMap<>();
        try (java.io.InputStream in = NdsPkm.class.getResourceAsStream("/com/dabomstew/pkrandom/config/Generation4.tbl")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                    int eq = line.indexOf('=');
                    if (eq == 4) {
                        try {
                            t.put(Integer.parseInt(line.substring(0, 4), 16), line.substring(5));
                        } catch (NumberFormatException ignored) {
                            // malformed line
                        }
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // nicknames stay unknown
        }
        gen4Chars = t;
        return t;
    }

    static int u16(byte[] m, int off) {
        return (m[off] & 0xFF) | ((m[off + 1] & 0xFF) << 8);
    }

    static long u32(byte[] m, int off) {
        return (u16(m, off) | ((long) u16(m, off + 2) << 16)) & 0xFFFFFFFFL;
    }

    private static long next(long s) {
        return (s * 0x41C64E6DL + 0x6073L) & 0xFFFFFFFFL;
    }

    /** XOR stream of the games' LCG (seed = seed * 0x41C64E6D + 0x6073, key = seed >> 16). */
    static int[] decrypt(byte[] m, int off, int words, long seed) {
        int[] out = new int[words];
        long s = seed & 0xFFFFFFFFL;
        for (int i = 0; i < words; i++) {
            s = next(s);
            out[i] = u16(m, off + 2 * i) ^ (int) (s >>> 16);
        }
        return out;
    }

    /**
     * Cheap test run before the full decoding: the party part (level, HP)
     * must look sane. Rejects almost all random memory with a few multiplications.
     */
    public static boolean plausible(byte[] m, int off, int partySize) {
        if (off < 0 || off + partySize > m.length) {
            return false;
        }
        if (m[off + 4] != 0 || m[off + 5] != 0) {
            return false;
        }
        long pid = u32(m, off);
        if (pid == 0 && u16(m, off + 6) == 0) {
            return false;
        }
        int[] party = decrypt(m, off + 0x88, 5, pid);
        int level = party[2] & 0xFF;
        return level >= 1 && level <= 100 && party[4] > 0 && party[4] < 1000 && party[3] <= party[4];
    }

    /**
     * Decodes the party Pokémon at {@code off}, or returns null when the bytes
     * are not a valid Pokémon (checksum, species, level, HP).
     */
    public static Mon decode(byte[] m, int off, int partySize, int maxSpecies, long base) {
        if (!plausible(m, off, partySize)) {
            return null;
        }
        return decodeAny(m, off, partySize, maxSpecies, base);
    }

    /** Like {@link #decode} without the party part checks (eggs, structures being written). */
    public static Mon decodeAny(byte[] m, int off, int partySize, int maxSpecies, long base) {
        if (off < 0 || off + partySize > m.length) {
            return null;
        }
        long pid = u32(m, off);
        int checksum = u16(m, off + 6);
        int[] data = decrypt(m, off + 8, 64, checksum);
        int sum = 0;
        for (int w : data) {
            sum = (sum + w) & 0xFFFF;
        }
        if (sum != checksum) {
            return null;
        }
        int order = (int) (((pid & 0x3E000L) >>> 13) % 24);
        int a = ORDER[order][0] * 16;
        int b = ORDER[order][1] * 16;
        int c = ORDER[order][2] * 16;
        int d = ORDER[order][3] * 16;
        int species = data[a];
        if (species < 1 || species > maxSpecies) {
            return null;
        }
        int otId = data[a + 2];
        int sid = data[a + 3];
        boolean shiny = (otId ^ sid ^ (int) (pid >>> 16) ^ (int) (pid & 0xFFFF)) < shinyThreshold;
        boolean egg = (data[b + 9] & 0x4000) != 0;
        int met = data[d + 12];
        if (partySize == GEN4_PARTY_SIZE && data[b + 15] != 0) {
            met = data[b + 15]; // Platinum / HeartGold-SoulSilver location field
        }
        int[] party = decrypt(m, off + 0x88, 6, pid);
        int level = party[2] & 0xFF;
        int hp = party[3];
        int maxHp = party[4];
        StringBuilder nick = new StringBuilder();
        boolean gen4 = partySize == GEN4_PARTY_SIZE;
        for (int i = 0; i < 11; i++) {
            int ch = data[c + i]; // block C starts with the nickname (11 characters)
            if (ch == 0xFFFF || ch == 0) {
                break;
            }
            if (gen4) {
                // generation 4 has its own character codes; generation 5 uses UTF-16
                String s = gen4Chars().get(ch);
                if (s != null && !s.startsWith("\\")) {
                    nick.append(s);
                }
            } else {
                nick.append((char) ch);
            }
        }
        return new Mon(pid, species, level, hp, maxHp, nick.length() > 0 ? nick.toString() : null, otId, met, egg, shiny,
                base + off);
    }

    /**
     * Battle set of the Pokémon at {@code off} (see {@link slt.player.track.GameReader#battleSet}). Block A:
     * species, held item, happiness (+0x14), ability (+0x15), EVs (+0x18: HP, Attack, Defense, Speed, Special
     * Attack, Special Defense); block B: moves, IVs (+0x10, 5 bits each in the same order), +0x18 female (bit 1),
     * genderless (bit 2), form (bits 3-7), generation 5 nature (+0x19); generation 4 nature = personality % 25.
     */
    public static java.util.Map<String, Object> battleSet(byte[] m, int off, int partySize, Mon mon) {
        if (off < 0 || off + partySize > m.length || mon.egg()) {
            return null;
        }
        long pid = u32(m, off);
        int[] data = decrypt(m, off + 8, 64, u16(m, off + 6));
        int order = (int) (((pid & 0x3E000L) >>> 13) % 24);
        int a = ORDER[order][0] * 16;
        int b = ORDER[order][1] * 16;
        java.util.List<Object> moves = new java.util.ArrayList<>();
        java.util.List<Object> ppUps = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (data[b + i] != 0) {
                moves.add((long) data[b + i]);
                ppUps.add((long) Math.min(3, (data[b + 6 + i / 2] >>> (8 * (i & 1))) & 0xFF));
            }
        }
        long iv = (data[b + 8] & 0xFFFFL) | ((data[b + 9] & 0xFFFFL) << 16);
        boolean gen5 = partySize == GEN5_PARTY_SIZE;
        int flags = data[b + 12] & 0xFF;
        long nature = gen5 ? (data[b + 12] >>> 8) & 0xFF : pid % 25;
        return slt.Json.map("gen", gen5 ? 5L : 4L, "species", (long) mon.species(), "level", (long) mon.level(),
                "nickname", mon.nickname(), "moves", moves, "ppUps", ppUps, "shiny", mon.shiny(),
                "ivs", slt.Json.map("hp", iv & 31, "atk", (iv >>> 5) & 31, "def", (iv >>> 10) & 31, "spe", (iv >>> 15) & 31,
                        "spa", (iv >>> 20) & 31, "spd", (iv >>> 25) & 31),
                "evs", slt.Json.map("hp", (long) (data[a + 8] & 0xFF), "atk", (long) (data[a + 8] >>> 8), "def", (long) (data[a + 9] & 0xFF),
                        "spe", (long) (data[a + 9] >>> 8), "spa", (long) (data[a + 10] & 0xFF), "spd", (long) (data[a + 10] >>> 8)),
                "nature", nature, "ability", (long) (data[a + 6] >>> 8), "item", (long) data[a + 1],
                "happiness", (long) (data[a + 6] & 0xFF), "form", (long) (flags >> 3),
                "gender", (flags & 4) != 0 ? "N" : (flags & 2) != 0 ? "F" : "M");
    }

    /** Bytes (offset, value) that set the current HP of the party Pokémon at {@code off} to zero. */
    public static byte[] zeroHpWord(byte[] m, int off) {
        long pid = u32(m, off);
        long s = pid;
        for (int i = 0; i <= 3; i++) {
            s = next(s);
        }
        int key = (int) (s >>> 16);
        // HP is the 4th word of the party part (0x8E); 0 XOR key = key
        return new byte[]{(byte) key, (byte) (key >>> 8)};
    }

    /** Current HP of the party Pokémon at {@code off}. */
    public static int hp(byte[] m, int off) {
        return decrypt(m, off + 0x88, 4, u32(m, off))[3];
    }

    /**
     * An empty slot as the games store it: personality and checksum 0, then zeros encrypted
     * (seeds 0): the keystream itself.
     */
    public static byte[] blank(int size) {
        byte[] out = new byte[size];
        long s = 0;
        for (int i = 0; i < 64; i++) {
            s = next(s);
            int k = (int) (s >>> 16);
            out[8 + 2 * i] = (byte) k;
            out[9 + 2 * i] = (byte) (k >>> 8);
        }
        s = 0;
        for (int o = BOX_SIZE; o + 1 < size; o += 2) {
            s = next(s);
            int k = (int) (s >>> 16);
            out[o] = (byte) k;
            out[o + 1] = (byte) (k >>> 8);
        }
        return out;
    }

    /** A slot is empty when its personality, flags and checksum are all zero. */
    public static boolean isEmpty(byte[] slot) {
        for (int i = 0; i < 8; i++) {
            if (slot[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static volatile java.util.Map<String, Integer> gen4Codes;

    /** Character code of generation 4 for one character, or -1. */
    static int gen4Code(String ch) {
        java.util.Map<String, Integer> t = gen4Codes;
        if (t == null) {
            t = new java.util.HashMap<>();
            for (java.util.Map.Entry<Integer, String> e : gen4Chars().entrySet()) {
                if (!e.getValue().startsWith("\\")) {
                    t.putIfAbsent(e.getValue(), e.getKey());
                }
            }
            gen4Codes = t;
        }
        Integer c = t.get(ch);
        return c == null ? -1 : c;
    }

    /**
     * The 136 first bytes of the Pokémon at {@code off} (encrypted part included) with a new
     * nickname: decrypted, block C rewritten (11 characters, 0xFFFF after the name), the
     * "nicknamed" bit set, checksum recomputed, encrypted again. Null if the name cannot be
     * written in this game's characters.
     */
    public static byte[] withNickname(byte[] m, int off, String name, boolean gen4) {
        long pid = u32(m, off);
        int checksum = u16(m, off + 6);
        int[] data = decrypt(m, off + 8, 64, checksum);
        int order = (int) (((pid & 0x3E000L) >>> 13) % 24);
        int b = ORDER[order][1] * 16;
        int c = ORDER[order][2] * 16;
        int[] chars = new int[11];
        java.util.Arrays.fill(chars, 0xFFFF);
        int n = 0;
        for (int i = 0; i < name.length() && n < 10; ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            int code = gen4 ? gen4Code(new String(Character.toChars(cp))) : (cp <= 0xFFFF ? cp : -1);
            if (code < 0) {
                return null;
            }
            chars[n++] = code;
        }
        System.arraycopy(chars, 0, data, c, 11);
        data[b + 9] |= 0x8000; // nicknamed
        int sum = 0;
        for (int w : data) {
            sum = (sum + w) & 0xFFFF;
        }
        byte[] out = java.util.Arrays.copyOfRange(m, off, off + BOX_SIZE);
        out[6] = (byte) sum;
        out[7] = (byte) (sum >>> 8);
        long s = sum;
        for (int i = 0; i < 64; i++) {
            s = next(s);
            int v = data[i] ^ (int) (s >>> 16);
            out[8 + 2 * i] = (byte) v;
            out[9 + 2 * i] = (byte) (v >>> 8);
        }
        return out;
    }
}
