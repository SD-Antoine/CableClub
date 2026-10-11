package slt.player.track;

import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game data read from the ROM by the main application (map and location
 * names, species names, internal species order and text table of the
 * cartridge). Lets the tracker name what it reads in memory.
 */
public final class GameInfo {

    public final String game;
    public final int gen;
    private final Map<Integer, String> maps = new HashMap<>();
    private final Map<Integer, String> locations = new HashMap<>();
    private final String[] species;
    private final int[] internal;
    private final String[] charTable;
    private String[] items = new String[0];
    private Map<String, Integer> encoding;

    private GameInfo(String game, int gen, String[] species, int[] internal, String[] charTable) {
        this.game = game;
        this.gen = gen;
        this.species = species;
        this.internal = internal;
        this.charTable = charTable;
    }

    /** Minimal info when the ROM data is unavailable (names stay unknown). */
    public static GameInfo bare(String game, int gen) {
        return new GameInfo(game, gen, new String[0], null, null);
    }

    public static GameInfo load(Path file) throws IOException {
        Map<String, Object> o = Json.obj(Json.parse(Files.readString(file)));
        List<Object> sp = Json.arr(o.get("species"));
        String[] species = new String[sp == null ? 0 : sp.size()];
        for (int i = 0; i < species.length; i++) {
            species[i] = sp.get(i) == null ? null : sp.get(i).toString();
        }
        List<Object> in = Json.arr(o.get("internal"));
        int[] internal = null;
        if (in != null && !in.isEmpty()) {
            internal = new int[in.size()];
            for (int i = 0; i < internal.length; i++) {
                internal[i] = in.get(i) instanceof Number n ? n.intValue() : 0;
            }
        }
        List<Object> ct = Json.arr(o.get("charTable"));
        String[] chars = null;
        if (ct != null && !ct.isEmpty()) {
            chars = new String[ct.size()];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = ct.get(i) == null ? null : ct.get(i).toString();
            }
        }
        GameInfo info = new GameInfo(Json.str(o, "gameId"), (int) Json.num(o, "gen", 0), species, internal, chars);
        List<Object> it = Json.arr(o.get("items"));
        if (it != null) {
            info.items = new String[it.size()];
            for (int i = 0; i < info.items.length; i++) {
                info.items[i] = it.get(i) == null ? null : it.get(i).toString();
            }
        }
        fill(info.maps, Json.obj(o.get("maps")));
        fill(info.locations, Json.obj(o.get("locations")));
        return info;
    }

    private static void fill(Map<Integer, String> into, Map<String, Object> from) {
        if (from == null) {
            return;
        }
        for (Map.Entry<String, Object> e : from.entrySet()) {
            try {
                into.put(Integer.parseInt(e.getKey()), String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
                // not a map id
            }
        }
    }

    /** Name of a map id, or null. */
    public String map(int id) {
        return id < 0 ? null : maps.get(id);
    }

    public boolean hasMaps() {
        return !maps.isEmpty();
    }

    /** Name of a "met location" id, or null. */
    public String location(int id) {
        return id < 0 ? null : locations.get(id);
    }

    /** Name of the place stored in a Pokémon ("met location"), or null when unknown / not recorded. */
    public String metName(int met) {
        if (met < 0 || (gen >= 4 && met == 0)) {
            return null; // gens 4-5: 0 = no location (battle copies, gifts not yet received)
        }
        return location(met);
    }

    public String speciesName(int national) {
        return national > 0 && national < species.length ? species[national] : null;
    }

    /** National number of an internal species index (gens 1 and 3), or -1. */
    public int national(int internalIndex) {
        if (internal == null) {
            return gen == 1 || gen == 3 ? -1 : internalIndex;
        }
        return internalIndex >= 0 && internalIndex < internal.length && internal[internalIndex] > 0 ? internal[internalIndex] : -1;
    }

    /** Name of an item id, or null. */
    public String itemName(int id) {
        String n = id >= 0 && id < items.length ? items[id] : null;
        return n == null || n.isBlank() ? null : n.trim();
    }

    public boolean knowsItems() {
        return items.length > 0;
    }

    /**
     * Encodes a nickname with the cartridge's text table (gens 1-3): {@code len}
     * bytes, padded with {@code terminator}. Characters the game cannot show are
     * left out. Null when nothing can be encoded.
     */
    public byte[] encode(String text, int len, int terminator) {
        if (charTable == null || text == null) {
            return null;
        }
        synchronized (this) {
            if (encoding == null) {
                encoding = new HashMap<>();
                for (int i = charTable.length - 1; i >= 0; i--) {
                    String c = charTable[i];
                    if (c != null && c.length() == 1 && i != terminator) {
                        encoding.put(c, i);
                    }
                }
            }
        }
        byte[] out = new byte[len];
        java.util.Arrays.fill(out, (byte) terminator);
        int n = 0;
        for (int i = 0; i < text.length() && n < len; i++) {
            String c = String.valueOf(text.charAt(i));
            Integer b = encoding.get(c);
            if (b == null) {
                b = encoding.get(c.toUpperCase(java.util.Locale.ROOT));
            }
            if (b == null) {
                b = encoding.get(c.toLowerCase(java.util.Locale.ROOT));
            }
            if (b != null) {
                out[n++] = (byte) (int) b;
            }
        }
        return n == 0 ? null : out;
    }

    /** Decodes a cartridge string (gens 1-3) up to {@code terminator}. */
    public String text(byte[] d, int off, int max, int terminator) {
        if (charTable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max && off + i < d.length; i++) {
            int c = d[off + i] & 0xFF;
            if (c == terminator) {
                break;
            }
            String s = c < charTable.length ? charTable[c] : null;
            if (s == null) {
                continue;
            }
            if (s.startsWith("\\")) {
                continue; // control code
            }
            sb.append(s);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }
}
