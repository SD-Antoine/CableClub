package slt.player.track;

import slt.Json;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The writes of the DS adapter (point 7c of the follow-up of the audit of 2026-11-10), on save buffers built here:
 * Platinum (generation 4) and White (generation 5). DsSaveTest checks how the save is found; this test reads the
 * party, the player and the bag, then applies every write the referee can ask for and reads again. The Pokémon are
 * encrypted by the test as the games store them. No ROM, save or emulator state.
 */
public final class NdsReaderTest {

    private static final long RAM = 0x02000000L;
    private static final int BASE = 0x200000;
    private static final int TID = 0x1234;
    private static final int SID = 0x5678;

    private static int failed;

    /** Where things are in the save buffer of each family (the adapter's layouts). */
    private record Game(String id, int gen, NdsReader.Layout layout, int name, int[][] pockets, int ballPocket,
                        int medicinePocket) {
    }

    private static final Game PLATINUM = new Game("platinum", 4, NdsReader.PT, 0x78 - 0x10,
            new int[][]{{0x630, 165}, {0x8C4, 50}, {0x98C, 100}, {0xB1C, 12}, {0xB4C, 40}, {0xBEC, 64}, {0xCEC, 15}, {0xD28, 30}}, 6, 4);
    private static final Game WHITE = new Game("white", 5, NdsReader.BW, 0x19404,
            new int[][]{{0x18400, 310}, {0x188D8, 83}, {0x18A24, 109}, {0x18BD8, 48}, {0x18C98, 64}}, 0, 3);

