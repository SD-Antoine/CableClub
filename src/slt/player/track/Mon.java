package slt.player.track;

/**
 * One Pokémon read from the game memory.
 *
 * @param uid      identity that survives evolution, boxes and healing (personality value, or OT+DVs)
 * @param species  national Pokédex number
 * @param level    current level
 * @param hp       current HP
 * @param maxHp    maximum HP
 * @param nickname nickname as shown in game, or null if unknown
 * @param otId     original trainer ID (wild Pokémon carry the player's ID in gens 3-5)
 * @param met      "met location" id stored in the Pokémon (gens 3-5), -1 if unknown
 * @param egg      still an egg (not a Pokémon yet)
 * @param shiny    shiny (different colours): the shiny clause lets the player ignore it
 * @param address  where the structure lives in memory (used to apply a shared death)
 */
public record Mon(long uid, int species, int level, int hp, int maxHp, String nickname, int otId, int met,
                  boolean egg, boolean shiny, long address) {

    public boolean fainted() {
        return hp == 0 && maxHp > 0;
    }
}
