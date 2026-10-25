package slt.client;

import com.dabomstew.pkrandom.pokemon.Trainer;
import com.dabomstew.pkrandom.pokemon.TrainerPokemon;
import com.dabomstew.pkrandom.romhandlers.RomHandler;
import slt.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Level caps of the Hardcore Nuzlocke, read from the trainers of the room's ROM (so a randomized ROM gives its own):
 * the level of the strongest Pokémon of each gym leader (first battle, not the rematches), then the League's (the
 * Champion, or the last boss right after the Elite Four). The randomizer tags these trainers: "GYMn-LEADER"
 * (generations 3 to 5), "GYMn" for the whole gym (generations 1 and 2: the leader is the strongest), "ELITEn" (or
 * "ELITEn-1" for the first battle in FireRed / LeafGreen), "CHAMPION", and the final rival or bosses ("RIVALn-k",
 * "UBER") where the game has no Champion of its own.
 */
public final class LevelCaps {

    private LevelCaps() {
    }

    /** {"gyms": [8 levels], "league": level}; a level of 0 is unknown. Null when the ROM has no tagged gyms. */
    public static Map<String, Object> of(RomHandler rh) {
        Map<String, List<Integer>> byTag = new HashMap<>();
        for (Trainer t : rh.getTrainers()) {
            if (t == null || t.tag == null || t.pokemon == null) {
                continue;
            }
            int max = 0;
            for (TrainerPokemon p : t.pokemon) {
                max = Math.max(max, p.level);
            }
            if (max > 0) {
                byTag.computeIfAbsent(t.tag, k -> new ArrayList<>()).add(max);
            }
        }
        return of(byTag);
    }

    /** Same, from the highest level of every tagged trainer (by tag). */
    static Map<String, Object> of(Map<String, List<Integer>> byTag) {
        List<Object> gyms = new ArrayList<>();
        int known = 0;
        int previous = 0;
        for (int n = 1; n <= 8; n++) {
            List<Integer> leader = byTag.get("GYM" + n + "-LEADER");
            List<Integer> gym = byTag.get("GYM" + n);
            int cap = 0;
            if (leader != null) {
                cap = min(leader);          // the first battle: rematches are stronger
            } else if (gym != null) {
                cap = max(gym);             // generations 1 and 2: the leader is the strongest of the gym
            }
            if (cap > 0) {
                known++;
                // a later badge never lowers the cap (gyms that can be done in another order)
                cap = Math.max(cap, previous);
                previous = cap;
            }
            gyms.add((long) cap);
        }
        if (known == 0) {
            return null;
        }
        int elite = 0;
        for (int n = 1; n <= 4; n++) {
            int first = 0;
            for (Map.Entry<String, List<Integer>> e : byTag.entrySet()) {
                String tag = e.getKey();
                if (tag.equals("ELITE" + n) || tag.equals("ELITE" + n + "-1")) {
                    int v = min(e.getValue());
                    first = first == 0 ? v : Math.min(first, v);
                }
            }
            elite = Math.max(elite, first);
        }
        int league = 0;
        if (elite > 0) {
            // the Champion's first battle, when it comes right after the Elite Four (not a post-game Champion)
            List<Integer> champion = byTag.get("CHAMPION");
            if (champion != null) {
                for (int v : champion) {
                    if (v >= elite && v <= elite + 12 && (league == 0 || v < league)) {
                        league = v;
                    }
                }
            }
            if (league == 0) {
                // no Champion of its own (the rival in generation 1 and FireRed / LeafGreen, N and Ghetsis in
                // Black / White): the strongest final boss within a few levels of the Elite Four
                for (Map.Entry<String, List<Integer>> e : byTag.entrySet()) {
                    if (!e.getKey().startsWith("RIVAL") && !e.getKey().equals("UBER")) {
                        continue;
                    }
                    for (int v : e.getValue()) {
                        if (v >= elite && v <= elite + 12) {
                            league = Math.max(league, v);
                        }
                    }
                }
            }
            if (league == 0) {
                league = elite;
            }
        }
        league = Math.max(league, previous);
        return Json.map("gyms", gyms, "league", (long) league);
    }

    private static int min(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).min().orElse(0);
    }

    private static int max(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
