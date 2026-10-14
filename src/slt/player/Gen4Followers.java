package slt.player;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
 * 12 bytes an entry, one per graphics id: u32 member of the archive, u16 751, u16 (animation), u16 832, u16 0.
 * Graphics id 947 is member 816 (Glaceon): found through the entries of members 815 to 818 in a row, the
 * rest follows (measured on HeartGold FR: the follower of the first Pokémon, Glaceon, drawn there).
 * </pre>
 */
final class Gen4Followers {

    private static final int GFX_REF = 947;
    private static final int MEMBER_REF = 816;
    private static final int BEFORE = 12 * 700;
    private static final int AFTER = 12 * 700;
    private static final long RETRY_MS = 3000;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private ByteBuffer table; // the entries around the reference, the entry of graphics id GFX_REF - 700 first
    private long nextSearch;

    /** Member of the overworld archive drawn for a follower graphics id, -1 if it is not a follower (or the table is not found yet). */
    int member(MemorySegment ram, int gfx) {
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
        boolean follower = (table.getShort(at + 4) & 0xFFFF) == 751 && (table.getShort(at + 8) & 0xFFFF) == 832;
        return follower && member >= 297 && member < 863 ? member : -1;
    }

    private static ByteBuffer find(MemorySegment ram) {
        long size = ram.byteSize();
        for (long o = 0; o + 48 <= size; o += 4) {
            if (ram.get(I32, o) == MEMBER_REF - 1 && entry(ram, o) && entry(ram, o + 12) && entry(ram, o + 24) && entry(ram, o + 36)
                    && ram.get(I32, o + 12) == MEMBER_REF && ram.get(I32, o + 24) == MEMBER_REF + 1 && ram.get(I32, o + 36) == MEMBER_REF + 2) {
                long ref = o + 12; // entry of the reference member
                long from = ref - BEFORE;
                long to = Math.min(size, ref + AFTER + 12);
                if (from < 0) {
                    return null;
                }
                byte[] t = new byte[BEFORE + AFTER + 12];
                MemorySegment.copy(ram, ValueLayout.JAVA_BYTE, from, t, 0, (int) (to - from));
                return ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
        return null;
    }

    private static boolean entry(MemorySegment ram, long o) {
        return (ram.get(U16, o + 4) & 0xFFFF) == 751 && (ram.get(U16, o + 8) & 0xFFFF) == 832 && ram.get(U16, o + 10) == 0;
    }
}
