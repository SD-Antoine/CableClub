package slt.player.track;

import java.util.List;
import java.util.Map;

/**
 * What the tracker read in the game memory at one moment.
 *
 * @param party    party in slot order (eggs included, the engine skips them)
 * @param badges   number of badges, -1 if unknown
 * @param map      current map id (key of the map table), -1 if unknown
 * @param playerOt trainer ID of the player, -1 if unknown
 * @param wild     wild Pokémon present in the battle memory (gen-specific, may be stale)
 * @param battle   -1 unknown, 0 no battle, 1 wild battle, 2 trainer battle
 * @param balls    the bag holds Poké Balls: 1 yes, 0 no, -1 unknown (encounters only count once
 *                 the player can catch)
 * @param bag      item id -> quantity over every pocket, null if unknown
 * @param money    money of the player, -1 if unknown
 * @param cleared  the game has been finished (Champion beaten): 1 yes, 0 no, -1 unknown
 * @param enemy    opponent's party during a battle (may be stale outside battles)
 */
public record Snapshot(List<Mon> party, int badges, int map, int playerOt, List<Mon> wild, int battle, int balls,
                       Map<Integer, Integer> bag, long money, int cleared, List<Mon> enemy) {

    public Snapshot(List<Mon> party, int badges, int map, int playerOt, List<Mon> wild, int battle, int balls) {
        this(party, badges, map, playerOt, wild, battle, balls, null, -1, -1, List.of());
    }
}
