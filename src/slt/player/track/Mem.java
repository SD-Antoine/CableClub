package slt.player.track;

import java.util.ArrayList;
import java.util.List;

/**
 * Copy of the console memory the tracker reads, addressed like the console
 * bus (Game Boy work RAM at 0xC000, GBA EWRAM at 0x02000000, DS main RAM at
 * 0x02000000…). The copy is taken between two frames, then decoded on the
 * tracker thread without slowing the game down.
 */
public final class Mem {

    /** One contiguous memory area. */
    public static final class Block {
        public final long start;
        public final byte[] data;

        public Block(long start, byte[] data) {
            this.start = start;
            this.data = data;
        }

        public boolean contains(long addr, int len) {
            return addr >= start && addr + len <= start + data.length;
        }
    }

    private final List<Block> blocks = new ArrayList<>();

    public Mem add(long start, byte[] data) {
        blocks.add(new Block(start, data));
        return this;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public Block block(long addr, int len) {
        for (Block b : blocks) {
            if (b.contains(addr, len)) {
                return b;
            }
        }
        return null;
    }

    public boolean has(long addr, int len) {
        return block(addr, len) != null;
    }

    public int u8(long addr) {
        Block b = block(addr, 1);
        return b == null ? -1 : b.data[(int) (addr - b.start)] & 0xFF;
    }

    public int u16(long addr) {
        Block b = block(addr, 2);
        if (b == null) {
            return -1;
        }
        int o = (int) (addr - b.start);
        return (b.data[o] & 0xFF) | ((b.data[o + 1] & 0xFF) << 8);
    }

    /** Big-endian 16-bit value (Game Boy games). */
    public int u16be(long addr) {
        Block b = block(addr, 2);
        if (b == null) {
            return -1;
        }
        int o = (int) (addr - b.start);
        return ((b.data[o] & 0xFF) << 8) | (b.data[o + 1] & 0xFF);
    }

    public long u32(long addr) {
        Block b = block(addr, 4);
        if (b == null) {
            return -1;
        }
        int o = (int) (addr - b.start);
        return ((b.data[o] & 0xFFL) | ((b.data[o + 1] & 0xFFL) << 8) | ((b.data[o + 2] & 0xFFL) << 16)
                | ((b.data[o + 3] & 0xFFL) << 24));
    }

    public byte[] read(long addr, int len) {
        Block b = block(addr, len);
        if (b == null) {
            return null;
        }
        byte[] out = new byte[len];
        System.arraycopy(b.data, (int) (addr - b.start), out, 0, len);
        return out;
    }
}
