package slt.client;

import com.dabomstew.pkrandom.pokemon.Evolution;
import com.dabomstew.pkrandom.pokemon.ItemList;
import com.dabomstew.pkrandom.pokemon.Pokemon;
import com.dabomstew.pkrandom.pokemon.Shop;
import com.dabomstew.pkrandom.romhandlers.RomHandler;
import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * SLT's own changes, applied to the ROM after the randomizer (always with
 * the same seed, so every player who generates the room's ROM gets the same
 * bytes):
 * <ul>
 *   <li>secondary shop clerks randomized, the main clerk (whose stock grows
 *       with the badges) untouched, never a Master Ball nor an item worth
 *       more when sold than when bought;</li>
 *   <li>evolution lines of the final ROM (dupes clause of the room);</li>
 *   <li>DS games: the room's shiny rate (the test "shiny value < 8" of the
 *       games' only shiny function becomes "< N"; generations 2 and 3 get the
 *       rate while playing, see the player's ShinyBoost).</li>
 * </ul>
 */
final class SltPatches {

    private static final int MASTER_BALL = 1;

    private SltPatches() {
    }

    static Map<String, Object> apply(String romPath, Map<String, Object> rando, long seed, Consumer<String> progress)
            throws IOException {
        // the original game ("Jeu original"): nothing changes, only the evolution lines are read
        boolean enabled = Json.bool(rando, "enabled", true);
        boolean shops = enabled && "secondary".equals(Json.str(rando, "shops"));
        Path out = Path.of(romPath);
        Path tmp = out.resolveSibling(out.getFileName() + ".slt.tmp");
        int[][] gen3Pools = new int[2][];
        Map<String, Object> result = Rando.withHandler(romPath, rh -> {
            Random rng = new Random(seed ^ 0x534C54_2D53_4C54L);
            List<String> notes = new ArrayList<>();
            boolean changed = false;
            if (shops && rh.generationOfPokemon() == 3) {
                // generation 3: the shops are found in the scripts of the ROM itself (any version, any language)
                gen3Pools[0] = itemPool(rh, false);
                gen3Pools[1] = itemPool(rh, true);
            } else if (shops) {
                if (rh.hasShopRandomization()) {
                    progress.accept("Vendeurs secondaires…");
                    int n = secondaryShops(rh, rng);
                    notes.add(n > 0 ? n + " vendeurs secondaires randomisés"
                            : "Boutiques : aucun vendeur secondaire connu du randomiseur pour cette version");
                    changed |= n > 0;
                } else {
                    notes.add("Boutiques : non modifiables dans ce jeu (1re et 2e générations)");
                }
            }
            int shinyNum = slt.ShinyOdds.numerator(rando);
            if (shinyNum > 1 && rh.generationOfPokemon() >= 4) {
                progress.accept("Taux de chromatiques…");
                if (dsShinyRate(rh, shinyNum)) {
                    notes.add("Chromatiques : " + slt.ShinyOdds.label(shinyNum));
                    changed = true;
                } else {
                    notes.add("Chromatiques : taux non modifiable dans cette version du jeu");
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("families", families(rh));
            r.put("notes", notes);
            if (changed) {
                progress.accept("Écriture de la ROM…");
                if (!rh.saveRomFile(tmp.toString(), seed)) {
                    throw new Http.Fail(500, "Écriture de la ROM impossible.");
                }
            }
            r.put("changed", changed);
            return r;
        });
        if (Json.bool(result, "changed", false)) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        if (gen3Pools[0] != null) {
            progress.accept("Vendeurs secondaires…");
            byte[] rom = Files.readAllBytes(out);
            int n = gen3Shops(rom, gen3Pools[0], gen3Pools[1], new Random(seed ^ 0x53484F50L));
            Files.write(out, rom);
            @SuppressWarnings("unchecked")
            List<String> notes = (List<String>) result.get("notes");
            notes.add(n > 0 ? n + " vendeurs secondaires randomisés" : "Boutiques : aucun vendeur secondaire trouvé");
        }
        return result;
    }

    /**
     * The shiny test of the DS games (thumb, same in Diamond/Pearl/Platinum, HeartGold/SoulSilver and the four
     * generation 5 games): {@code eor r0,r1; eor r0,r2; cmp r0,#8; bhs; mov r0,#1}, then the end of the function:
     * generation 4 {@code b; mov r0,#0; lsl r0,#24; lsr r0,#24; bx lr}, generation 5 {@code bx lr; mov r0,#0; bx lr}.
     * A threshold up to 255 only changes the constant; a higher one (up to 65536, every Pokémon) rewrites the test
     * from the {@code cmp}: {@code lsr r0,#s; sub r0,#c; sbc r0,r0; neg r0,r0; bx lr}, i.e. 1 when (x >> s) < c.
     */
    private static final byte[] DS_SHINY = {0x48, 0x40, 0x50, 0x40, 0x08, 0x28, 0x01, (byte) 0xD2, 0x01, 0x20};
    private static final byte[] DS_TAIL_GEN4 = {0x00, (byte) 0xE0, 0x00, 0x20, 0x00, 0x06, 0x00, 0x0E, 0x70, 0x47};
    private static final byte[] DS_TAIL_GEN5 = {0x70, 0x47, 0x00, 0x20, 0x70, 0x47};

    private static boolean dsShinyRate(RomHandler rh, int num) {
        try {
            java.lang.reflect.Field f = rh.getClass().getDeclaredField("arm9");
            f.setAccessible(true);
            byte[] arm9 = (byte[]) f.get(rh);
            int at = find(arm9, DS_SHINY);
            if (at < 0) {
                return false;
            }
            if (find(arm9, DS_SHINY, at + 1) >= 0) {
                Log.warn("Taux de chromatiques : plusieurs tests trouvés, ROM non modifiée");
                return false;
            }
            int[] test = slt.ShinyOdds.dsTest(num);
            if (test[0] == 0) {
                arm9[at + 4] = (byte) test[1];
                return true;
            }
            int tail = at + DS_SHINY.length;
            int room = matches(arm9, tail, DS_TAIL_GEN4) ? 16 : matches(arm9, tail, DS_TAIL_GEN5) ? 12 : -1;
            if (room < 0) {
                Log.warn("Taux de chromatiques : fin du test inconnue, ROM non modifiée");
                return false;
            }
            int[] code = {0x0800 | (test[0] << 6), 0x3800 | test[1], 0x4180, 0x4240, 0x4770, 0x4770, 0x4770, 0x4770};
            for (int i = 0; i < room / 2; i++) {
                arm9[at + 4 + 2 * i] = (byte) code[i];
                arm9[at + 5 + 2 * i] = (byte) (code[i] >>> 8);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("Taux de chromatiques : " + e.getMessage());
            return false;
        }
    }

    private static int find(byte[] data, byte[] pattern) {
        return find(data, pattern, 0);
    }

    private static int find(byte[] data, byte[] pattern, int from) {
        for (int i = from; i + pattern.length <= data.length; i++) {
            if (matches(data, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(byte[] data, int at, byte[] pattern) {
        if (at < 0 || at + pattern.length > data.length) {
            return false;
        }
        for (int k = 0; k < pattern.length; k++) {
            if (data[at + k] != pattern[k]) {
                return false;
            }
        }
        return true;
    }

    private static final int GEN3_LAST_ITEM = 376;
    private static final int GEN3_TM01 = 289;
    private static final int GEN3_TM50 = 338;
    private static final int GEN3_HM08 = 346;
    private static final Set<Integer> GEN3_POTIONS = Set.of(13, 20, 21, 22);
    private static final Set<Integer> GEN3_BALLS = Set.of(2, 3, 4);

    /** Items a secondary clerk may sell: useful items (or TMs), never a Master Ball nor the "OP" resale items. */
    private static int[] itemPool(RomHandler rh, boolean tms) {
        ItemList allowed = rh.getNonBadItems().copy();
        allowed.banSingles(MASTER_BALL);
        List<Integer> op = rh.getOPShopItems();
        if (op != null) {
            allowed.banSingles(op.stream().mapToInt(Integer::intValue).toArray());
        }
        List<Integer> out = new ArrayList<>();
        for (int id = 1; id <= GEN3_LAST_ITEM; id++) {
            boolean tm = id >= GEN3_TM01 && id <= GEN3_TM50;
            boolean machine = id >= GEN3_TM01 && id <= GEN3_HM08;
            if (tms ? tm : allowed.isAllowed(id) && !allowed.isTM(id) && !machine) {
                out.add(id);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Generation 3 shops, found through the {@code pokemart} script command
     * (0x86 + pointer to a list of items ended by 0). The main clerk of a Poké
     * Mart sells Poké Balls and Potions (both lists of a clerk whose stock
     * changes during the adventure count as main); the others — department
     * store floors, market stalls, TM, vitamin or herb sellers — are
     * randomized: TMs stay TMs (any TM, never an HM), the other lists get
     * useful items. Same number of items, so the scripts are untouched.
     *
     * @return number of lists randomized
     */
    static int gen3Shops(byte[] rom, int[] pool, int[] tmPool, Random rng) {
        TreeMap<Integer, int[]> lists = new TreeMap<>();
        for (int i = 0; i + 5 <= rom.length; i++) {
            if ((rom[i] & 0xFF) != 0x86) {
                continue;
            }
            long ptr = (rom[i + 1] & 0xFFL) | ((rom[i + 2] & 0xFFL) << 8) | ((rom[i + 3] & 0xFFL) << 16)
                    | ((rom[i + 4] & 0xFFL) << 24);
            if (ptr < 0x08000000L || ptr >= 0x08000000L + rom.length || (ptr & 1) != 0) {
                continue;
            }
            int off = (int) (ptr - 0x08000000L);
            if (lists.containsKey(off)) {
                continue;
            }
            int[] items = gen3List(rom, off);
            if (items != null && items.length >= 2) {
                lists.put(off, items);
            }
        }
        Set<Integer> main = new HashSet<>();
        for (Map.Entry<Integer, int[]> e : lists.entrySet()) {
            if (has(e.getValue(), GEN3_POTIONS) && has(e.getValue(), GEN3_BALLS)) {
                main.add(e.getKey());
            }
        }
        // a clerk with two stocks (before / after an event): the other list sits right next to the main one
        for (Map.Entry<Integer, int[]> e : lists.entrySet()) {
            if (main.contains(e.getKey()) || !has(e.getValue(), GEN3_POTIONS)) {
                continue;
            }
            for (int m : new ArrayList<>(main)) {
                if (Math.abs(m - e.getKey()) <= 0x80) {
                    main.add(e.getKey());
                    break;
                }
            }
        }
        int n = 0;
        for (Map.Entry<Integer, int[]> e : lists.entrySet()) {
            if (main.contains(e.getKey())) {
                continue;
            }
            int[] items = e.getValue();
            boolean tmSeller = java.util.Arrays.stream(items).allMatch(x -> x >= GEN3_TM01 && x <= GEN3_HM08);
            int[] from = tmSeller ? tmPool : pool;
            if (from.length < items.length) {
                continue;
            }
            Set<Integer> used = new HashSet<>();
            for (int k = 0; k < items.length; k++) {
                int item;
                do {
                    item = from[rng.nextInt(from.length)];
                } while (!used.add(item));
                rom[e.getKey() + 2 * k] = (byte) item;
                rom[e.getKey() + 2 * k + 1] = (byte) (item >> 8);
            }
            n++;
        }
        return n;
    }

    private static int[] gen3List(byte[] rom, int off) {
        List<Integer> out = new ArrayList<>();
        for (int o = off; o + 2 <= rom.length && out.size() <= 60; o += 2) {
            int v = (rom[o] & 0xFF) | ((rom[o + 1] & 0xFF) << 8);
            if (v == 0) {
                return out.stream().mapToInt(Integer::intValue).toArray();
            }
            if (v > GEN3_LAST_ITEM) {
                return null;
            }
            out.add(v);
        }
        return null;
    }

    private static boolean has(int[] items, Set<Integer> wanted) {
        for (int x : items) {
            if (wanted.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Randomizes the clerks that are not the main one: random useful items,
     * no Master Ball, no items whose resale beats their price (the
     * randomizer's "OP" list: Rare Candy, Nuggets, Pearls, mushrooms…).
     *
     * @return number of shops changed
     */
    private static int secondaryShops(RomHandler rh, Random rng) {
        Map<Integer, Shop> shops = rh.getShopItems();
        if (shops == null) {
            return 0;
        }
        ItemList allowed = rh.getNonBadItems().copy();
        allowed.banSingles(MASTER_BALL);
        List<Integer> op = rh.getOPShopItems();
        if (op != null) {
            allowed.banSingles(op.stream().mapToInt(Integer::intValue).toArray());
        }
        int n = 0;
        Map<Integer, Shop> out = new TreeMap<>();
        for (Map.Entry<Integer, Shop> e : shops.entrySet()) {
            Shop old = e.getValue();
            Shop shop = new Shop(old);
            if (!old.isMainGame) {
                List<Integer> items = new ArrayList<>();
                Set<Integer> used = new HashSet<>();
                for (int i = 0; i < old.items.size(); i++) {
                    int item = allowed.randomNonTM(rng);
                    for (int tries = 0; used.contains(item) && tries < 50; tries++) {
                        item = allowed.randomNonTM(rng);
                    }
                    used.add(item);
                    items.add(item);
                }
                shop.items = items;
                n++;
            } else {
                shop.items = new ArrayList<>(old.items);
            }
            out.put(e.getKey(), shop);
        }
        rh.setShopItems(out);
        return n;
    }

    /** Evolution line of every species (smallest national number of the line). */
    static List<Object> families(RomHandler rh) {
        List<Pokemon> all = rh.getPokemon();
        int max = 0;
        for (Pokemon p : all) {
            if (p != null) {
                max = Math.max(max, p.number);
            }
        }
        int[] parent = new int[max + 1];
        for (int i = 0; i <= max; i++) {
            parent[i] = i;
        }
        for (Pokemon p : all) {
            if (p == null) {
                continue;
            }
            for (Evolution ev : p.evolutionsFrom) {
                if (ev.from != null && ev.to != null && ev.to.number <= max) {
                    union(parent, ev.from.number, ev.to.number);
                }
            }
        }
        List<Object> out = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            out.add((long) find(parent, i));
        }
        return out;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) {
            if (ra < rb) {
                p[rb] = ra;
            } else {
                p[ra] = rb;
            }
        }
    }

    static void log(String msg) {
        Log.info("SLT : " + msg);
    }
}
