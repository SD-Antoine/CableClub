package slt.player;

import slt.Log;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Inputs of the game: the buttons pressed in the SLT window (sent as a
 * libretro button mask), an XInput gamepad, and the mouse on the DS touch
 * screen.
 */
final class Controls implements CoreHost.InputSource {

    /** Not a console button: gamepad request for fast-forward (right trigger). */
    static final int FAST_BIT = 1 << 20;

    private volatile int pad;            // -1 = none, 0..3 = XInput controller
    private volatile int fastSpeed = 4;  // fast-forward speed, 0 = no limit
    private volatile int keyMask;
    private volatile int latched;      // buttons pressed since the last frame (even if already released)
    private volatile int frameKeys;
    private volatile int padMask;
    private volatile int pointerX;
    private volatile int pointerY;
    private volatile boolean pointerDown;
    // a touch shorter than one frame still reaches the game (as the keys)
    private volatile boolean pointerLatched;
    private volatile boolean framePointer;
    private final XInput xinput = XInput.load();

    Controls(int pad, int fastSpeed) {
        this.pad = pad;
        this.fastSpeed = Math.max(0, Math.min(16, fastSpeed));
    }

    int pad() {
        return pad;
    }

    void setPad(int index) {
        pad = index;
    }

    int fastSpeed() {
        return fastSpeed;
    }

    void setFastSpeed(int speed) {
        fastSpeed = Math.max(0, Math.min(16, speed));
    }

    /** Buttons held in the window (libretro joypad bits). */
    synchronized void setKeys(int mask) {
        int m = mask & 0xFFFF;
        latched |= m & ~keyMask;
        keyMask = m;
    }

    synchronized void releaseAll() {
        keyMask = 0;
        latched = 0;
        pointerDown = false;
        pointerLatched = false;
    }

    /** Pointer in libretro coordinates (-0x7fff..0x7fff over the whole frame). */
    void pointer(int x, int y, boolean down) {
        pointerX = x;
        pointerY = y;
        if (down) {
            pointerLatched = true;
        }
        pointerDown = down;
    }

    // ------------------------------------------------------- InputSource

    @Override
    public void poll() {
        padMask = pad >= 0 && xinput != null ? Math.max(0, xinput.read(pad)) : 0;
        synchronized (this) {
            // a tap shorter than one frame still reaches the game
            frameKeys = keyMask | latched;
            latched = 0;
            framePointer = pointerDown || pointerLatched;
            pointerLatched = false;
        }
    }

    @Override
    public int buttons() {
        return (frameKeys | padMask) & ~FAST_BIT;
    }

    boolean padWantsFastForward() {
        return (padMask & FAST_BIT) != 0;
    }

    @Override
    public int pointerX() {
        return pointerX;
    }

    @Override
    public int pointerY() {
        return pointerY;
    }

    @Override
    public boolean pointerPressed() {
        return framePointer;
    }

    // ------------------------------------------------------------- XInput

    /** Xbox-compatible controllers through Windows' xinput1_4.dll. */
    static final class XInput {
        private final MethodHandle getState;
        private final MemorySegment state;

        private XInput(MethodHandle getState, MemorySegment state) {
            this.getState = getState;
            this.state = state;
        }

        static XInput load() {
            for (String name : new String[]{"xinput1_4", "xinput9_1_0", "xinput1_3"}) {
                try {
                    Arena arena = Arena.global();
                    SymbolLookup lib = SymbolLookup.libraryLookup(name, arena);
                    MethodHandle h = Linker.nativeLinker().downcallHandle(lib.find("XInputGetState").orElseThrow(),
                            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
                    return new XInput(h, arena.allocate(16, 4));
                } catch (Throwable ignored) {
                    // try the next DLL name
                }
            }
            Log.warn("XInput indisponible : manettes désactivées");
            return null;
        }

        /** @return the libretro button mask, or -1 if the controller is not connected */
        synchronized int read(int index) {
            int rc;
            try {
                rc = (int) getState.invokeExact(index, state);
            } catch (Throwable t) {
                return -1;
            }
            if (rc != 0) {
                return -1;
            }
            int w = state.get(JAVA_SHORT, 4) & 0xFFFF;
            int lt = state.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 6) & 0xFF;
            int rt = state.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 7) & 0xFF;
            int lx = state.get(JAVA_SHORT, 8);
            int ly = state.get(JAVA_SHORT, 10);
            int m = 0;
            if ((w & 0x0001) != 0 || ly > 16000) {
                m |= 1 << Retro.JOY_UP;
            }
            if ((w & 0x0002) != 0 || ly < -16000) {
                m |= 1 << Retro.JOY_DOWN;
            }
            if ((w & 0x0004) != 0 || lx < -16000) {
                m |= 1 << Retro.JOY_LEFT;
            }
            if ((w & 0x0008) != 0 || lx > 16000) {
                m |= 1 << Retro.JOY_RIGHT;
            }
            if ((w & 0x0010) != 0) {
                m |= 1 << Retro.JOY_START;
            }
            if ((w & 0x0020) != 0) {
                m |= 1 << Retro.JOY_SELECT;
            }
            if ((w & 0x0100) != 0) {
                m |= 1 << Retro.JOY_L;
            }
            if ((w & 0x0200) != 0) {
                m |= 1 << Retro.JOY_R;
            }
            // right trigger: fast-forward while held
            if (rt > 100) {
                m |= FAST_BIT;
            }
            // Nintendo layout: the button at the same position, not the same letter
            if ((w & 0x1000) != 0) {
                m |= 1 << Retro.JOY_B;
            }
            if ((w & 0x2000) != 0) {
                m |= 1 << Retro.JOY_A;
            }
            if ((w & 0x4000) != 0) {
                m |= 1 << Retro.JOY_Y;
            }
            if ((w & 0x8000) != 0) {
                m |= 1 << Retro.JOY_X;
            }
            return m;
        }
    }
}
