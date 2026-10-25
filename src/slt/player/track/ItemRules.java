package slt.player.track;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Items the room's rules take out of the game, by generation, with the games' own ids (checked against the item
 * names of the French cartridges: Red / Yellow, Gold / Crystal, Ruby / FireRed / Emerald, Diamond / Platinum /
 * SoulSilver, Black / White 2):
 * <ul>
 *   <li>{@code heal}: potions, status cures, revives, PP, drinks, herbs and other food, the flutes that cure (generations
 *       3 to 5) and the berries that heal (HP, status, PP);</li>
 *   <li>{@code repel}: Repel, Super Repel, Max Repel;</li>
 *   <li>{@code masterBall};</li>
 *   <li>{@code expShare}: Exp. All (generation 1), Exp. Share;</li>
 *   <li>{@code fly}: HM02.</li>
 * </ul>
 */
public final class ItemRules {

    /** The move Fly: the same number in generations 1 to 5. */
    public static final int FLY = 19;

    private static final Map<String, String> LABELS = Map.of("heal", "pas d'objets de soin", "repel", "pas de Repousse",
            "masterBall", "pas de Master Ball", "expShare", "pas de Multi Exp", "fly", "pas de Vol");

    private ItemRules() {
    }

    /** Ids from {from, to} pairs (bounds included). */
    private static Set<Integer> ids(int... ranges) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            for (int id = ranges[i]; id <= ranges[i + 1]; id++) {
                out.add(id);
            }
        }
        return Set.copyOf(out);
    }

    private static final Set<Integer> HEAL_1 = ids(11, 20, 52, 54, 60, 62, 80, 83);
    private static final Set<Integer> HEAL_2 = ids(9, 18, 21, 21, 38, 40, 46, 48, 63, 65, 72, 72, 74, 74, 78, 80, 83, 84,
            109, 109, 114, 114, 121, 124, 139, 139, 150, 150, 156, 156, 173, 174);
    private static final Set<Integer> HEAL_3 = ids(13, 41, 44, 45, 133, 147);
    private static final Set<Integer> HEAL_4 = ids(17, 44, 54, 54, 65, 67, 149, 163, 504, 504);
    private static final Set<Integer> HEAL_5 = ids(17, 44, 54, 54, 65, 67, 149, 163, 504, 504, 591, 591);

    /** The items of these categories in a generation's games. */
    public static Set<Integer> banned(int gen, Collection<String> categories) {
        Set<Integer> out = new HashSet<>();
        for (String c : categories) {
            out.addAll(items(gen, c));
        }
        return out;
    }

    /** The items of one category. */
    public static Set<Integer> items(int gen, String category) {
        return switch (category) {
            case "heal" -> switch (gen) {
                case 1 -> HEAL_1;
                case 2 -> HEAL_2;
                case 3 -> HEAL_3;
                case 4 -> HEAL_4;
                default -> HEAL_5;
            };
            case "repel" -> switch (gen) {
                case 1 -> Set.of(30, 56, 57);
                case 2 -> Set.of(20, 42, 43);
                case 3 -> Set.of(83, 84, 86);
                default -> Set.of(76, 77, 79);
            };
            case "masterBall" -> Set.of(1);
            case "expShare" -> Set.of(gen == 1 ? 75 : gen == 2 ? 57 : gen == 3 ? 182 : 216);
            case "fly" -> Set.of(gen == 1 ? 197 : gen == 2 ? 244 : gen == 3 ? 340 : 421);
            default -> Set.of();
        };
    }

    /** The category of the rules that takes this item out, or null. */
    public static String category(int gen, int item, Collection<String> categories) {
        for (String c : categories) {
            if (items(gen, c).contains(item)) {
                return c;
            }
        }
        return null;
    }

    /** The rule that takes the items of a category out, as told to the players ("pas d'objets de soin"…). */
    public static String label(String category) {
        return category == null ? "règles de la salle" : LABELS.getOrDefault(category, "règles de la salle");
    }

    /** Mail: the letter it carries is kept apart in the save, it stays with its Pokémon. */
    public static boolean mail(int gen, int item) {
        return switch (gen) {
            case 1 -> false;
            case 2 -> item == 158 || (item >= 181 && item <= 189);
            case 3 -> item >= 121 && item <= 132;
            default -> item >= 137 && item <= 148;
        };
    }
}