    public static void main(String[] args) {
        run(PLATINUM, "Platine");
        run(WHITE, "Blanc");
        System.out.println(failed == 0 ? "ok     adaptateur DS (Platine, Blanc : lecture et écritures)"
                : "ÉCHEC  adaptateur DS : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Game g, String n) {
        NdsReader.Layout l = g.layout();
        boolean gen5 = g.gen() == 5;
        int size = l.partySize();
        byte[] ram = new byte[4 << 20];
        int b = BASE;
        // the player: name, trainer and secret IDs, money, badges, map, battle style "Shift", a box name (generation 5)
        name(ram, b + g.name(), "Lucas", gen5);
        put16(ram, b + l.trainerId(), TID);
        put16(ram, b + l.trainerId() + 2, SID);
        put32(ram, b + l.money(), 4321);
        ram[b + l.badges()] = 0b0000_1011;
        put16(ram, b + l.map(), 300);
        ram[b + l.trainerId() - 0x14] = 0x01; // text speed "middle", battle style "Shift"
        if (gen5) {
            name(ram, b + l.boxNames(), "BOITE 1", true);
        }
        // the bag: 6 Poké Balls then a Great Ball, 2 Potions
        slot(ram, b + g.pockets()[g.ballPocket()][0], 4, 6);
        slot(ram, b + g.pockets()[g.ballPocket()][0] + 4, 3, 1);
        slot(ram, b + g.pockets()[g.medicinePocket()][0] + (g.ballPocket() == g.medicinePocket() ? 8 : 0), 17, 2);
        // the party
        int hdr = b + l.party();
        put32(ram, hdr, 6);
        put32(ram, hdr + 4, 3);
        long[] pids = {0x0100_0001L, 0x0200_0002L, 0x0300_0003L};
        System.arraycopy(pkm(pids[0], 393, 15, 40, 44, 0, new int[]{33, 45, 55, 0}, gen5), 0, ram, hdr + 8, size);
        System.arraycopy(pkm(pids[1], 396, 9, 0, 28, 155, new int[]{33, 0, 0, 0}, gen5), 0, ram, hdr + 8 + size, size);
        System.arraycopy(pkm(pids[2], 399, 7, 20, 20, 0, new int[]{33, 111, 0, 0}, gen5), 0, ram, hdr + 8 + 2 * size, size);
        Mem m = new Mem().add(RAM, ram);
        NdsReader r = NdsReader.forGame(g.id(), GameInfo.bare(g.id(), g.gen()));

        Snapshot s = r.read(m);
        check(s != null && s.party().size() == 3, n + " : équipe de 3");
        if (s == null || s.party().size() != 3) {
            return;
        }
        Mon first = s.party().get(0);
        Mon second = s.party().get(1);
        Mon third = s.party().get(2);
        check(first.species() == 393 && first.level() == 15 && first.hp() == 40 && first.maxHp() == 44, n + " : espèce, niveau, PV");
        check(first.otId() == TID && second.fainted() && s.playerOt() == TID, n + " : dresseur, K.O. lu");
        check(s.badges() == 3 && s.money() == 4321 && s.map() == 300, n + " : badges, argent, lieu (" + s.badges() + ", " + s.money() + ", " + s.map() + ")");
        check(s.bag() != null && s.bag().get(4) == 6 && s.bag().get(17) == 2 && s.balls() == 1, n + " : sac (" + s.bag() + ")");
        check(r.stored(m, pids[0]) == Boolean.FALSE, n + " : PC vide");

        // the party structure
        patch(m, first, r.zeroHp(m.read(first.address(), size), first));
        check(r.read(m).party().get(0).fainted() && r.read(m).party().get(0).maxHp() == 44, n + " : K.O. écrit, PV max gardés");
        check(r.zeroHp(m.read(first.address(), size), first) == null, n + " : déjà K.O., rien à écrire");
        if (gen5 || NdsPkm.gen4Code("F") >= 0) {
            apply(m, r.nickname(m, first, "Flamme"));
            check("Flamme".equals(r.read(m).party().get(0).nickname()), n + " : surnom écrit (" + r.read(m).party().get(0).nickname() + ")");
            check(r.nickname(m, first, "Flamme").isEmpty(), n + " : surnom déjà là");
        } else {
            // the character table of generation 4 comes with the randomizer (its jar is not on the classpath here)
            check(r.nickname(m, first, "Flamme") == null, n + " : sans table de caractères, aucun surnom écrit");
        }
        check(r.heldItem(m, second) == 155 && r.heldItem(m, first) == 0, n + " : objet tenu");
        apply(m, r.setHeldItem(m, second, 0));
        check(r.heldItem(m, second) == 0 && r.read(m).party().size() == 3, n + " : objet tenu retiré, structure valide");
        apply(m, r.forgetMove(m, first, 45));
        Map<String, Object> set = r.battleSet(m, r.read(m).party().get(0));
        check(set != null && Json.arr(set.get("moves")).equals(List.of(33L, 55L)), n + " : capacité oubliée (" + (set == null ? null : set.get("moves")) + ")");
        check(set != null && Json.arr(set.get("ppUps")).equals(List.of(1L, 0L)), n + " : les PP Plus remontent avec la capacité ("
                + (set == null ? null : set.get("ppUps")) + ")");
        check(r.forgetMove(m, third, 999) == null, n + " : capacité inconnue, rien à écrire");

        // the bag
        apply(m, r.addItem(m, 17, 3));
        apply(m, r.addItem(m, 26, 1));
        Map<Integer, Integer> bag = r.read(m).bag();
        check(bag.get(17) == 5 && bag.get(26) == 1, n + " : objets rendus (" + bag + ")");
        apply(m, r.removeItem(m, 4));
        bag = r.read(m).bag();
        check(!bag.containsKey(4) && bag.get(3) == 1 && bag.get(17) == 5, n + " : Poké Balls retirées");
        check(NdsPkm.u16(ram, b + g.pockets()[g.ballPocket()][0]) == 3 && r.read(m).balls() == 1, n + " : la Super Ball remonte");
        check(r.removeItem(m, 4) == null, n + " : rien à retirer");
        apply(m, r.setBattleStyle(m));
        check((ram[b + l.trainerId() - 0x14] & 0x40) != 0 && r.setBattleStyle(m).isEmpty(), n + " : style de combat « Défini »");

        // to the PC
        apply(m, r.deposit(m, second));
        s = r.read(m);
        check(s.party().size() == 2 && s.party().stream().noneMatch(x -> x.uid() == second.uid()), n + " : dépôt, l'équipe se resserre");
        check(r.stored(m, second.uid()) == Boolean.TRUE, n + " : dans le PC");
        int last = b + l.boxes() + (l.boxCount() - 1) * l.boxStride();
        check(NdsPkm.u32(ram, last) == second.uid(), n + " : dans la dernière boîte");
        int boxName = b + l.boxNames() + (l.boxCount() - 1) * 0x28;
        int mCode = gen5 ? 'M' : NdsPkm.gen4Code("M");
        check(mCode < 0 || NdsPkm.u16(ram, boxName) == mCode, n + " : la dernière boîte s'appelle MORTS");
        apply(m, r.store(m, s.party().get(1)));
        check(NdsPkm.u32(ram, b + l.boxes()) == third.uid() && r.read(m).party().size() == 1, n + " : rangé vivant dans la première boîte");
        apply(m, r.release(m, third.uid()));
        check(r.stored(m, third.uid()) == Boolean.FALSE && r.stored(m, second.uid()) == Boolean.TRUE, n + " : relâché depuis le PC");
        check(r.release(m, first.uid()) == null && r.deposit(m, first) == null, n + " : jamais le dernier Pokémon");
    }

    // -------------------------------------------------------------- building

    /**
     * A party Pokémon as the DS games store it: blocks A (species, held item, IDs), B (moves, PP, PP Ups), C
     * (nickname, empty), D, shuffled by the personality and encrypted with the checksum; then the battle values
     * (level, HP) encrypted with the personality.
     */
    private static byte[] pkm(long pid, int species, int level, int hp, int maxHp, int item, int[] moves, boolean gen5) {
        int size = gen5 ? NdsPkm.GEN5_PARTY_SIZE : NdsPkm.GEN4_PARTY_SIZE;
        int[] blocks = new int[64];
        blocks[0] = species;
        blocks[1] = item;
        blocks[2] = TID;
        blocks[3] = SID;
        blocks[4] = 1000;
        blocks[6] = 70;
        for (int i = 0; i < 4; i++) {
            blocks[16 + i] = moves[i];
        }
        blocks[16 + 4] = (moves[0] == 0 ? 0 : 35) | ((moves[1] == 0 ? 0 : 30) << 8);
        blocks[16 + 5] = (moves[2] == 0 ? 0 : 25) | ((moves[3] == 0 ? 0 : 20) << 8);
        blocks[16 + 6] = 1 | (2 << 8); // PP Ups of the first two moves
        blocks[16 + 9] = 0x0100; // IVs, not an egg
        blocks[32] = 0xFFFF; // empty nickname
        blocks[48 + 12] = 10; // met location
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
        byte[] out = new byte[size];
        put32(out, 0, pid);
        put16(out, 6, sum);
        encrypt(out, 8, data, sum);
        int[] party = new int[(size - NdsPkm.BOX_SIZE) / 2];
        party[2] = level;
        party[3] = hp;
        party[4] = maxHp;
        encrypt(out, NdsPkm.BOX_SIZE, party, pid);
        return out;
    }

    private static void encrypt(byte[] m, int off, int[] words, long seed) {
        long s = seed & 0xFFFFFFFFL;
        for (int i = 0; i < words.length; i++) {
            s = (s * 0x41C64E6DL + 0x6073L) & 0xFFFFFFFFL;
            put16(m, off + 2 * i, words[i] ^ (int) (s >>> 16));
        }
    }

    /** A name ended by 0xFFFF: generation 5 in UTF-16, generation 4 here with the same codes (enough to be read as one). */
    private static void name(byte[] ram, int off, String name, boolean gen5) {
        byte[] c = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < c.length; i++) {
            put16(ram, off + 2 * i, c[i]);
        }
        put16(ram, off + 2 * c.length, 0xFFFF);
    }

    private static void slot(byte[] ram, int off, int item, int qty) {
        put16(ram, off, item);
        put16(ram, off + 2, qty);
    }

    private static void put16(byte[] m, int off, int v) {
        m[off] = (byte) v;
        m[off + 1] = (byte) (v >>> 8);
    }

    private static void put32(byte[] m, int off, long v) {
        put16(m, off, (int) v);
        put16(m, off + 2, (int) (v >>> 16));
    }

    private static void apply(Mem m, List<GameReader.Write> writes) {
        if (writes == null) {
            check(false, "écriture attendue, aucune proposée");
            return;
        }
        for (GameReader.Write wr : writes) {
            Mem.Block blk = m.block(wr.address(), wr.bytes().length);
            if (blk == null) {
                check(false, "écriture hors de la mémoire : " + Long.toHexString(wr.address()));
                continue;
            }
            System.arraycopy(wr.bytes(), 0, blk.data, (int) (wr.address() - blk.start), wr.bytes().length);
        }
    }

    private static void patch(Mem m, Mon mon, GameReader.Patch patch) {
        if (patch == null) {
            check(false, "changement attendu, aucun proposé");
            return;
        }
        apply(m, List.of(new GameReader.Write(mon.address() + patch.offset(), patch.bytes())));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
