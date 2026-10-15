package slt.player;

/**
 * Inputs of the game on the phone: the buttons pressed in the window (touch controls, keyboard or gamepad, all
 * read by the interface and sent as a libretro button mask) and the finger on the DS touch screen. The PC version
 * also reads XInput gamepads itself.
 */
final class Controls implements CoreHost.InputSource {

    /** Not a console button: request for fast-forward. */
    static final int FAST_BIT = 1 << 20;

    private volatile int pad;
    private volatile int fastSpeed = 4;  // fast-forward speed, 0 = no limit
    private volatile int keyMask;
    private volatile int latched;      // buttons pressed since the last frame (even if already released)
    private volatile int frameKeys;
    private volatile int pointerX;
    private volatile int pointerY;
    private volatile boolean pointerDown;
    // a touch shorter than one frame still reaches the game (as the keys)
    private volatile boolean pointerLatched;
    private volatile boolean framePointer;

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

    @Override
    public void poll() {
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
        return frameKeys & ~FAST_BIT;
    }

    boolean padWantsFastForward() {
        return false;
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
}
