package slt.player;

import slt.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A minimal libretro frontend on Android: the same class as on the PC for the rest of the game (image, sound,
 * save data, save states, emulated memory), the core being driven by the native library "cclibretro" (the PC
 * calls the core from Java through the foreign function API, which Android does not have).
 */
final class CoreHost implements AutoCloseable {

    static {
        System.loadLibrary("cclibretro");
    }

    /** Supplies the controller state polled by the core. */
    interface InputSource {
        void poll();

        int buttons();

        int pointerX();

        int pointerY();

        boolean pointerPressed();
    }

    /** A block of emulated memory exposed by the core (SET_MEMORY_MAPS). */
    record Region(long start, long length, ByteBuffer data) {
    }

    private static native String nativeOpen(String core, String systemDir, String saveDir, String user, String[] defaults);

    private static native String[] nativeInfo();

    private static native String nativeLoad(String path, boolean bytes);

    private static native double[] nativeAv();

    private static native void nativeInput(int buttons, int x, int y, boolean down, boolean fast);

    private static native int nativeRun();

    private static native void nativeReset();

    private static native int nativePixels(int[] out);

    private static native int nativeAudio(short[] out);

    private static native String nativeMessage();

    private static native ByteBuffer nativeMemory(int which);

    private static native long[] nativeRegions();

    private static native ByteBuffer nativeRegion(int index);

    private static native byte[] nativeSaveState();

    private static native boolean nativeLoadState(byte[] state);

    private static native String[] nativeVariables();

    private static native void nativeSetVariable(String key, String value);

    private static native void nativeClose(boolean loaded);

    private static final int MEMORY_SAVE_RAM = 0;
    private static final int MEMORY_SYSTEM_RAM = 2;

    private final Path saveDir;
    private final InputSource input;

    final String libraryName;
    final String libraryVersion;
    final boolean needFullPath;

    private final Map<String, String> values = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String[]> choices = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();

    private int[] pixels = new int[0];
    private int frameW;
    private int frameH;
    private boolean frameReady;
    private short[] audio = new short[16384];
    private int audioLen;

    double fps = 60;
    double sampleRate = 44100;
    int baseWidth = 240;
    int baseHeight = 160;
    double aspect = 1.5;

    volatile boolean fastForwarding;
    volatile boolean shutdownRequested;
    volatile String lastMessage;

    private final List<Region> regions = new ArrayList<>();
    private Path sramFile;
    private volatile boolean stateLoaded;
    private long lastSramCrc = -1;
    private boolean loaded;

    CoreHost(Path core, Path saveDir, Path systemDir, String username, Map<String, String> overrides, InputSource input) throws IOException {
        Files.createDirectories(saveDir);
        Files.createDirectories(systemDir);
        this.saveDir = saveDir;
        this.input = input;
        List<String> defaults = new ArrayList<>();
        overrides.forEach((k, v) -> defaults.add(k + "=" + v));
        String error = nativeOpen(core.toAbsolutePath().toString(), systemDir.toAbsolutePath().toString(),
                saveDir.toAbsolutePath().toString(), username == null || username.isBlank() ? "Dresseur" : username,
                defaults.toArray(new String[0]));
        if (error != null) {
            throw new IOException("Cœur d'émulation inutilisable (" + core.getFileName() + ") : " + error);
        }
        String[] info = nativeInfo();
        libraryName = info[0];
        libraryVersion = info[1];
        needFullPath = "1".equals(info[2]);
    }

    // ------------------------------------------------------------------ game

    void load(Path rom) throws IOException {
        Path romPath = rom.toAbsolutePath();
        String error = nativeLoad(romPath.toString(), !needFullPath);
        if (error != null) {
            throw new IOException("Le cœur " + libraryName + " n'a pas pu charger ce jeu (" + error + ").");
        }
        loaded = true;
        readAv();
        readVariables();
        readRegions();
        Log.info("Options du cœur : " + values);
        String base = romPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        sramFile = saveDir.resolve(base + ".srm");
        loadSram();
    }

    private void readAv() {
        double[] av = nativeAv();
        fps = av[0];
        sampleRate = av[1];
        baseWidth = (int) av[2];
        baseHeight = (int) av[3];
        aspect = av[4];
    }

    private void readVariables() {
        String[] v = nativeVariables();
        for (int i = 0; i + 3 < v.length; i += 4) {
            labels.put(v[i], v[i + 1]);
            choices.put(v[i], v[i + 3].split("\\|"));
            values.putIfAbsent(v[i], v[i + 2]);
        }
    }

