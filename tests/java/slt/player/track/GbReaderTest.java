package slt.player.track;

import slt.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Game Boy adapter (point 7a of the follow-up of the audit of 2026-11-10) on memories built here byte by byte:
 * Red (generation 1) and Crystal (generation 2). Reading (party, player, bag, PC, battle), finding the party when it
 * moved, and every write the referee can ask for, applied to the memory and read again. No ROM, save or emulator
 * state: the species and character tables are made up for the test (the real ones are read in the player's ROM).
 */
public final class GbReaderTest {

    private static final long WRAM = 0xC000L;
    private static final long SRAM = 0x100000L;
    /** Made-up tables: internal species index = national + 0x10 (generation 1), letters from 0xA0. */
    private static final int INTERNAL_SHIFT = 0x10;
    private static final int LETTER_A = 0xA0;
    private static final int END = 0x50;

    private static int failed;

    public static void main(String[] args) throws Exception {
        red();
        redMoved();
        redBeforeStarter();
        crystal();
        System.out.println(failed == 0 ? "ok     adaptateur Game Boy (Rouge, Cristal : lecture et écritures)"
                : "ÉCHEC  adaptateur Game Boy : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ Red

    private static final int RED_PARTY = 0xD163 - (int) WRAM;
    private static final int OT = 0x3039;

    private static void red() throws Exception {
        byte[] w = new byte[0x8000];
        int p = RED_PARTY;
        redPlayer(w, p);
        party1(w, p, new int[][]{
                // national species, level, hp, max hp, DVs, moves
                {25, 12, 30, 31, 0x1234, 84, 45, 0, 0},
                {16, 9, 0, 25, 0x5678, 33, 28, 16, 0},
                {19, 7, 20, 20, 0x9ABC, 33, 0, 0, 0}}, new String[]{"ZAP", "PIOU", "RAT"});
        // bag: 5 Poké Balls, 3 Potions; money 12345 (decimal digits)
        list(w, p + 0x1BA, new int[][]{{4, 5}, {20, 3}});
        w[p + 0x1BA + 0x2A] = 0x01;
        w[p + 0x1BA + 0x2B] = 0x23;
        w[p + 0x1BA + 0x2C] = 0x45;
        // current box: one Pokémon
        box1(w, p + 0x91D, new int[][]{{10, 3, 0, 15, 0x0F0F, 33, 0, 0, 0}});
        w[p + 0x43F] = 1; // a team in the Hall of Fame
        Mem m = new Mem().add(WRAM, w).add(SRAM, new byte[0x8000]);
        GbReader r = GbReader.forGame("red", info("red", 1));

        Snapshot s = r.read(m);
        check(s != null && s.party().size() == 3, "Rouge : équipe de 3");
        if (s == null || s.party().size() != 3) {
            return;
        }
        Mon zap = s.party().get(0);
        Mon piou = s.party().get(1);
        check(zap.species() == 25 && zap.level() == 12 && zap.hp() == 30 && zap.maxHp() == 31, "Rouge : espèce, niveau, PV");
        check("ZAP".equals(zap.nickname()) && zap.otId() == OT && zap.uid() == GbReader.uid(OT, 0x1234), "Rouge : surnom, dresseur, identité");
        check(piou.fainted() && !zap.fainted(), "Rouge : K.O. lu");
        check(s.badges() == 3 && s.map() == 12 && s.playerOt() == OT, "Rouge : badges, lieu, dresseur");
        check(s.money() == 12345, "Rouge : argent (" + s.money() + ")");
        check(s.bag() != null && s.bag().get(4) == 5 && s.bag().get(20) == 3 && s.balls() == 1, "Rouge : sac et Poké Balls");
        check(s.cleared() == 1 && s.battle() == 0 && s.wild().isEmpty(), "Rouge : partie finie, pas de combat");
        check(r.stored(m, GbReader.uid(OT, 0x0F0F)) == Boolean.TRUE && r.stored(m, zap.uid()) == Boolean.FALSE, "Rouge : PC");

        // a wild battle: the enemy's battle copy
        int e = p - 0x17E;
        w[p - 0x10C] = 1;
        w[e] = (byte) (41 + INTERNAL_SHIFT);
        w[e + 14] = 4;
        be16(w, e + 12, 0x4321);
        be16(w, e + 1, 18);
        be16(w, e + 15, 18);
        s = r.read(m);
        check(s.battle() == 1 && s.wild().size() == 1 && s.wild().get(0).species() == 41 && s.wild().get(0).level() == 4,
                "Rouge : Pokémon sauvage en combat");
        w[p - 0x10C] = 0;

        // writes
        patch(m, zap, r.zeroHp(m.read(zap.address(), r.structSize()), zap));
        check(r.read(m).party().get(0).fainted(), "Rouge : K.O. écrit");
        check(r.zeroHp(m.read(zap.address(), r.structSize()), zap) == null, "Rouge : déjà K.O., rien à écrire");
        apply(m, r.nickname(m, zap, "ECLAIR"));
        check("ECLAIR".equals(r.read(m).party().get(0).nickname()), "Rouge : surnom écrit");
        check(r.nickname(m, zap, "ECLAIR").isEmpty(), "Rouge : surnom déjà là");
        apply(m, r.addItem(m, 20, 2));
        apply(m, r.addItem(m, 30, 1));
        Map<Integer, Integer> bag = r.read(m).bag();
        check(bag.get(20) == 5 && bag.get(30) == 1, "Rouge : objets rendus (pile et nouvelle ligne)");
        apply(m, r.removeItem(m, 4));
        s = r.read(m);
        check(!s.bag().containsKey(4) && s.balls() == 0 && s.bag().get(20) == 5, "Rouge : Poké Balls retirées, le reste remonte");
        check(r.removeItem(m, 4) == null, "Rouge : rien à retirer");
        apply(m, r.forgetMove(m, zap, 84));
        check(moves(r, m, zap).equals(List.of(45L)), "Rouge : capacité oubliée (" + moves(r, m, zap) + ")");
        int zs = (int) (zap.address() - WRAM);
        check((w[zs + 29] & 0xFF) == 21 && w[zs + 30] == 0, "Rouge : les PP remontent avec la capacité");
        check(r.forgetMove(m, zap, 45) == null, "Rouge : jamais la dernière capacité");
        check(r.heldItem(m, zap) == -1 && r.setHeldItem(m, zap, 1) == null, "Rouge : pas d'objet tenu en 1re génération");
        apply(m, r.setBattleStyle(m));
        check((w[p + 0x1F2] & 0x40) != 0 && r.setBattleStyle(m).isEmpty(), "Rouge : style de combat « Défini »");

        // the K.O. Pokémon goes to the box; a released one leaves it
        apply(m, r.deposit(m, piou));
        s = r.read(m);
        check(s.party().size() == 2 && s.party().stream().noneMatch(x -> x.uid() == piou.uid()), "Rouge : dépôt, l'équipe se resserre");
        check("RAT".equals(s.party().get(1).nickname()), "Rouge : le suivant remonte avec son surnom");
        check(r.stored(m, piou.uid()) == Boolean.TRUE, "Rouge : déposé dans la boîte");
        check((w[p + 0x91D + 22 + 33 + 3] & 0xFF) == 9, "Rouge : niveau gardé dans la boîte");
        apply(m, r.release(m, piou.uid()));
        check(r.stored(m, piou.uid()) == Boolean.FALSE && (w[p + 0x91D] & 0xFF) == 1, "Rouge : relâché depuis la boîte");
        apply(m, r.release(m, s.party().get(1).uid()));
        check(r.read(m).party().size() == 1, "Rouge : relâché depuis l'équipe");
        check(r.release(m, zap.uid()) == null && r.deposit(m, zap) == null, "Rouge : jamais le dernier Pokémon");
    }

    /** The party moved by a few bytes (as in the French versions): found by its contents. */
    private static void redMoved() throws Exception {
        byte[] w = new byte[0x8000];
        int p = RED_PARTY + 5;
        redPlayer(w, p);
        party1(w, p, new int[][]{{4, 6, 20, 20, 0x1111, 10, 0, 0, 0}}, new String[]{"SALA"});
        list(w, p + 0x1BA, new int[][]{{4, 1}});
        Snapshot s = GbReader.forGame("red", info("red", 1)).read(new Mem().add(WRAM, w).add(SRAM, new byte[0x8000]));
        check(s != null && s.party().size() == 1 && s.party().get(0).species() == 4 && s.badges() == 3 && s.map() == 12,
                "Rouge : équipe déplacée retrouvée");
    }

    /** Before the starter: the empty party right after the player's name. */
    private static void redBeforeStarter() throws Exception {
        byte[] w = new byte[0x8000];
        int p = RED_PARTY + 5;
        text(w, p - 11, "SACHA", 11);
        w[p] = 0;
        w[p + 1] = (byte) 0xFF;
        Snapshot s = GbReader.forGame("red", info("red", 1)).read(new Mem().add(WRAM, w).add(SRAM, new byte[0x8000]));
        check(s != null && s.party().isEmpty(), "Rouge : équipe vide avant le starter");
    }

    private static void redPlayer(byte[] w, int p) {
        be16(w, p + 0x1F6, OT);
        w[p + 0x1FB] = 12;
        w[p + 0x1F3] = 0b0000_0111;
        w[p + 0x1F2] = 0x03; // options: text speed 3, battle style "Shift"
    }

    /** Generation 1 party: species list, 44-byte structures, OT names, nicknames. */
    private static void party1(byte[] w, int p, int[][] mons, String[] nicks) {
        w[p] = (byte) mons.length;
        for (int i = 0; i < mons.length; i++) {
            int[] x = mons[i];
            int s = p + 8 + i * 44;
            w[p + 1 + i] = (byte) (x[0] + INTERNAL_SHIFT);
            w[s] = (byte) (x[0] + INTERNAL_SHIFT);
            be16(w, s + 1, x[2]);
            w[s + 3] = (byte) x[1];
            for (int k = 0; k < 4; k++) {
                w[s + 8 + k] = (byte) x[5 + k];
                w[s + 29 + k] = (byte) (x[5 + k] == 0 ? 0 : 20 + k);
            }
            be16(w, s + 12, OT);
            be16(w, s + 27, x[4]);
            w[s + 33] = (byte) x[1];
            be16(w, s + 34, x[3]);
            text(w, p + 8 + 6 * 44 + i * 11, "SACHA", 11);
            text(w, p + 8 + 6 * 44 + 6 * 11 + i * 11, nicks[i], 11);
        }
        w[p + 1 + mons.length] = (byte) 0xFF;
    }

    /** Generation 1 box: count, species list, 33-byte structures, OT names, nicknames. */
    private static void box1(byte[] w, int b, int[][] mons) {
        w[b] = (byte) mons.length;
        for (int i = 0; i < mons.length; i++) {
            int[] x = mons[i];
            int s = b + 22 + i * 33;
            w[b + 1 + i] = (byte) (x[0] + INTERNAL_SHIFT);
            w[s] = (byte) (x[0] + INTERNAL_SHIFT);
            w[s + 3] = (byte) x[1];
            be16(w, s + 12, OT);
            be16(w, s + 27, x[4]);
        }
        w[b + 1 + mons.length] = (byte) 0xFF;
    }

    // -------------------------------------------------------------- Crystal

    private static final int CRYSTAL_PARTY = 0xDCD7 - (int) WRAM;

    private static void crystal() throws Exception {
        byte[] w = new byte[0x8000];
        byte[] sram = new byte[0x8000];
        int p = CRYSTAL_PARTY;
        be16(w, p - 0x85C, OT);
        w[p - 0x22] = 3; // map group
        w[p - 0x21] = 7; // map number
        w[p - 0x480] = (byte) 0xFF; // the 8 badges of Johto
        w[p - 0x47F] = 0b0000_0011; // 2 of Kanto
        party2(w, p, new int[][]{
                // species, level, hp, max hp, DVs, held item, moves
                {155, 14, 40, 41, 0xAAAA, 0, 33, 43, 52, 0},
                {16, 10, 0, 30, 0x5555, 30, 33, 0, 0, 0},
                {175, 5, 10, 10, 0x1357, 0, 0, 0, 0, 0}}, new String[]{"FEU", "PIOU", "OEUF"}, 2);
        int balls = p - 0x400;
        int items = balls - 0x45;
        list(w, balls, new int[][]{{5, 4}}); // 4 Poké Balls
        list(w, items, new int[][]{{18, 2}}); // 2 Potions
        w[items - 57 + 50 + 1] = 1; // HM02
        w[items - 57] = 3; // three of TM01
        w[p - 0x379] = 1; // Hall of Fame
        w[0xCFCC - (int) WRAM] = 0x03; // options: text speed 3, battle style "Shift"
        w[0xCFCC - (int) WRAM + 4] = 0x40; // printer brightness
        Mem m = new Mem().add(WRAM, w).add(SRAM, sram);
        GbReader r = GbReader.forGame("crystal", info("crystal", 2));

        Snapshot s = r.read(m);
        check(s != null && s.party().size() == 3, "Cristal : équipe de 3");
        if (s == null || s.party().size() != 3) {
            return;
        }
        Mon feu = s.party().get(0);
        Mon piou = s.party().get(1);
        Mon egg = s.party().get(2);
        check(feu.species() == 155 && feu.level() == 14 && feu.hp() == 40 && "FEU".equals(feu.nickname()), "Cristal : Pokémon lu");
        check(feu.shiny() && !piou.shiny(), "Cristal : chromatique d'après les DV");
        check(egg.egg() && !feu.egg(), "Cristal : œuf");
        check(s.badges() == 10 && s.map() == 3 * 256 + 7 && s.playerOt() == OT, "Cristal : badges (Johto et Kanto), lieu");
        check(s.bag() != null && s.bag().get(5) == 4 && s.bag().get(18) == 2 && s.balls() == 1, "Cristal : poches du sac");
        check(s.bag().get(191) != null && s.bag().get(191) == 3 && s.bag().get(244) != null, "Cristal : CT et CS dans le sac");
        check(s.cleared() == 1 && s.battle() == 0, "Cristal : partie finie, pas de combat");

        check(r.heldItem(m, piou) == 30 && r.heldItem(m, feu) == 0, "Cristal : objet tenu");
        apply(m, r.setHeldItem(m, piou, 0));
        check(r.heldItem(m, piou) == 0, "Cristal : objet tenu retiré");
        check(r.heldItem(m, egg) == -1 && r.setHeldItem(m, egg, 1) == null, "Cristal : rien sur un œuf");
        apply(m, r.forgetMove(m, feu, 43));
        check(moves(r, m, feu).equals(List.of(33L, 52L)), "Cristal : capacité oubliée (" + moves(r, m, feu) + ")");
        int fs = (int) (feu.address() - WRAM);
        check((w[fs + 23] & 0xFF) == 20 && (w[fs + 24] & 0xFF) == 22 && w[fs + 25] == 0, "Cristal : les PP remontent avec la capacité");
        Map<String, Object> set = r.battleSet(m, feu);
        check(set != null && Json.num(Json.obj(set.get("ivs")), "atk", 0) == 20, "Cristal : fiche de combat (DV x 2)");

        apply(m, r.addItem(m, 2, 1)); // a Great Ball: the ball pocket
        apply(m, r.addItem(m, 18, 1)); // a Potion: the item pocket
        Map<Integer, Integer> bag = r.read(m).bag();
        check(bag.get(2) == 1 && (w[balls] & 0xFF) == 2 && bag.get(18) == 3, "Cristal : objets rendus dans la bonne poche");
        apply(m, r.removeItem(m, 244));
        check(r.read(m).bag().get(244) == null && r.removeItem(m, 244) == null, "Cristal : CS retirée");
        apply(m, r.removeItem(m, 5));
        check(r.read(m).bag().get(5) == null && r.read(m).balls() == 1, "Cristal : Poké Balls retirées, les Super Balls restent");
        apply(m, r.nickname(m, feu, "BRASIER"));
        check("BRASIER".equals(r.read(m).party().get(0).nickname()), "Cristal : surnom écrit");
        apply(m, r.setBattleStyle(m));
        check((w[0xCFCC - (int) WRAM] & 0x40) != 0, "Cristal : style de combat « Défini »");

        // the box of the cartridge RAM
        int box = 0x2D10;
        sram[box] = 0;
        sram[box + 1] = (byte) 0xFF;
        check(r.stored(m, piou.uid()) == Boolean.FALSE, "Cristal : boîte vide");
        r.cartridgeRamLive(false);
        check(r.deposit(m, piou) == null, "Cristal : pas de dépôt quand la RAM de la cartouche n'est plus celle du jeu");
        r.cartridgeRamLive(true);
        apply(m, r.deposit(m, piou));
        check(r.stored(m, piou.uid()) == Boolean.TRUE && r.read(m).party().size() == 2, "Cristal : dépôt dans la boîte");
        apply(m, r.release(m, piou.uid()));
        check(r.stored(m, piou.uid()) == Boolean.FALSE, "Cristal : relâché depuis la boîte");
        patch(m, feu, r.zeroHp(m.read(feu.address(), r.structSize()), feu));
        check(r.read(m).party().get(0).fainted(), "Cristal : K.O. écrit");
    }

    /** Generation 2 party: species list (0xFD for an egg), 48-byte structures, OT names, nicknames. */
    private static void party2(byte[] w, int p, int[][] mons, String[] nicks, int eggIndex) {
        w[p] = (byte) mons.length;
        for (int i = 0; i < mons.length; i++) {
            int[] x = mons[i];
            int s = p + 8 + i * 48;
            w[p + 1 + i] = (byte) (i == eggIndex ? 0xFD : x[0]);
            w[s] = (byte) x[0];
            w[s + 1] = (byte) x[5];
            for (int k = 0; k < 4; k++) {
                w[s + 2 + k] = (byte) x[6 + k];
                w[s + 23 + k] = (byte) (x[6 + k] == 0 ? 0 : 20 + k);
            }
            be16(w, s + 6, OT);
            be16(w, s + 21, x[4]);
            w[s + 31] = (byte) x[1];
            be16(w, s + 34, x[2]);
            be16(w, s + 36, x[3]);
            text(w, p + 8 + 6 * 48 + i * 11, "LUTH", 11);
            text(w, p + 8 + 6 * 48 + 6 * 11 + i * 11, nicks[i], 11);
        }
        w[p + 1 + mons.length] = (byte) 0xFF;
    }

    // -------------------------------------------------------------- helpers

    /** The made-up tables of a game, as GameInfo reads them (normally taken from the player's ROM). */
    private static GameInfo info(String game, int gen) throws Exception {
        List<Object> chars = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            chars.add(i >= LETTER_A && i < LETTER_A + 26 ? String.valueOf((char) ('A' + i - LETTER_A)) : null);
        }
        Map<String, Object> o = Json.map("gameId", game, "gen", (long) gen, "charTable", chars);
        if (gen == 1) {
            List<Object> internal = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                internal.add((long) (i > INTERNAL_SHIFT && i <= 151 + INTERNAL_SHIFT ? i - INTERNAL_SHIFT : 0));
            }
            o.put("internal", internal);
        }
        Path f = Files.createTempFile("cableclub-info", ".json");
        try {
            Files.writeString(f, Json.stringify(o));
            return GameInfo.load(f);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static void text(byte[] w, int o, String s, int len) {
        for (int i = 0; i < len; i++) {
            w[o + i] = (byte) (i < s.length() ? LETTER_A + s.charAt(i) - 'A' : END);
        }
    }

    /** A list of the bag: count, item / quantity pairs, 0xFF. */
    private static void list(byte[] w, int o, int[][] items) {
        w[o] = (byte) items.length;
        for (int i = 0; i < items.length; i++) {
            w[o + 1 + 2 * i] = (byte) items[i][0];
            w[o + 2 + 2 * i] = (byte) items[i][1];
        }
        w[o + 1 + 2 * items.length] = (byte) 0xFF;
    }

    private static void be16(byte[] w, int o, int v) {
        w[o] = (byte) (v >> 8);
        w[o + 1] = (byte) v;
    }

    private static List<Object> moves(GbReader r, Mem m, Mon mon) {
        Map<String, Object> set = r.battleSet(m, mon);
        return set == null ? List.of() : Json.arr(set.get("moves"));
    }

    /** Writes the adapter's changes into the memory, as the player does in the emulator. */
    static void apply(Mem m, List<GameReader.Write> writes) {
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

    /** A change of a party structure (shared death), at its offset from the structure of {@code mon}. */
    static void patch(Mem m, Mon mon, GameReader.Patch patch) {
        if (patch == null) {
            check(false, "changement attendu, aucun proposé");
            return;
        }
        apply(m, List.of(new GameReader.Write(mon.address() + patch.offset(), patch.bytes())));
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
