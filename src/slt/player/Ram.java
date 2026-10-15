package slt.player;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The emulated memory as the readers see it: little-endian byte buffers over the core's own memory (no copy).
 * The same on the PC (over the foreign memory of the core) and on Android (over a direct buffer from JNI).
 */
final class Ram {

    private Ram() {
    }

    /** The bytes [offset, offset+length) of a buffer, as a little-endian buffer of their own (no copy). */
    static ByteBuffer slice(ByteBuffer b, long offset, int length) {
        ByteBuffer d = b.duplicate();
        d.limit((int) offset + length);
        d.position((int) offset);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Copy of the whole buffer. */
    static byte[] bytes(ByteBuffer b) {
        return bytes(b, 0, b.capacity());
    }

    /** Copy of the bytes [offset, offset+length). */
    static byte[] bytes(ByteBuffer b, long offset, int length) {
        byte[] out = new byte[length];
        copy(b, offset, out, 0, length);
        return out;
    }

    static void copy(ByteBuffer from, long offset, byte[] to, int at, int length) {
        ByteBuffer d = from.duplicate();
        d.position((int) offset);
        d.get(to, at, length);
    }

    /** Writes bytes into the memory at an offset. */
    static void put(ByteBuffer to, long offset, byte[] from, int at, int length) {
        ByteBuffer d = to.duplicate();
        d.position((int) offset);
        d.put(from, at, length);
    }
}