    private void readRegions() {
        regions.clear();
        long[] r = nativeRegions();
        for (int i = 0; i + 1 < r.length; i += 2) {
            ByteBuffer b = nativeRegion(i / 2);
            if (b != null) {
                regions.add(new Region(r[i], r[i + 1], b.order(ByteOrder.LITTLE_ENDIAN)));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Region x : regions) {
            sb.append(' ').append(Long.toHexString(x.start())).append('+').append(Long.toHexString(x.length()));
        }
        Log.info("Mémoire du cœur :" + sb);
    }

    void runFrame() {
        input.poll();
        nativeInput(input.buttons(), input.pointerX(), input.pointerY(), input.pointerPressed(), fastForwarding);
        int r = nativeRun();
        frameReady = (r & 1) != 0;
        if ((r & 2) != 0) {
            readAv();
        }
        if ((r & 4) != 0) {
            shutdownRequested = true;
        }
        if ((r & 8) != 0) {
            readRegions();
        }
        if (frameReady) {
            int size = nativePixels(pixels);
            int w = size >>> 16;
            int h = size & 0xFFFF;
            if (pixels.length < w * h) {
                pixels = new int[w * h];
                nativePixels(pixels);
            }
            frameW = w;
            frameH = h;
        }
        int n = nativeAudio(audio);
        if (n < 0) {
            audio = new short[-n * 2];
            n = nativeAudio(audio);
        }
        audioLen = Math.max(0, n);
        String m = nativeMessage();
        if (m != null) {
            lastMessage = m;
        }
    }

    void reset() {
        nativeReset();
    }

    boolean frameReady() {
        return frameReady;
    }

    int[] pixels() {
        return pixels;
    }

    int frameWidth() {
        return frameW;
    }

    int frameHeight() {
        return frameH;
    }

    short[] audio() {
        return audio;
    }

    int audioLength() {
        return audioLen;
    }

    // ------------------------------------------------------------ save data

    /** Cartridge RAM (battery save), or null. */
    ByteBuffer saveRam() {
        ByteBuffer b = nativeMemory(MEMORY_SAVE_RAM);
        return b == null ? null : b.order(ByteOrder.LITTLE_ENDIAN);
    }

    private void loadSram() throws IOException {
        ByteBuffer s = saveRam();
        if (s == null || !Files.isRegularFile(sramFile)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(sramFile);
        int n = Math.min(bytes.length, s.capacity());
        Ram.put(s, 0, bytes, 0, n);
        lastSramCrc = crc(Ram.bytes(s));
    }

    /** Writes the cartridge save when it changed. @return true if written */
    boolean saveSram() {
        ByteBuffer s = saveRam();
        if (s == null || sramFile == null) {
            return false;
        }
        byte[] bytes = Ram.bytes(s);
        long c = crc(bytes);
        if (c == lastSramCrc) {
            return false;
        }
        try {
            Path tmp = sramFile.resolveSibling(sramFile.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, sramFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastSramCrc = c;
            return true;
        } catch (IOException e) {
            Log.warn("Sauvegarde impossible : " + e.getMessage());
            return false;
        }
    }

    private static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    byte[] saveState() {
        return nativeSaveState();
    }

    boolean loadState(byte[] state) {
        boolean ok = nativeLoadState(state);
        if (ok) {
            stateLoaded = true;
        }
        return ok;
    }

    /** Whether the cartridge RAM shown (saveRam) is the one the game uses (see the PC version). */
    boolean cartridgeRamLive() {
        return !stateLoaded;
    }

    // --------------------------------------------------------------- memory

    /** Main RAM as exposed by retro_get_memory_data(SYSTEM_RAM), or null. */
    ByteBuffer systemRam() {
        ByteBuffer b = nativeMemory(MEMORY_SYSTEM_RAM);
        return b == null ? null : b.order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Memory regions declared by the core, addressed like the console bus. */
    List<Region> regions() {
        return regions;
    }

    /** Memory covering [address, address+len) of the emulated bus, or null. */
    ByteBuffer bus(long address, long len) {
        for (Region r : regions) {
            if (address >= r.start() && address + len <= r.start() + r.length()) {
                return Ram.slice(r.data(), address - r.start(), (int) len);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ options

    Map<String, String> options() {
        return values;
    }

    Map<String, String[]> optionChoices() {
        return choices;
    }

    String optionLabel(String key) {
        return labels.getOrDefault(key, key);
    }

    void setOption(String key, String value) {
        values.put(key, value);
        nativeSetVariable(key, value);
    }

    @Override
    public void close() {
        try {
            if (loaded) {
                saveSram();
            }
            nativeClose(loaded);
        } finally {
            loaded = false;
        }
    }

    String describe() {
        return libraryName + " " + libraryVersion + " — " + baseWidth + "x" + baseHeight + " @ "
                + String.format(java.util.Locale.ROOT, "%.2f", fps) + " i/s, son " + (int) sampleRate + " Hz";
    }
}
