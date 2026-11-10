package slt.player.track;

import slt.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The generation 3 adapter (point 7b of the follow-up of the audit of 2026-11-10) on memories built here: Emerald
 * (save blocks found by their pointers), FireRed (enemy party before the player's, its own offsets) and Ruby (fixed
 * addresses, no encryption key). The Pokémon are encrypted with their checksum by an encoder of the test; reading,
 * and every write the referee can ask for, applied and read again. No ROM, save or emulator state: the species and
 * character tables are made up (the real ones are read in the player's ROM).
 */
public final class Gen3ReaderTest {

    private static final long EWRAM = 0x02000000L;
    private static final long IWRAM = 0x03000000L;
    private static final String[] ORDERS = {"GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA", "AGEM", "AGME", "AEGM", "AEMG",
            "AMGE", "AMEG", "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG", "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG"};
    private static final int LETTER_A = 0xA0;
    private static final long TID = 0x1234_5678L; // secret ID 0x1234, trainer ID 0x5678
    private static final long KEY = 0x0BAD_F00DL;

    private static int failed;

    /** One game's addresses and offsets, as the adapter expects them. */
    private record Game(String id, boolean iwramParty, long party, int countDelta, boolean enemyBefore, long sb1, long sb2,
                        long storage, long pointers, int flags, int firstBadge, int clear, int money, int keyAt,
                        int items, int balls, long main, int battle) {
    }

    private static final Game EMERALD = new Game("emerald", false, 0x020244ECL, -3, false, 0x02026000L, 0x02024D00L,
            0x0202A000L, 0x03005D8CL, 0x1270, 0x867, 0x864, 0x490, 0xAC, 0x560, 0x650, 0x030022C0L, 0x439);
    private static final Game FIRERED = new Game("firered", false, 0x02024284L, -0x25B, true, 0x02026000L, 0x02024D00L,
            0x0202A000L, 0x03005008L, 0x0EE0, 0x820, 0x82C, 0x290, 0xF20, 0x310, 0x430, 0x030030F0L, 0x439);
    private static final Game RUBY = new Game("ruby", true, 0x03004360L, -0x10, false, 0x02025734L, 0x02024EA4L,
            0x020300A0L, -1, 0x1220, 0x807, 0x804, 0x490, -1, 0x560, 0x600, 0x03001770L, 0x43D);

    public static void main(String[] args) throws Exception {
        emerald();
        other(FIRERED);
        other(RUBY);
        System.out.println(failed == 0 ? "ok     adaptateur 3e génération (Émeraude, Rouge Feu, Rubis : lecture et écritures)"
                : "ÉCHEC  adaptateur 3e génération : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------- Emerald

    private static void emerald() throws Exception {
        Game g = EMERALD;
        byte[] ew = new byte[0x40000];
        byte[] iw = new byte[0x8000];
        world(g, ew, iw, 0b0001_1111, true);
        long pidShiny = shinyPid();
        party(g, ew, iw, new byte[][]{
                mon(0x0000_0007L, 280, 12, 33, 35, "TARSAL", 13, 0, new int[]{33, 45, 0, 0}, false),
                mon(0x0001_0019L, 263, 6, 0, 22, "ZIG", 13, 0, new int[]{33, 0, 0, 0}, false),
                mon(pidShiny, 265, 4, 15, 15, "CHENI", 13, 0, new int[]{33, 81, 0, 0}, false),
                mon(0x0002_0003L, 280, 5, 0, 0, "OEUF", 0, 0, new int[]{0, 0, 0, 0}, true)});
        Mem m = mem(g, ew, iw);
        Gen3Reader r = (Gen3Reader) Gen3Reader.forGame(g.id(), info());
        Snapshot s = twoReadings(g, r, m, iw);
        check(s != null && s.party().size() == 4, "Émeraude : équipe de 4 (" + (s == null ? "rien" : s.party().size()) + ")");
        if (s == null || s.party().size() != 4) {
            return;
        }
        Mon tarsal = s.party().get(0);
        Mon zig = s.party().get(1);
        Mon cheni = s.party().get(2);
        check(tarsal.species() == 280 && tarsal.level() == 12 && tarsal.hp() == 33 && "TARSAL".equals(tarsal.nickname()),
                "Émeraude : Pokémon déchiffré");
        check(tarsal.otId() == 0x5678 && tarsal.met() == 13 && tarsal.uid() == 7, "Émeraude : dresseur, lieu de rencontre, identité");
        check(cheni.shiny() && !tarsal.shiny(), "Émeraude : chromatique");
        check(s.party().get(3).egg(), "Émeraude : œuf");
        check(zig.fainted(), "Émeraude : K.O. lu");
        check(s.playerOt() == 0x5678 && s.badges() == 5 && s.map() == 0x0103 && s.cleared() == 1, "Émeraude : dresseur, badges, lieu, partie finie");
        check(s.money() == 3210, "Émeraude : argent déchiffré (" + s.money() + ")");
        check(s.bag() != null && s.bag().get(4) == 7 && s.bag().get(13) == 2 && s.balls() == 1, "Émeraude : sac déchiffré");
        check(s.battle() == 0 && s.wild().isEmpty(), "Émeraude : pas de combat");
        check(r.stored(m, 7) == Boolean.FALSE, "Émeraude : PC vide");

        // a broken checksum ends the party there
        int t = (int) (g.party() + 100 - EWRAM);
        ew[t + 0x1C] ^= 1;
        Gen3Reader fresh = (Gen3Reader) Gen3Reader.forGame(g.id(), info());
        check(fresh.read(m).party().size() == 1, "Émeraude : une structure abîmée n'est pas lue");
        ew[t + 0x1C] ^= 1;

        // writes
        patch(m, tarsal, r.zeroHp(m.read(tarsal.address(), 100), tarsal));
        check(r.read(m).party().get(0).fainted(), "Émeraude : K.O. écrit");
        apply(m, r.nickname(m, tarsal, "LAME"));
        check("LAME".equals(r.read(m).party().get(0).nickname()) && r.nickname(m, tarsal, "LAME").isEmpty(), "Émeraude : surnom écrit");
        check(r.heldItem(m, zig) == 0, "Émeraude : rien en main");
        apply(m, r.setHeldItem(m, zig, 139));
        check(r.heldItem(m, zig) == 139 && r.read(m).party().size() == 4, "Émeraude : objet tenu écrit, somme de contrôle juste");
        apply(m, r.forgetMove(m, cheni, 33));
        Map<String, Object> set = r.battleSet(m, r.read(m).party().get(2));
        check(set != null && Json.arr(set.get("moves")).equals(List.of(81L)), "Émeraude : capacité oubliée (" + (set == null ? null : set.get("moves")) + ")");
        long[] cw = words(m.read(cheni.address(), 100));
        String co = ORDERS[(int) (cheni.uid() % 24)];
        int ca = co.indexOf('A') * 3;
        int cg = co.indexOf('G') * 3;
        check((cw[ca + 2] & 0xFF) == 30 && ((cw[ca + 2] >>> 8) & 0xFF) == 0 && (cw[cg + 2] & 0xFF) == 0b10,
                "Émeraude : PP et PP Plus remontent avec la capacité");
        check(r.forgetMove(m, r.read(m).party().get(2), 81) == null, "Émeraude : jamais la dernière capacité");
        apply(m, r.addItem(m, 13, 3));
        apply(m, r.addItem(m, 20, 1));
        apply(m, r.addItem(m, 2, 5));
        Map<Integer, Integer> bag = r.read(m).bag();
        check(bag.get(13) == 5 && bag.get(20) == 1 && bag.get(2) == 5, "Émeraude : objets rendus (" + bag + ")");
        apply(m, r.removeItem(m, 4));
        bag = r.read(m).bag();
        check(!bag.containsKey(4) && bag.get(2) == 5, "Émeraude : Poké Balls retirées, la suivante remonte");
        check(r.read(m).balls() == 1 && r.removeItem(m, 4) == null, "Émeraude : rien à retirer");
        apply(m, r.setBattleStyle(m));
        check((ew[(int) (g.sb2() - EWRAM) + 0x15] & 2) != 0 && r.setBattleStyle(m).isEmpty(), "Émeraude : style de combat « Défini »");

        // to the PC
        Mon zigNow = r.read(m).party().get(1);
        apply(m, r.deposit(m, zigNow));
        s = r.read(m);
        check(s.party().size() == 3 && s.party().stream().noneMatch(x -> x.uid() == zig.uid()), "Émeraude : dépôt, l'équipe se resserre");
        check(r.stored(m, zig.uid()) == Boolean.TRUE && (ew[(int) (g.party() + g.countDelta() - EWRAM)] & 0xFF) == 3, "Émeraude : dans le PC, compteur à jour");
        int name = (int) (g.storage() - EWRAM) + 4 + 14 * 30 * 80 + 13 * 9;
        check((ew[name] & 0xFF) == LETTER_A + 'M' - 'A', "Émeraude : la boîte s'appelle MORTS");
        int deadSlot = (int) (g.storage() - EWRAM) + 4 + 13 * 30 * 80;
        check(Gen3Reader.u32(ew, deadSlot) == zig.uid(), "Émeraude : dans la dernière boîte");
        apply(m, r.store(m, s.party().get(1)));
        check(Gen3Reader.u32(ew, (int) (g.storage() - EWRAM) + 4) == cheni.uid(), "Émeraude : rangé vivant dans la première boîte");
        apply(m, r.release(m, cheni.uid()));
        check(r.stored(m, cheni.uid()) == Boolean.FALSE, "Émeraude : relâché depuis le PC");
        check(r.release(m, 0x0202_0202L) == null, "Émeraude : rien à relâcher");

        // in battle: nothing is written in the party
        int battleByte = (int) (g.main() - IWRAM) + g.battle();
        iw[battleByte] |= 2;
        tick(g, iw);
        s = r.read(m);
        check(r.inBattleFlag() == 1, "Émeraude : combat vu dans gMain");
        check(r.deposit(m, s.party().get(0)) == null && r.setHeldItem(m, s.party().get(0), 1) == null
                && r.release(m, s.party().get(0).uid()) == null, "Émeraude : aucune écriture en combat");
        // a wild one: the first of the enemy party, with the player's ID
        int enemy = (int) (g.party() + 600 - EWRAM);
        System.arraycopy(mon(0x0BAD_0001L, 261, 3, 11, 11, "", 16, 0, new int[]{33, 0, 0, 0}, false), 0, ew, enemy, 100);
        s = r.read(m);
        check(s.battle() == 1 && s.wild().size() == 1 && s.wild().get(0).species() == 261, "Émeraude : Pokémon sauvage en combat");
    }

    /** FireRed and Ruby: their own addresses, read and one write of each kind that differs. */
    private static void other(Game g) throws Exception {
        String n = g == RUBY ? "Rubis" : "Rouge Feu";
        byte[] ew = new byte[0x40000];
        byte[] iw = new byte[0x8000];
        world(g, ew, iw, 0b0000_0011, false);
        party(g, ew, iw, new byte[][]{
                mon(0x0000_0010L, 4, 8, 20, 22, "SALA", 88, 0, new int[]{10, 52, 0, 0}, false),
                mon(0x0000_0011L, 16, 5, 0, 18, "ROUC", 89, 0, new int[]{33, 0, 0, 0}, false)});
        if (g.enemyBefore()) {
            // the enemy party before the player's: a trainer's Pokémon from the last battle
            byte[] trainer = mon(0x7777_0001L, 74, 10, 0, 30, "", 0, 0, new int[]{33, 0, 0, 0}, false);
            long otherOt = 0x0BEE_F00DL;
            setOt(trainer, otherOt);
            System.arraycopy(trainer, 0, ew, (int) (g.party() - 600 - EWRAM), 100);
        }
        Mem m = mem(g, ew, iw);
        Gen3Reader r = (Gen3Reader) Gen3Reader.forGame(g.id(), info());
        Snapshot s = twoReadings(g, r, m, iw);
        check(s != null && s.party().size() == 2 && s.party().get(0).species() == 4 && "SALA".equals(s.party().get(0).nickname()),
                n + " : équipe lue");
        if (s == null || s.party().size() != 2) {
            return;
        }
        check(s.badges() == 2 && s.map() == 0x0103 && s.playerOt() == 0x5678 && s.cleared() == 0, n + " : badges, lieu, dresseur");
        check(s.money() == 3210 && s.bag() != null && s.bag().get(4) == 7, n + " : argent et sac (" + s.money() + ", " + s.bag() + ")");
        check(s.wild().isEmpty(), n + " : le Pokémon d'un dresseur n'est pas sauvage");
        apply(m, r.addItem(m, 4, 1));
        check(r.read(m).bag().get(4) == 8, n + " : objet rendu");
        apply(m, r.deposit(m, s.party().get(1)));
        check(r.read(m).party().size() == 1 && r.stored(m, 0x11) == Boolean.TRUE, n + " : dépôt dans le PC");
    }

    // ------------------------------------------------------------- building

    /**
     * The save blocks: player (name, gender, trainer ID, options, key), then the player's data (map, flags, money,
     * pockets: 7 Poké Balls, 2 Potions), the pointers to them in IWRAM, and gMain.
     */
    private static void world(Game g, byte[] ew, byte[] iw, int badges, boolean cleared) {
        int s2 = (int) (g.sb2() - EWRAM);
        text(ew, s2, "MAY", 8);
        ew[s2 + 8] = 1;
        le32(ew, s2 + 0xA, TID);
        ew[s2 + 0x14] = 1; // text speed "middle", frame 0
        ew[s2 + 0x15] = 0; // battle style "Shift"
        long key = g.keyAt() < 0 ? 0 : KEY;
        if (g.keyAt() >= 0) {
            le32(ew, s2 + g.keyAt(), KEY);
        }
        int s1 = (int) (g.sb1() - EWRAM);
        ew[s1 + 4] = 1;
        ew[s1 + 5] = 3;
        for (int i = 0; i < 8; i++) {
            if ((badges & (1 << i)) != 0) {
                flag(ew, s1 + g.flags(), g.firstBadge() + i);
            }
        }
        if (cleared) {
            flag(ew, s1 + g.flags(), g.clear());
        }
        le32(ew, s1 + g.money(), 3210 ^ key);
        int k16 = (int) (key & 0xFFFF);
        slot(ew, s1 + g.balls(), 4, 7 ^ k16);
        slot(ew, s1 + g.items(), 13, 2 ^ k16);
        // the empty slots hold quantity 0 encrypted, as the games keep them
        for (int i = 1; i < 13; i++) {
            slot(ew, s1 + g.balls() + 4 * i, 0, k16);
        }
        if (g.pointers() >= 0) {
            int p = (int) (g.pointers() - IWRAM);
            le32(iw, p, g.sb1());
            le32(iw, p + 4, g.sb2());
            le32(iw, p + 8, g.storage());
        }
        int main = (int) (g.main() - IWRAM);
        le32(iw, main + 4, 0x0800_0101L); // callback in the cartridge
        le32(iw, main + 0x24, 100); // frame counter
        if (g != FIRERED) {
            le32(iw, main + 0x20, 100); // a second counter (Ruby, Sapphire, Emerald)
        }
    }

    /** The frames go on between two readings. */
    private static void tick(Game g, byte[] iw) {
        int main = (int) (g.main() - IWRAM);
        le32(iw, main + 0x24, Gen3Reader.u32(iw, main + 0x24) + 30);
        if (g != FIRERED) {
            le32(iw, main + 0x20, Gen3Reader.u32(iw, main + 0x20) + 30);
        }
    }

    /** gMain is known after two readings 30 frames apart. */
    private static Snapshot twoReadings(Game g, Gen3Reader r, Mem m, byte[] iw) {
        r.read(m);
        tick(g, iw);
        Snapshot s = r.read(m);
        check(r.inBattleFlag() == 0, g.id() + " : gMain trouvé, pas en combat (" + r.inBattleFlag() + ")");
        return s;
    }

    private static void party(Game g, byte[] ew, byte[] iw, byte[][] mons) {
        byte[] d = g.iwramParty() ? iw : ew;
        long base = g.iwramParty() ? IWRAM : EWRAM;
        int p = (int) (g.party() - base);
        for (int i = 0; i < mons.length; i++) {
            System.arraycopy(mons[i], 0, d, p + 100 * i, 100);
        }
        d[(int) (g.party() + g.countDelta() - base)] = (byte) mons.length;
    }

    private static Mem mem(Game g, byte[] ew, byte[] iw) {
        return new Mem().add(EWRAM, ew).add(IWRAM, iw);
    }

    /**
     * A party Pokémon: personality, trainer ID, nickname, then the four substructures in the order the personality
     * gives, encrypted with personality ^ trainer ID, their checksum, and the battle values (level, HP).
     */
    private static byte[] mon(long pid, int species, int level, int hp, int maxHp, String nick, int met, int item,
                              int[] moves, boolean egg) {
        byte[] d = new byte[100];
        le32(d, 0, pid);
        le32(d, 4, TID);
        text(d, 8, nick, 10);
        long[] w = new long[12];
        String order = ORDERS[(int) (pid % 24)];
        int gw = order.indexOf('G') * 3;
        int a = order.indexOf('A') * 3;
        int mi = order.indexOf('M') * 3;
        w[gw] = species | ((long) item << 16);
        w[gw + 1] = 1000; // experience
        w[gw + 2] = 0b10_01 | (70L << 8); // PP Ups: 1 on the first move, 2 on the second; friendship 70
        w[a] = moves[0] | ((long) moves[1] << 16);
        w[a + 1] = moves[2] | ((long) moves[3] << 16);
        w[a + 2] = (moves[0] == 0 ? 0 : 35) | ((long) (moves[1] == 0 ? 0 : 30) << 8) | ((long) (moves[2] == 0 ? 0 : 25) << 16);
        w[mi] = (long) met << 8;
        w[mi + 1] = (egg ? 1L : 0L) << 30;
        seal(d, w);
        d[0x54] = (byte) level;
        le16(d, 0x56, hp);
        le16(d, 0x58, maxHp);
        return d;
    }

    private static void seal(byte[] d, long[] w) {
        long key = Gen3Reader.u32(d, 0) ^ Gen3Reader.u32(d, 4);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (int) (w[i] & 0xFFFF) + (int) (w[i] >>> 16);
            le32(d, 0x20 + 4 * i, (w[i] ^ key) & 0xFFFFFFFFL);
        }
        le16(d, 0x1C, sum & 0xFFFF);
    }

    /** Another trainer ID for a structure already sealed (re-encrypted with it). */
    private static void setOt(byte[] d, long ot) {
        long key = Gen3Reader.u32(d, 0) ^ Gen3Reader.u32(d, 4);
        long[] w = new long[12];
        for (int i = 0; i < 12; i++) {
            w[i] = Gen3Reader.u32(d, 0x20 + 4 * i) ^ key;
        }
        le32(d, 4, ot);
        seal(d, w);
    }

    /** A personality that makes the Pokémon of trainer {@link #TID} shiny. */
    private static long shinyPid() {
        long tid = TID & 0xFFFF;
        long sid = TID >>> 16;
        long low = 0x0042;
        long high = tid ^ sid ^ low;
        return (high << 16) | low;
    }

    /** Made-up tables: internal species index = national number, letters from 0xA0, 0xFF ends a name. */
    private static GameInfo info() throws Exception {
        List<Object> chars = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            chars.add(i >= LETTER_A && i < LETTER_A + 26 ? String.valueOf((char) ('A' + i - LETTER_A)) : null);
        }
        List<Object> internal = new ArrayList<>();
        for (int i = 0; i <= 412; i++) {
            internal.add((long) (i <= 386 ? i : 0));
        }
        Path f = Files.createTempFile("cableclub-info", ".json");
        try {
            Files.writeString(f, Json.stringify(Json.map("gameId", "emerald", "gen", 3L, "charTable", chars, "internal", internal)));
            return GameInfo.load(f);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static void flag(byte[] d, int flags, int flag) {
        d[flags + (flag >> 3)] |= (byte) (1 << (flag & 7));
    }

    private static void slot(byte[] d, int o, int item, int qty) {
        le16(d, o, item);
        le16(d, o + 2, qty);
    }

    private static void text(byte[] d, int o, String s, int len) {
        for (int i = 0; i < len; i++) {
            d[o + i] = (byte) (i < s.length() ? LETTER_A + s.charAt(i) - 'A' : 0xFF);
        }
    }

    private static void le16(byte[] d, int o, int v) {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] d, int o, long v) {
        for (int i = 0; i < 4; i++) {
            d[o + i] = (byte) (v >>> (8 * i));
        }
    }

    private static void apply(Mem m, List<GameReader.Write> writes) {
        if (writes == null) {
            check(false, "écriture attendue, aucune proposée");
            return;
        }
        for (GameReader.Write wr : writes) {
            Mem.Block b = m.block(wr.address(), wr.bytes().length);
            if (b == null) {
                check(false, "écriture hors de la mémoire : " + Long.toHexString(wr.address()));
                continue;
            }
            System.arraycopy(wr.bytes(), 0, b.data, (int) (wr.address() - b.start), wr.bytes().length);
        }
    }

    /** A change of the structure of {@code mon} (shared death), at its offset from the structure. */
    private static void patch(Mem m, Mon mon, GameReader.Patch patch) {
        if (patch == null) {
            check(false, "changement attendu, aucun proposé");
            return;
        }
        apply(m, List.of(new GameReader.Write(mon.address() + patch.offset(), patch.bytes())));
    }

    /** The 12 decrypted words of a structure. */
    private static long[] words(byte[] d) {
        long key = Gen3Reader.u32(d, 0) ^ Gen3Reader.u32(d, 4);
        long[] w = new long[12];
        for (int i = 0; i < 12; i++) {
            w[i] = Gen3Reader.u32(d, 0x20 + 4 * i) ^ key;
        }
        return w;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
