package slt.player;

import slt.player.track.GameInfo;
import slt.player.track.Mem;
import slt.player.track.NdsPkm;
import slt.player.track.NdsReader;
import slt.player.track.Snapshot;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * The save buffer of the DS games stays the one found. A battle of the game can leave blocks that look like a party in
 * the RAM (the Catching demonstration of Black / White: the Pokémon of the Professor, IDs at 0, map 0, no money): one
 * unreadable reading of the party must not make the reader follow them. Runs on made-up RAM images: no data of a game.
 */
public final class DsSaveTest {

    private static final long RAM = 0x02000000L;
    private static final int REAL = 0x200000;
    private static final int OTHER = 0x250000;
    private static final int PARTY_GEN5 = 0x18E00;
    private static final int READS = 300;
    private static final Random RNG = new Random(5);
    private static int failed;

    public static void main(String[] args) {
        oneReading();
        demonstration();
        firstLook();
        moved();
        anotherGame();
        mapZero();
        generation4();
        System.out.println(failed == 0 ? "ok     sauvegarde DS suivie" : "ÉCHEC  sauvegarde DS suivie : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** One unreadable reading: nothing is told, and the next one finds everything again. */
    private static void oneReading() {
        byte[] ram = new byte[4 << 20];
        save(ram, REAL, 0x1234, 0x5678, "Brisket", 648, 398);
        NdsReader reader = white();
        Snapshot before = reader.read(mem(ram));
        check(before != null && before.party().get(0).species() == 648, "la sauvegarde est trouvée");
        ram[REAL + PARTY_GEN5] = 5;
        check(reader.read(mem(ram)) == null, "une lecture illisible ne dit rien");
        check(reader.mapAddress() == address(REAL), "une lecture illisible ne perd pas la sauvegarde");
        ram[REAL + PARTY_GEN5] = 6;
        Snapshot after = reader.read(mem(ram));
        check(after != null && after.party().get(0).species() == 648 && after.map() == 398 && reader.mapAddress() == address(REAL),
                "la lecture suivante retrouve tout");
    }

    /** What the demonstration leaves, as much like a save as it can be: never followed, however long the party is unreadable. */
    private static void demonstration() {
        byte[] ram = new byte[4 << 20];
        save(ram, REAL, 0x1234, 0x5678, "Brisket", 648, 398);
        save(ram, OTHER, 0, 0, "Bloc", 572, 0);
        NdsReader reader = white();
        check(reader.read(mem(ram)) != null && reader.mapAddress() == address(REAL), "la vraie sauvegarde, pas le bloc de la démonstration");
        ram[REAL + PARTY_GEN5] = 5;
        for (int i = 0; i < READS; i++) {
            Snapshot s = reader.read(mem(ram));
            if (s != null) {
                check(false, "le bloc de la démonstration n'est jamais suivi (lecture " + i + ")");
                break;
            }
        }
        ram[REAL + PARTY_GEN5] = 6;
        Snapshot back = reader.read(mem(ram));
        check(back != null && back.party().get(0).species() == 648 && reader.mapAddress() == address(REAL), "la sauvegarde revient");
    }

    /** Before the save is found: neither a block with IDs at 0, nor a generation 5 block without box names. */
    private static void firstLook() {
        byte[] zero = new byte[4 << 20];
        save(zero, OTHER, 0, 0, "Bloc", 572, 0);
        check(white().read(mem(zero)) == null, "des identifiants à 0 ne sont pas un joueur");
        byte[] unnamed = new byte[4 << 20];
        save(unnamed, OTHER, 0x1234, 0x5678, "Bloc", 572, 0);
        unnamed[OTHER + 4] = 0;
        unnamed[OTHER + 5] = 0;
        check(white().read(mem(unnamed)) == null, "5e génération : sans nom de boîte, pas une sauvegarde");
        byte[] empty = new byte[4 << 20];
        empty[OTHER + PARTY_GEN5] = 6;
        name(empty, OTHER + 0x19404, "Brisket");
        check(white().read(mem(empty)) != null, "une nouvelle partie, encore sans Pokémon ni identifiant, est trouvée");
    }

    /** The same trainer's buffer elsewhere takes over after a few readings. */
    private static void moved() {
        byte[] ram = new byte[4 << 20];
        save(ram, REAL, 0x1234, 0x5678, "Brisket", 648, 398);
        NdsReader reader = white();
        check(reader.read(mem(ram)) != null, "la sauvegarde est trouvée");
        save(ram, OTHER, 0x1234, 0x5678, "Brisket", 648, 398);
        ram[REAL + PARTY_GEN5] = 5;
        check(reader.read(mem(ram)) == null, "une lecture ne suffit pas à changer de tampon");
        Snapshot s = null;
        for (int i = 0; i < 20 && s == null; i++) {
            s = reader.read(mem(ram));
        }
        check(s != null && reader.mapAddress() == address(OTHER), "déplacée : le même dresseur est retrouvé");
    }

    /** Another game played after a reset, elsewhere in the RAM: followed only once the save is long gone. */
    private static void anotherGame() {
        byte[] ram = new byte[4 << 20];
        save(ram, REAL, 0x1234, 0x5678, "Brisket", 648, 398);
        NdsReader reader = white();
        check(reader.read(mem(ram)) != null, "la sauvegarde est trouvée");
        java.util.Arrays.fill(ram, REAL + PARTY_GEN5, REAL + PARTY_GEN5 + 8, (byte) 0);
        save(ram, OTHER, 0x4321, 0x8765, "Autre", 25, 317);
        int reads = 0;
        Snapshot s = null;
        while (s == null && reads < READS) {
            s = reader.read(mem(ram));
            reads++;
        }
        check(reads > 60, "un autre dresseur n'est pas suivi tout de suite (" + reads + " lectures)");
        check(s != null && s.party().get(0).species() == 25 && reader.mapAddress() == address(OTHER), "après une réinitialisation, l'autre partie est suivie");
        s = reader.read(mem(ram));
        check(s != null && s.party().get(0).species() == 25, "et le reste");
    }

    /** Map 0 of generation 5 is a save buffer not filled in: no place. */
    private static void mapZero() {
        byte[] ram = new byte[4 << 20];
        save(ram, REAL, 0x1234, 0x5678, "Brisket", 648, 0);
        Snapshot s = white().read(mem(ram));
        check(s != null && s.map() == -1, "5e génération : la carte 0 n'est aucun lieu");
    }

    /** Generation 4: no box name asked (they are elsewhere), one unreadable reading changes nothing either. */
    private static void generation4() {
        byte[] ram = new byte[4 << 20];
        int party = 0x98;
        put16(ram, REAL + party, 6);
        put16(ram, REAL + party + 4, 1);
        System.arraycopy(DsShinyTest.pokemon(0x11112222L, 0x1234, 0x5678, 25, 10, false, RNG), 0, ram, REAL + party + 8, NdsPkm.GEN4_PARTY_SIZE);
        put16(ram, REAL + 0x78 - 0x10, 'A');
        put16(ram, REAL + 0x78 - 0x0E, 0xFFFF);
        put16(ram, REAL + 0x78, 0x1234);
        put16(ram, REAL + 0x7A, 0x5678);
        put16(ram, REAL + 0x7C, 3000);
        put16(ram, REAL + 0x1280, 342);
        NdsReader reader = NdsReader.forGame("platinum", GameInfo.bare("platinum", 4));
        Snapshot s = reader.read(mem(ram));
        check(s != null && s.party().get(0).species() == 25 && s.map() == 342, "4e génération : la sauvegarde est trouvée");
        ram[REAL + party] = 5;
        check(reader.read(mem(ram)) == null && reader.mapAddress() == address(REAL) + 0x1280 - 0x19504, "4e génération : une lecture illisible ne la perd pas");
        ram[REAL + party] = 6;
        check(reader.read(mem(ram)) != null, "4e génération : la lecture suivante la retrouve");
    }

    private static NdsReader white() {
        return NdsReader.forGame("white", GameInfo.bare("white", 5));
    }

    private static Mem mem(byte[] ram) {
        return new Mem().add(RAM, ram);
    }

    /** The console address {@code mapAddress} gives for a generation 5 save buffer at {@code off}. */
    private static long address(int off) {
        return RAM + off + 0x19504;
    }

    private static void save(byte[] ram, int off, int tid, int sid, String name, int species, int map) {
        put16(ram, off + PARTY_GEN5, 6);
        put16(ram, off + PARTY_GEN5 + 4, 1);
        long pid = 0x11110000L + species;
        System.arraycopy(DsShinyTest.pokemon(pid, tid, sid, species, 7, true, RNG), 0, ram, off + PARTY_GEN5 + 8, NdsPkm.GEN5_PARTY_SIZE);
        name(ram, off + 0x19404, name);
        put16(ram, off + 0x19414, tid);
        put16(ram, off + 0x19416, sid);
        put16(ram, off + 0x19504, map);
        name(ram, off + 4, "BOITE 1");
    }

    private static void name(byte[] ram, int off, String name) {
        byte[] b = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < b.length; i++) {
            put16(ram, off + 2 * i, b[i]);
        }
        put16(ram, off + 2 * b.length, 0xFFFF);
    }

    private static void put16(byte[] m, int off, int v) {
        m[off] = (byte) v;
        m[off + 1] = (byte) (v >>> 8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
