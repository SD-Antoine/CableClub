package slt.player;

import slt.player.track.GameInfo;
import slt.player.track.Mem;
import slt.player.track.Mon;
import slt.player.track.NdsPkm;
import slt.player.track.NdsReader;

import java.util.Map;
import java.util.Random;

/**
 * The shiny rate of the DS games written in RAM (point 8b): a wild Pokémon given another personality must read back the
 * same (species, level, HP, trainer, nature, gender, ability, moves, IVs), only shiny. Runs on made-up Pokémon
 * structures, built here: no data of a game.
 */
public final class DsShinyTest {

    private static int failed;

    public static void main(String[] args) {
        Random rng = new Random(8);
        int done = 0;
        for (int i = 0; i < 2000; i++) {
            boolean gen5 = i % 2 == 1;
            int size = gen5 ? NdsPkm.GEN5_PARTY_SIZE : NdsPkm.GEN4_PARTY_SIZE;
            long pid = rng.nextLong() & 0xFFFFFFFFL;
            int tid = rng.nextInt(65536);
            int sid = rng.nextInt(65536);
            long tsv = tid ^ sid;
            if (((pid >>> 16) ^ (pid & 0xFFFF) ^ tsv) < 8) {
                continue;
            }
            byte[] m = pokemon(pid, tid, sid, 1 + rng.nextInt(400), 2 + rng.nextInt(90), gen5, rng);
            Mon before = NdsPkm.decode(m, 0, size, 649, 0);
            Map<String, Object> setBefore = NdsPkm.battleSet(m, 0, size, before);
            boolean wurmple = i % 7 == 0; // its evolution comes from the personality too
            long np = ShinyBoost.dsShinyPid(pid, tsv, gen5, wurmple, rng);
            if (np < 0) {
                check(wurmple, "un PID chromatique est trouvé (sauf rares Chenipotte)");
                continue;
            }
            byte[] out = NdsPkm.withPersonality(m, 0, size, np);
            Mon after = out == null ? null : NdsPkm.decode(out, 0, size, 649, 0);
            if (after == null) {
                check(false, "relu après le changement (" + (gen5 ? "5e" : "4e") + " gén.)");
                continue;
            }
            check(after.shiny() && !before.shiny(), "chromatique au seuil du jeu (8)");
            check(after.uid() == np && after.species() == before.species() && after.level() == before.level()
                    && after.hp() == before.hp() && after.maxHp() == before.maxHp() && after.otId() == before.otId()
                    && after.met() == before.met(), "espèce, niveau, PV, dresseur et lieu gardés");
            Map<String, Object> setAfter = NdsPkm.battleSet(out, 0, size, after);
            setBefore.put("shiny", true);
            check(setBefore.equals(setAfter), "nature, sexe, talent, attaques, IV gardés : " + setBefore + " / " + setAfter);
            check((np & 0xFF) == (pid & 0xFF), "octet bas gardé (sexe)");
            check(gen5 ? ((np >>> 16) & 1) == ((pid >>> 16) & 1) : np % 25 == pid % 25,
                    gen5 ? "bit 16 gardé (talent)" : "nature gardée (PID % 25)");
            check(!wurmple || ((np >>> 16) % 10 < 5) == ((pid >>> 16) % 10 < 5), "évolution de Chenipotte gardée");
            check(NdsPkm.trainer(out, 0) == NdsPkm.trainer(m, 0), "dresseur et espèce lus pareil");
            // the box part alone (the first 136 bytes), as some copies are
            byte[] box = NdsPkm.withPersonality(m, 0, NdsPkm.BOX_SIZE, np);
            check(box != null && java.util.Arrays.equals(box, java.util.Arrays.copyOf(out, NdsPkm.BOX_SIZE)),
                    "la partie boîte seule est la même");
            done++;
        }
        // a structure that does not pass its checksum is left alone
        byte[] m = pokemon(0x12345678L, 1, 2, 25, 10, false, rng);
        m[40] ^= 1;
        check(NdsPkm.withPersonality(m, 0, NdsPkm.GEN4_PARTY_SIZE, 0x1234L) == null, "somme de contrôle fausse : rien");
        memory(rng);
        System.out.println(failed == 0 ? "ok     chromatiques DS en RAM (" + done + " Pokémon)"
                : "ÉCHEC  chromatiques DS en RAM : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * A made-up RAM of Black / White, with what the money looks like at the offset of Black 2 / White 2: the save is
     * found; the wild Pokémon becomes shiny, every copy of it; the party, the PC and a Pokémon of the PC looked at or
     * held in its screen do not change.
     */
    private static void memory(Random rng) {
        int tid = 0x1234;
        int sid = 0x5678;
        int size = NdsPkm.GEN5_PARTY_SIZE;
        byte[] ram = new byte[4 << 20];
        int base = 0x200000;
        put16(ram, base + 0x18E00, 6);
        put16(ram, base + 0x18E04, 1);
        put16(ram, base + 4, 'B');
        put16(ram, base + 6, 0xFFFF);
        byte[] mine = pokemon(0x11112222L, tid, sid, 25, 10, true, rng);
        System.arraycopy(mine, 0, ram, base + 0x18E08, size);
        put16(ram, base + 0x19404, 'A');
        put16(ram, base + 0x19406, 0xFFFF);
        put16(ram, base + 0x19414, tid);
        put16(ram, base + 0x19416, sid);
        java.util.Arrays.fill(ram, base + 0x21100, base + 0x21104, (byte) 0xFF);
        put16(ram, base + 0x21200, 4000);
        // the PC: one Pokémon whose summary screen is open (party copy outside the save), another one held in its screen
        byte[] boxed = pokemon(0x33334444L, tid, sid, 16, 12, true, rng);
        byte[] held = pokemon(0x77778888L, tid, sid, 19, 13, true, rng);
        System.arraycopy(boxed, 0, ram, base + 0x400, NdsPkm.BOX_SIZE);
        System.arraycopy(held, 0, ram, base + 0x400 + NdsPkm.BOX_SIZE, NdsPkm.BOX_SIZE);
        byte[] wild = pokemon(0x55556666L, tid, sid, 509, 4, true, rng);
        int[] at = {0x240000, 0x243000};
        System.arraycopy(wild, 0, ram, at[0], size);
        System.arraycopy(wild, 0, ram, at[1], size);
        int look = 0x250000;
        System.arraycopy(boxed, 0, ram, look, size);
        // at the day care: in the save, not in a box, with a party copy elsewhere
        byte[] daycare = pokemon(0x99990000L, tid, sid, 41, 15, true, rng);
        System.arraycopy(daycare, 0, ram, base + 0x20000, NdsPkm.BOX_SIZE);
        System.arraycopy(daycare, 0, ram, 0x270000, size);

        NdsReader reader = NdsReader.forGame("white", GameInfo.bare("white", 5));
        Mem mem = new Mem().add(0x02000000L, ram);
        check(reader.read(mem) != null && reader.mapAddress() == 0x02000000L + base + 0x19504, "Noire/Blanche : sauvegarde trouvée");
        byte[] before = ram.clone();
        ShinyBoost boost = ShinyBoost.create(reader, null);
        boost.setRate(1);
        ShinyBoost.Live live = new ShinyBoost.Live() {
            @Override
            public byte[] read(long address, int length) {
                return java.util.Arrays.copyOfRange(ram, (int) (address - 0x02000000L), (int) (address - 0x02000000L) + length);
            }

            @Override
            public boolean write(long address, byte[] bytes) {
                System.arraycopy(bytes, 0, ram, (int) (address - 0x02000000L), bytes.length);
                return true;
            }
        };
        reader.fieldShown(true);
        boost.tick(live);
        check(java.util.Arrays.equals(ram, before), "terrain affiché : rien n'est écrit");
        reader.fieldShown(false);
        boost.tick(live);
        // taken out of its box once the field is away: a party copy that looks wild
        java.util.Arrays.fill(ram, base + 0x400 + NdsPkm.BOX_SIZE, base + 0x400 + 2 * NdsPkm.BOX_SIZE, (byte) 0);
        System.arraycopy(held, 0, ram, 0x260000, size);
        boost.tick(live);
        Mon one = NdsPkm.decode(ram, at[0], size, 649, 0);
        Mon two = NdsPkm.decode(ram, at[1], size, 649, 0);
        check(one != null && one.shiny() && two != null && two.uid() == one.uid() && one.species() == 509, "sauvage : toutes ses copies, chromatique");
        check(NdsPkm.u32At(ram, base + 0x18E08) == 0x11112222L, "l'équipe ne change pas");
        check(NdsPkm.u32At(ram, base + 0x400) == 0x33334444L && NdsPkm.u32At(ram, look) == 0x33334444L, "un Pokémon du PC regardé ne change pas");
        check(NdsPkm.u32At(ram, 0x260000) == 0x77778888L, "un Pokémon du PC tenu ne change pas");
        check(NdsPkm.u32At(ram, base + 0x20000) == 0x99990000L && NdsPkm.u32At(ram, 0x270000) == 0x99990000L, "un Pokémon de la pension ne change pas");
        check(NdsReader.forGame("white2", GameInfo.bare("white2", 5)).gen5(), "Noire 2/Blanche 2 : toujours la 5e génération");
    }

    /** A made-up party Pokémon, encrypted as the DS games store it. */
    static byte[] pokemon(long pid, int tid, int sid, int species, int level, boolean gen5, Random rng) {
        int size = gen5 ? NdsPkm.GEN5_PARTY_SIZE : NdsPkm.GEN4_PARTY_SIZE;
        int[] blocks = new int[64]; // A, B, C, D in this order, 16 words each
        blocks[0] = species;
        blocks[2] = tid;
        blocks[3] = sid;
        blocks[4] = rng.nextInt(65536);                          // experience
        blocks[6] = 70 | (rng.nextInt(120) << 8);                // happiness, ability
        blocks[8] = rng.nextInt(65536);                          // EVs
        for (int i = 0; i < 4; i++) {
            blocks[16 + i] = 1 + rng.nextInt(500);              // moves
        }
        blocks[16 + 4] = 0x2323;                                 // PP
        blocks[16 + 5] = 0x2323;
        blocks[16 + 8] = rng.nextInt(65536);                     // IVs
        blocks[16 + 9] = rng.nextInt(0x4000);                    // IVs, not an egg
        blocks[16 + 12] = (rng.nextBoolean() ? 2 : 0) | (gen5 ? rng.nextInt(25) << 8 : 0); // female, gen 5 nature
        blocks[32] = 0xFFFF;                                     // empty nickname
        blocks[48 + 12] = 1 + rng.nextInt(100);                  // met location
        int order = (int) (((pid & 0x3E000L) >>> 13) % 24);
        String[] orders = {"ABCD", "ABDC", "ACBD", "ACDB", "ADBC", "ADCB", "BACD", "BADC", "BCAD", "BCDA", "BDAC", "BDCA",
            "CABD", "CADB", "CBAD", "CBDA", "CDAB", "CDBA", "DABC", "DACB", "DBAC", "DBCA", "DCAB", "DCBA"};
        int[] data = new int[64];
        for (int pos = 0; pos < 4; pos++) {
            System.arraycopy(blocks, (orders[order].charAt(pos) - 'A') * 16, data, pos * 16, 16);
        }
        int sum = 0;
        for (int w : data) {
            sum = (sum + w) & 0xFFFF;
        }
        byte[] m = new byte[size];
        put16(m, 0, (int) pid);
        put16(m, 2, (int) (pid >>> 16));
        put16(m, 6, sum);
        encrypt(m, 8, data, sum);
        int[] party = new int[(size - NdsPkm.BOX_SIZE) / 2];
        int hp = 10 + rng.nextInt(200);
        party[2] = level;
        party[3] = hp - rng.nextInt(hp);
        party[4] = hp;
        encrypt(m, NdsPkm.BOX_SIZE, party, pid);
        return m;
    }

    private static void encrypt(byte[] m, int off, int[] words, long seed) {
        long s = seed & 0xFFFFFFFFL;
        for (int i = 0; i < words.length; i++) {
            s = (s * 0x41C64E6DL + 0x6073L) & 0xFFFFFFFFL;
            put16(m, off + 2 * i, words[i] ^ (int) (s >>> 16));
        }
    }

    private static void put16(byte[] m, int off, int v) {
        m[off] = (byte) v;
        m[off + 1] = (byte) (v >>> 8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            if (failed <= 10) {
                System.out.println("  " + what);
            }
        }
    }
}
