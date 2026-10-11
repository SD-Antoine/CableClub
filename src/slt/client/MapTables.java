package slt.client;

import com.dabomstew.pkrandom.romhandlers.Gen1RomHandler;
import slt.Log;
import com.dabomstew.pkrandom.romhandlers.Gen2RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen3RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen4RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen5RomHandler;
import com.dabomstew.pkrandom.romhandlers.RomHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Name of every map of the game, read through the randomizer's ROM handlers.
 * The automatic tracker reads the current map id from the game memory; this
 * table turns it into the same zone names as the wild encounter areas.
 *
 * Keys: gen 1 map id; gens 2-3 "bank * 256 + map"; gens 4-5 map header index.
 */
public final class MapTables {

    private MapTables() {
    }

    public static Map<String, Object> export(RomHandler rh) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (rh instanceof Gen1RomHandler) {
                String[] names = (String[]) field(rh, Gen1RomHandler.class, "mapNames");
                for (int i = 0; names != null && i < names.length; i++) {
                    put(out, i, names[i]);
                }
            } else if (rh instanceof Gen2RomHandler || rh instanceof Gen3RomHandler) {
                if (rh instanceof Gen3RomHandler && !(Boolean) field(rh, Gen3RomHandler.class, "mapLoadingDone")) {
                    // the engine reads the map headers lazily
                    call(rh, Gen3RomHandler.class, "preprocessMaps", new Class<?>[0]);
                    Field done = Gen3RomHandler.class.getDeclaredField("mapLoadingDone");
                    done.setAccessible(true);
                    done.set(rh, true);
                }
                String[][] names = (String[][]) field(rh, rh.getClass(), "mapNames");
                for (int bank = 0; names != null && bank < names.length; bank++) {
                    for (int map = 0; names[bank] != null && map < names[bank].length; map++) {
                        put(out, bank * 256 + map, names[bank][map]);
                    }
                }
            } else if (rh instanceof Gen4RomHandler) {
                gen4(rh, out);
            } else if (rh instanceof Gen5RomHandler) {
                gen5(rh, out);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Table des cartes indisponible : " + e);
        }
        return out;
    }

    /**
     * Location names by id, as stored in the "met location" of a Pokémon
     * (gen 3: region map section; gens 4-5: index in the location names).
     * Empty for gens 1-2, which do not record where a Pokémon was caught.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> locations(RomHandler rh) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (rh instanceof Gen3RomHandler) {
                gen3Locations(rh, out);
            } else if (rh instanceof Gen4RomHandler) {
                int textIndex = (Integer) entry(rh, Gen4RomHandler.class, "getInt", "MapNamesTextOffset");
                List<String> names = (List<String>) call(rh, Gen4RomHandler.class, "getStrings", new Class<?>[]{int.class}, textIndex);
                for (int i = 0; i < names.size(); i++) {
                    put(out, i, names.get(i));
                }
            } else if (rh instanceof Gen5RomHandler) {
                int textIndex = (Integer) entry(rh, Gen5RomHandler.class, "getInt", "MapNamesTextOffset");
                List<String> names = (List<String>) call(rh, Gen5RomHandler.class, "getStrings",
                        new Class<?>[]{boolean.class, int.class}, false, textIndex);
                for (int i = 0; i < names.size(); i++) {
                    put(out, i, names.get(i));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Noms des lieux indisponibles : " + e);
        }
        return out;
    }

    private static void gen3Locations(RomHandler rh, Map<String, Object> out) throws ReflectiveOperationException {
        Class<?> c = Gen3RomHandler.class;
        int labels = (Integer) entry(rh, c, "getValue", "MapLabels");
        Object romEntry = field(rh, c, "romEntry");
        int romType = (Integer) field(romEntry, romEntry.getClass(), "romType");
        boolean frlg = romType == com.dabomstew.pkrandom.constants.Gen3Constants.RomType_FRLG;
        boolean emerald = romType == com.dabomstew.pkrandom.constants.Gen3Constants.RomType_Em;
        int first = frlg ? com.dabomstew.pkrandom.constants.Gen3Constants.frlgMapLabelsStart : 0;
        int end = frlg ? 0xC5 : emerald ? 0xD5 : 0x58;
        Method readPointer = c.getDeclaredMethod("readPointer", int.class);
        Method readString = c.getDeclaredMethod("readVariableLengthString", int.class);
        readPointer.setAccessible(true);
        readString.setAccessible(true);
        for (int id = first; id < end; id++) {
            int ptr = frlg ? labels + (id - first) * 4 : labels + id * 8 + 4;
            int str = (Integer) readPointer.invoke(rh, ptr);
            if (str > 0) {
                put(out, id, (String) readString.invoke(rh, str));
            }
        }
    }

    /**
     * Data the in-game tracker needs to describe what it reads: species names
     * by national number, internal species order (gens 1 and 3) and the text
     * table of the cartridge (gens 1-3).
     */
    public static Map<String, Object> tracker(RomHandler rh) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> species = new java.util.ArrayList<>();
        for (com.dabomstew.pkrandom.pokemon.Pokemon p : rh.getPokemon()) {
            if (p != null) {
                while (species.size() < p.number) {
                    species.add(null);
                }
                species.add(p.name);
            }
        }
        out.put("species", species);
        try {
            out.put("items", strings(rh.getItemNames()));
        } catch (RuntimeException e) {
            Log.warn("Noms des objets indisponibles : " + e);
        }
        try {
            if (rh instanceof Gen1RomHandler) {
                out.put("internal", ints((int[]) field(rh, Gen1RomHandler.class, "pokeRBYToNumTable")));
                out.put("charTable", strings((String[]) field(rh, com.dabomstew.pkrandom.romhandlers.AbstractGBCRomHandler.class, "tb")));
            } else if (rh instanceof Gen2RomHandler) {
                out.put("charTable", strings((String[]) field(rh, com.dabomstew.pkrandom.romhandlers.AbstractGBCRomHandler.class, "tb")));
            } else if (rh instanceof Gen3RomHandler) {
                out.put("internal", ints((int[]) field(rh, Gen3RomHandler.class, "internalToPokedex")));
                out.put("charTable", strings((String[]) field(rh, Gen3RomHandler.class, "tb")));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Tables du suivi indisponibles : " + e);
        }
        return out;
    }

    private static List<Object> ints(int[] a) {
        List<Object> out = new java.util.ArrayList<>();
        if (a != null) {
            for (int v : a) {
                out.add((long) v);
            }
        }
        return out;
    }

    private static List<Object> strings(String[] a) {
        List<Object> out = new java.util.ArrayList<>();
        if (a != null) {
            for (String v : a) {
                out.add(v);
            }
        }
        return out;
    }

    private static void put(Map<String, Object> out, int key, String name) {
        if (name != null && !name.isBlank()) {
            out.put(String.valueOf(key), name.trim());
        }
    }

    private static Object field(Object o, Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object call(Object o, Class<?> c, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static Object entry(Object rh, Class<?> c, String method, String key) throws ReflectiveOperationException {
        Object romEntry = field(rh, c, "romEntry");
        return call(romEntry, romEntry.getClass(), method, new Class<?>[]{String.class}, key);
    }

    @SuppressWarnings("unchecked")
    private static void gen4(RomHandler rh, Map<String, Object> out) throws ReflectiveOperationException {
        Class<?> c = Gen4RomHandler.class;
        byte[] arm9 = (byte[]) field(rh, c, "arm9");
        String tableFile = (String) entry(rh, c, "getFile", "MapTableFile");
        byte[] internalNames = (byte[]) call(rh, com.dabomstew.pkrandom.romhandlers.AbstractDSRomHandler.class,
                "readFile", new Class<?>[]{String.class}, tableFile);
        int count = internalNames.length / 16;
        int base = (Integer) entry(rh, c, "getInt", "MapTableARM9Offset");
        int textIndex = (Integer) entry(rh, c, "getInt", "MapNamesTextOffset");
        int indexSize = (Integer) entry(rh, c, "getInt", "MapTableNameIndexSize");
        List<String> names = (List<String>) call(rh, c, "getStrings", new Class<?>[]{int.class}, textIndex);
        for (int map = 0; map < count; map++) {
            int off = base + map * 24;
            int nameIndex = indexSize == 2 ? (arm9[off + 18] & 0xFF) | ((arm9[off + 19] & 0xFF) << 8) : arm9[off + 18] & 0xFF;
            if (nameIndex < names.size()) {
                put(out, map, names.get(nameIndex));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void gen5(RomHandler rh, Map<String, Object> out) throws ReflectiveOperationException {
        Class<?> c = Gen5RomHandler.class;
        String tableFile = (String) entry(rh, c, "getFile", "MapTableFile");
        Object narc = call(rh, com.dabomstew.pkrandom.romhandlers.AbstractDSRomHandler.class,
                "readNARC", new Class<?>[]{String.class}, tableFile);
        List<byte[]> files = (List<byte[]>) narc.getClass().getField("files").get(narc);
        byte[] headers = files.get(0);
        int textIndex = (Integer) entry(rh, c, "getInt", "MapNamesTextOffset");
        List<String> names = (List<String>) call(rh, c, "getStrings", new Class<?>[]{boolean.class, int.class}, false, textIndex);
        for (int map = 0; map < headers.length / 48; map++) {
            int nameIndex = headers[map * 48 + 26] & 0xFF;
            if (nameIndex < names.size()) {
                put(out, map, names.get(nameIndex));
            }
        }
    }
}
