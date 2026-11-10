package slt.player.track;

import slt.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The map of generation 5 (made-up RAM): the save only knows the map of the last door, the place-name block of the game
 * the real one. The Champion is still told by the save, and generation 4 does not change.
 */
public final class NdsMapTest {

    private static final long RAM = 0x02000000L;
    private static final int BASE = 0x200000;
    private static final int BLOCK = 0x100000;
    private static int failed;

    public static void main(String[] args) throws IOException {
        // Black / White, then Black 2 / White 2: the same behaviour, the map further in the block
        game("white", 0x21200, 0x21204, 0x5E);
        game("white2", 0x21100, 0x21104, 0x66);
        tables();
        System.out.println(failed == 0 ? "ok     carte de la 5e génération" : "ÉCHEC  carte de la 5e génération : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void game(String game, int money, int badges, int place) {
        byte[] ram = save(money);
        NdsReader reader = NdsReader.forGame(game, GameInfo.bare(game, 5));
        put16(ram, BASE + 0x19504, 389);
        check(map(reader, ram) == 389, game + " : sans le bloc, la carte de la sauvegarde");

        // the source file's name elsewhere (the game's own data), without the block's magic: not the block
        write(ram, 0x20000, "field_place_name.c");
        put16(ram, 0x20000 + place, 999);
        check(map(reader, ram) == 389, game + " : le nom du fichier seul n'est pas le bloc");

        // a block of another source file, with a value where the map would be: not this block
        block(ram, 0x30000, place, 500);
        write(ram, 0x30004, "clact.c\0\0\0\0\0\0\0\0\0\0\0");
        check(map(reader, ram) == 389, game + " : le bloc d'un autre fichier est ignoré");

        // another block of the same file, not filled in: skipped
        block(ram, BLOCK - 0x1000, place, 0);
        check(map(reader, ram) == 389, game + " : un bloc sans carte est ignoré");

        // outside, no door: the save stays behind
        block(ram, BLOCK, place, 317);
        check(map(reader, ram) == 317, game + " : hors d'une porte, la vraie carte");

        // a battle: no block any more, the same place
        clear(ram, BLOCK);
        check(map(reader, ram) == 317, game + " : en combat, la dernière carte connue");
        block(ram, BLOCK, place, 317);
        check(map(reader, ram) == 317, game + " : après le combat, la même carte");

        // its memory used for something else, the name still there: not the block any more
        put16(ram, BLOCK, 0);
        put16(ram, BLOCK + 2, 0);
        put16(ram, BLOCK + 4 + place, 500);
        check(map(reader, ram) == 317, game + " : sans son en-tête, ce n'est plus le bloc");
        block(ram, BLOCK, place, 317);

        // moved in memory: found again
        clear(ram, BLOCK);
        block(ram, BLOCK + 0x8000, place, 318);
        check(map(reader, ram) == 318, game + " : bloc déplacé, retrouvé");

        // a door while there is no block: the save is right, not the place of before
        clear(ram, BLOCK + 0x8000);
        put16(ram, BASE + 0x19504, 396);
        check(map(reader, ram) == 396, game + " : une porte sans bloc, la carte de la sauvegarde");

        // the Champion: told by the save, whatever the place-name block says
        block(ram, BLOCK, place, 145);
        put16(ram, BASE + 0x19504, 317);
        ram[BASE + badges] = (byte) 0xFF;
        check(reader.read(new Mem().add(RAM, ram)).cleared() == 0, game + " : le Maître vient de la sauvegarde (pas battu)");
        put16(ram, BASE + 0x19504, 145);
        block(ram, BLOCK, place, 317);
        check(reader.read(new Mem().add(RAM, ram)).cleared() == 1, game + " : le Maître vient de la sauvegarde (battu)");
    }

    /** With the map tables of the room, a value that names no map is not trusted. */
    private static void tables() throws IOException {
        Path f = Files.createTempFile("cableclub-carte", ".json");
        try {
            Files.writeString(f, Json.stringify(Json.map("gameId", "white", "gen", 5L, "maps", Json.map("317", "Route 1", "389", "Renouet"))));
            NdsReader reader = NdsReader.forGame("white", GameInfo.load(f));
            byte[] ram = save(0x21200);
            put16(ram, BASE + 0x19504, 389);
            block(ram, BLOCK, 0x5E, 500);
            check(map(reader, ram) == 389, "une valeur qui ne nomme aucun lieu est ignorée");
            block(ram, BLOCK, 0x5E, 317);
            check(map(reader, ram) == 317, "un lieu connu est lu");
        } finally {
            Files.deleteIfExists(f);
        }
        // generation 4: the save, whatever else the memory holds
        NdsReader platinum = NdsReader.forGame("platinum", GameInfo.bare("platinum", 4));
        byte[] ram = new byte[4 << 20];
        put16(ram, BASE + 0x98, 6);
        put16(ram, BASE + 0x78 - 0x10, 'A');
        put16(ram, BASE + 0x78 - 0x0E, 0xFFFF);
        put16(ram, BASE + 0x1280, 342);
        block(ram, BLOCK, 0x66, 311);
        Snapshot s = platinum.read(new Mem().add(RAM, ram));
        check(s != null && s.map() == 342, "4e génération : la carte de la sauvegarde");
    }

    private static int map(NdsReader reader, byte[] ram) {
        return reader.read(new Mem().add(RAM, ram)).map();
    }

    /** A made-up save buffer with an empty party. */
    private static byte[] save(int money) {
        byte[] ram = new byte[4 << 20];
        put16(ram, BASE + 0x18E00, 6);
        put16(ram, BASE + 0x19404, 'A');
        put16(ram, BASE + 0x19406, 0xFFFF);
        put16(ram, BASE + money, 4000);
        return ram;
    }

    private static void block(byte[] ram, int off, int place, int map) {
        put16(ram, off, 0x0093);
        put16(ram, off + 2, 0x194E);
        write(ram, off + 4, "field_place_name.c");
        put16(ram, off + 4 + place, map);
    }

    private static void clear(byte[] ram, int off) {
        java.util.Arrays.fill(ram, off, off + 0x100, (byte) 0);
    }

    private static void write(byte[] ram, int off, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, ram, off, b.length);
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
