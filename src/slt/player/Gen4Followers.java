package slt.player;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * HeartGold / SoulSilver: the Pokémon that follows the player is a map object whose graphics id (+0x10) stands for
 * a texture of the overworld archive (a/0/8/1, "tsure_poke": 8 images of 32x32 or 64x64, up, down, left, right
 * two each). The game's own table that turns a graphics id into a member of that archive is found in the RAM
 * (the field overlay that holds it is loaded while the player walks; the ROM of a room is rewritten by the
 * randomizer, which moves overlays about, so it is not searched there):
 *
 * <pre>
 * 12 bytes an entry, one per graphics id: u32 member of the archive, u16 (animation: 700 to 770), u16 (flags),
 * u16 (size: 822 to 1856), u16 0. The animation and the size differ from a Pokémon to another (flying ones, big
 * ones): only the member (297 to 862) and the last word tell a follower.
 * Graphics id 947 is member 816 (Glaceon): found through the entries of members 815 to 818 in a row, the
 * rest follows (measured on HeartGold FR: the follower of the first Pokémon, Glaceon, drawn there; Butterfree,
 * graphics id 394, is member 308 with another animation: the first version kept 183 entries of 560 and showed
 * no follower for the others).
 * </pre>
 */
final class Gen4Followers {

    private static final int GFX_REF = 947;
    private static final int MEMBER_REF = 816;
    private static final int BEFORE = 12 * 700;
    private static final int AFTER = 12 * 700;
    private static final long RETRY_MS = 3000;

    private ByteBuffer table; // the entries around the reference, the entry of graphics id GFX_REF - 700 first
    private long nextSearch;

    /** Member of the overworld archive drawn for a follower graphics id, -1 if it is not a follower (or the table is not found yet). */
    int member(ByteBuffer ram, int gfx) {
        if (table == null) {
            long now = System.currentTimeMillis();
            if (now < nextSearch) {
                return -1;
            }
            nextSearch = now + RETRY_MS;
            table = find(ram);
            if (table == null) {
                return -1;
            }
        }
        int at = 12 * (gfx - GFX_REF + 700);
        if (at < 0 || at + 12 > table.capacity()) {
            return -1;
        }
        int member = table.getInt(at);
        return table.getShort(at + 10) == 0 && member >= 297 && member < 863 ? member : -1;
    }

    /** Offset in the RAM of the entry of the reference member, -1 if the table is not there. */
    static long locate(ByteBuffer ram) {
        int size = ram.capacity();
        for (int o = 0; o + 48 <= size; o += 4) {
            if (ram.getInt(o) == MEMBER_REF - 1 && entry(ram, o) && entry(ram, o + 12) && entry(ram, o + 24) && entry(ram, o + 36)
                    && ram.getInt(o + 12) == MEMBER_REF && ram.getInt(o + 24) == MEMBER_REF + 1 && ram.getInt(o + 36) == MEMBER_REF + 2) {
                return o + 12;
            }
        }
        return -1;
    }

    private static ByteBuffer find(ByteBuffer ram) {
        long ref = locate(ram);
        long from = ref - BEFORE;
        if (ref < 0 || from < 0) {
            return null;
        }
        long to = Math.min(ram.capacity(), ref + AFTER + 12);
        byte[] t = new byte[BEFORE + AFTER + 12];
        Ram.copy(ram, from, t, 0, (int) (to - from));
        return ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** An entry of the table: the last word is 0 and the animation and the size are in the ranges seen. */
    private static boolean entry(ByteBuffer ram, int o) {
        int animation = ram.getShort(o + 4) & 0xFFFF;
        int size = ram.getShort(o + 8) & 0xFFFF;
        return animation >= 700 && animation <= 770 && size >= 800 && size <= 1900 && ram.getShort(o + 10) == 0;
    }
}
