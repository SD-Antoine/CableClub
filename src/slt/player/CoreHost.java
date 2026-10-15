package slt.player;

import slt.Log;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

/**
 * A minimal libretro frontend: implements the callbacks a core needs
 * (environment, video, audio, input) and exposes frames, audio samples,
 * save data, save states and the emulated memory.
 */
final class CoreHost implements AutoCloseable {

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

    private final Arena arena = Arena.ofShared();
    private final Retro retro;
    private final Path saveDir;
    private final InputSource input;
    private final Map<String, String> overrides;

    final String libraryName;
    final String libraryVersion;
    final boolean needFullPath;

    // read by the emulation thread, edited from the options dialog
    private final Map<String, String> values = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String[]> choices = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Map<String, MemorySegment> cStrings = new HashMap<>();
    private volatile boolean variablesDirty;
    private final MemorySegment cSystemDir;
    private final MemorySegment cSaveDir;
    private final MemorySegment cUser;
    private final MemorySegment logInterface;
    private final MemorySegment perfInterface;
    private static final boolean TRACE_ENV = Boolean.getBoolean("slt.traceEnv");

    private int pixelFormat = Retro.PIXEL_0RGB1555;
    private int[] pixels = new int[0];
    private short[] rowBuffer = new short[0];
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
    private Path romPath;
    private Path sramFile;
    private volatile boolean stateLoaded;
    private long lastSramCrc = -1;
    private boolean loaded;

    CoreHost(Path dll, Path saveDir, Path systemDir, String username, Map<String, String> overrides, InputSource input) throws IOException {
        Files.createDirectories(saveDir);
        Files.createDirectories(systemDir);
        this.saveDir = saveDir;
        this.input = input;
        this.overrides = overrides;
        this.retro = new Retro(dll, arena);
        this.cSystemDir = arena.allocateUtf8String(systemDir.toAbsolutePath().toString());
        this.cSaveDir = arena.allocateUtf8String(saveDir.toAbsolutePath().toString());
        this.cUser = arena.allocateUtf8String(username == null || username.isBlank() ? "Dresseur" : username);

        MemorySegment info = arena.allocate(32);
        invoke(() -> { retro.getSystemInfo.invokeExact(info); });
        libraryName = cstr(info.get(ADDRESS, 0));
        libraryVersion = cstr(info.get(ADDRESS, 8));
        needFullPath = info.get(JAVA_BOOLEAN, 24);

        try {
            MethodHandles.Lookup lk = MethodHandles.lookup();
            MemorySegment env = retro.upcall(lk.findVirtual(CoreHost.class, "env",
                    MethodType.methodType(boolean.class, int.class, MemorySegment.class)).bindTo(this), Retro.ENV_FN, arena);
            MemorySegment video = retro.upcall(lk.findVirtual(CoreHost.class, "video",
                    MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, long.class)).bindTo(this), Retro.VIDEO_FN, arena);
            MemorySegment sample = retro.upcall(lk.findVirtual(CoreHost.class, "audioSample",
                    MethodType.methodType(void.class, short.class, short.class)).bindTo(this), Retro.AUDIO_FN, arena);
            MemorySegment batch = retro.upcall(lk.findVirtual(CoreHost.class, "audioBatch",
                    MethodType.methodType(long.class, MemorySegment.class, long.class)).bindTo(this), Retro.AUDIO_BATCH_FN, arena);
            MemorySegment poll = retro.upcall(lk.findVirtual(CoreHost.class, "inputPoll",
                    MethodType.methodType(void.class)).bindTo(this), Retro.INPUT_POLL_FN, arena);
            MemorySegment state = retro.upcall(lk.findVirtual(CoreHost.class, "inputState",
                    MethodType.methodType(short.class, int.class, int.class, int.class, int.class)).bindTo(this), Retro.INPUT_STATE_FN, arena);
            // struct retro_log_callback { retro_log_printf_t log; }
            logInterface = arena.allocate(8, 8);
            logInterface.set(ADDRESS, 0, retro.upcall(lk.findVirtual(CoreHost.class, "coreLog",
                    MethodType.methodType(void.class, int.class, MemorySegment.class)).bindTo(this), Retro.LOG_FN, arena));
            // struct retro_perf_callback: 7 function pointers
            MemorySegment time = retro.upcall(lk.findStatic(CoreHost.class, "perfTime",
                    MethodType.methodType(long.class)), Retro.PERF_TIME_FN, arena);
            MemorySegment features = retro.upcall(lk.findStatic(CoreHost.class, "cpuFeatures",
                    MethodType.methodType(long.class)), Retro.PERF_TIME_FN, arena);
            MemorySegment counter = retro.upcall(lk.findStatic(CoreHost.class, "perfCounter",
                    MethodType.methodType(long.class)), Retro.PERF_TIME_FN, arena);
            MemorySegment noop = retro.upcall(lk.findStatic(CoreHost.class, "perfNoop",
                    MethodType.methodType(void.class, MemorySegment.class)), Retro.PERF_COUNTER_FN, arena);
            MemorySegment perfLog = retro.upcall(lk.findStatic(CoreHost.class, "perfLog",
                    MethodType.methodType(void.class)), Retro.PERF_LOG_FN, arena);
            perfInterface = arena.allocate(56, 8);
            MemorySegment[] perf = {time, features, counter, noop, noop, noop, perfLog};
            for (int i = 0; i < perf.length; i++) {
                perfInterface.set(ADDRESS, 8L * i, perf[i]);
            }
            invoke(() -> { retro.setEnvironment.invokeExact(env); });
            invoke(() -> { retro.setVideoRefresh.invokeExact(video); });
            invoke(() -> { retro.setAudioSample.invokeExact(sample); });
            invoke(() -> { retro.setAudioSampleBatch.invokeExact(batch); });
            invoke(() -> { retro.setInputPoll.invokeExact(poll); });
            invoke(() -> { retro.setInputState.invokeExact(state); });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        invoke(() -> { retro.init.invokeExact(); });
    }

    // ------------------------------------------------------------------ game

    void load(Path rom) throws IOException {
        romPath = rom.toAbsolutePath();
        MemorySegment gameInfo = arena.allocate(32);
        gameInfo.set(ADDRESS, 0, arena.allocateUtf8String(romPath.toString()));
        if (!needFullPath) {
            byte[] bytes = Files.readAllBytes(romPath);
            MemorySegment data = arena.allocate(bytes.length, 16);
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
            gameInfo.set(ADDRESS, 8, data);
            gameInfo.set(JAVA_LONG, 16, bytes.length);
        } else {
            gameInfo.set(ADDRESS, 8, MemorySegment.NULL);
            gameInfo.set(JAVA_LONG, 16, 0L);
        }
        gameInfo.set(ADDRESS, 24, MemorySegment.NULL);
        boolean[] ok = {false};
        invoke(() -> ok[0] = (boolean) retro.loadGame.invokeExact(gameInfo));
        if (!ok[0]) {
            throw new IOException("Le cœur " + libraryName + " n'a pas pu charger ce jeu.");
        }
        loaded = true;
        MemorySegment av = arena.allocate(40, 8);
        invoke(() -> { retro.getAvInfo.invokeExact(av); });
        readAvInfo(av);
        invoke(() -> { retro.setControllerPortDevice.invokeExact(0, Retro.DEVICE_JOYPAD); });
        String base = romPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        sramFile = saveDir.resolve(base + ".srm");
        loadSram();
    }

    void runFrame() {
        audioLen = 0;
        frameReady = false;
        invoke(() -> { retro.run.invokeExact(); });
    }

    void reset() {
        invoke(() -> { retro.reset.invokeExact(); });
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
        return buffer(sram());
    }

    /** The core's memory seen as a little-endian buffer (the readers are the same on Android). */
    private static ByteBuffer buffer(MemorySegment s) {
        return s == null ? null : s.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private MemorySegment sram() {
        long[] size = {0};
        invoke(() -> size[0] = (long) retro.getMemorySize.invokeExact(Retro.MEMORY_SAVE_RAM));
        if (size[0] <= 0) {
            return null;
        }
        MemorySegment[] data = {MemorySegment.NULL};
        invoke(() -> data[0] = (MemorySegment) retro.getMemoryData.invokeExact(Retro.MEMORY_SAVE_RAM));
        return data[0].address() == 0 ? null : data[0].reinterpret(size[0]);
    }

    private void loadSram() throws IOException {
        MemorySegment s = sram();
        if (s == null || !Files.isRegularFile(sramFile)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(sramFile);
        int n = (int) Math.min(bytes.length, s.byteSize());
        MemorySegment.copy(bytes, 0, s, JAVA_BYTE, 0, n);
        lastSramCrc = crc(s.toArray(JAVA_BYTE));
    }

    /** Writes the cartridge save when it changed. @return true if written */
    boolean saveSram() {
        MemorySegment s = sram();
        if (s == null || sramFile == null) {
            return false;
        }
        byte[] bytes = s.toArray(JAVA_BYTE);
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
        long[] size = {0};
        invoke(() -> size[0] = (long) retro.serializeSize.invokeExact());
        if (size[0] <= 0) {
            return null;
        }
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment buf = tmp.allocate(size[0], 16);
            boolean[] ok = {false};
            invoke(() -> ok[0] = (boolean) retro.serialize.invokeExact(buf, size[0]));
            return ok[0] ? buf.toArray(JAVA_BYTE) : null;
        }
    }

    boolean loadState(byte[] state) {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment buf = tmp.allocate(state.length, 16);
            MemorySegment.copy(state, 0, buf, JAVA_BYTE, 0, state.length);
            boolean[] ok = {false};
            invoke(() -> ok[0] = (boolean) retro.unserialize.invokeExact(buf, (long) state.length));
            if (ok[0]) {
                stateLoaded = true;
            }
            return ok[0];
        }
    }

    /**
     * Whether the cartridge RAM shown (saveRam) is the one the game uses: mGBA maps it from the
     * save buffer, but a loaded save state puts the game on a copy of it (savedataRestore masks it).
     */
    boolean cartridgeRamLive() {
        return !stateLoaded;
    }

    // --------------------------------------------------------------- memory

    /** Main RAM as exposed by retro_get_memory_data(SYSTEM_RAM), or null. */
    ByteBuffer systemRam() {
        long[] size = {0};
        invoke(() -> size[0] = (long) retro.getMemorySize.invokeExact(Retro.MEMORY_SYSTEM_RAM));
        if (size[0] <= 0) {
            return null;
        }
        MemorySegment[] data = {MemorySegment.NULL};
        invoke(() -> data[0] = (MemorySegment) retro.getMemoryData.invokeExact(Retro.MEMORY_SYSTEM_RAM));
        return data[0].address() == 0 ? null : buffer(data[0].reinterpret(size[0]));
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
        variablesDirty = true;
    }

    // ------------------------------------------------------------ callbacks

    @SuppressWarnings("unused")
    private boolean env(int cmd, MemorySegment data) {
        try {
            int c = cmd & 0xFFFF;
            boolean hasData = data.address() != 0;
            if (TRACE_ENV) {
                Log.info("env " + c + (cmd != c ? " (exp)" : ""));
            }
            switch (c) {
                case Retro.ENV_GET_LOG_INTERFACE:
                    MemorySegment.copy(logInterface, 0, data.reinterpret(8), 0, 8);
                    return true;
                case Retro.ENV_GET_PERF_INTERFACE:
                    MemorySegment.copy(perfInterface, 0, data.reinterpret(56), 0, 56);
                    return true;
                case Retro.ENV_GET_CAN_DUPE:
                    if (hasData) {
                        data.reinterpret(1).set(JAVA_BOOLEAN, 0, true);
                    }
                    return true;
                case Retro.ENV_SET_MESSAGE:
                    if (hasData) {
                        lastMessage = cstr(data.reinterpret(16).get(ADDRESS, 0));
                    }
                    return true;
                case Retro.ENV_SHUTDOWN:
                    shutdownRequested = true;
                    return true;
                case Retro.ENV_GET_SYSTEM_DIRECTORY:
                case Retro.ENV_GET_CORE_ASSETS_DIRECTORY:
                    data.reinterpret(8).set(ADDRESS, 0, cSystemDir);
                    return true;
                case Retro.ENV_GET_SAVE_DIRECTORY:
                    data.reinterpret(8).set(ADDRESS, 0, cSaveDir);
                    return true;
                case Retro.ENV_GET_USERNAME:
                    data.reinterpret(8).set(ADDRESS, 0, cUser);
                    return true;
                case Retro.ENV_SET_PIXEL_FORMAT: {
                    int f = data.reinterpret(4).get(JAVA_INT, 0);
                    if (f >= 0 && f <= 2) {
                        pixelFormat = f;
                        return true;
                    }
                    return false;
                }
                case Retro.ENV_SET_INPUT_DESCRIPTORS:
                case Retro.ENV_SET_CONTROLLER_INFO:
                case Retro.ENV_SET_SUPPORT_ACHIEVEMENTS:
                case Retro.ENV_SET_SERIALIZATION_QUIRKS:
                case Retro.ENV_SET_MINIMUM_AUDIO_LATENCY:
                case Retro.ENV_SET_SUPPORT_NO_GAME:
                    return true;
                case Retro.ENV_GET_VARIABLE: {
                    MemorySegment v = data.reinterpret(16);
                    String key = cstr(v.get(ADDRESS, 0));
                    String val = values.get(key);
                    if (val == null) {
                        v.set(ADDRESS, 8, MemorySegment.NULL);
                        return false;
                    }
                    v.set(ADDRESS, 8, cString(val));
                    return true;
                }
                case Retro.ENV_SET_VARIABLES:
                    readVariables(data);
                    return true;
                case Retro.ENV_SET_VARIABLE: {
                    if (!hasData) {
                        return true;
                    }
                    MemorySegment v = data.reinterpret(16);
                    String key = cstr(v.get(ADDRESS, 0));
                    String val = cstr(v.get(ADDRESS, 8));
                    if (key != null && val != null) {
                        values.put(key, val);
                    }
                    return true;
                }
                case Retro.ENV_GET_VARIABLE_UPDATE:
                    data.reinterpret(1).set(JAVA_BOOLEAN, 0, variablesDirty);
                    variablesDirty = false;
                    return true;
                case Retro.ENV_GET_INPUT_DEVICE_CAPABILITIES:
                    data.reinterpret(8).set(JAVA_LONG, 0, (1L << Retro.DEVICE_JOYPAD) | (1L << Retro.DEVICE_POINTER));
                    return true;
                case Retro.ENV_SET_SYSTEM_AV_INFO:
                    readAvInfo(data.reinterpret(40));
                    return true;
                case Retro.ENV_SET_GEOMETRY:
                    readGeometry(data.reinterpret(20));
                    return true;
                case Retro.ENV_SET_MEMORY_MAPS:
                    readMemoryMaps(data);
                    return true;
                case Retro.ENV_GET_LANGUAGE:
                    data.reinterpret(4).set(JAVA_INT, 0, Retro.LANGUAGE_FRENCH);
                    return true;
                case Retro.ENV_GET_AUDIO_VIDEO_ENABLE:
                    if (hasData) {
                        data.reinterpret(4).set(JAVA_INT, 0, 3);
                    }
                    return true;
                case Retro.ENV_GET_FASTFORWARDING:
                    if (hasData) {
                        data.reinterpret(1).set(JAVA_BOOLEAN, 0, fastForwarding);
                    }
                    return true;
                case Retro.ENV_GET_TARGET_REFRESH_RATE:
                    data.reinterpret(4).set(JAVA_FLOAT, 0, 60f);
                    return true;
                case Retro.ENV_GET_INPUT_BITMASKS:
                    if (hasData) {
                        data.reinterpret(1).set(JAVA_BOOLEAN, 0, true);
                    }
                    return true;
                case Retro.ENV_GET_CORE_OPTIONS_VERSION:
                case Retro.ENV_GET_MESSAGE_INTERFACE_VERSION:
                case Retro.ENV_GET_SAVESTATE_CONTEXT:
                    data.reinterpret(4).set(JAVA_INT, 0, 0);
                    return true;
                case Retro.ENV_GET_INPUT_MAX_USERS:
                    data.reinterpret(4).set(JAVA_INT, 0, 1);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.error("Erreur dans l'environnement libretro (commande " + cmd + ")", t);
            return false;
        }
    }

    private void readVariables(MemorySegment data) {
        MemorySegment arr = data.reinterpret(Long.MAX_VALUE);
        for (long off = 0; ; off += 16) {
            MemorySegment keyPtr = arr.get(ADDRESS, off);
            if (keyPtr.address() == 0) {
                break;
            }
            String key = cstr(keyPtr);
            String spec = cstr(arr.get(ADDRESS, off + 8));
            if (key == null || spec == null) {
                continue;
            }
            int semi = spec.indexOf(';');
            String label = semi >= 0 ? spec.substring(0, semi).trim() : key;
            String[] opts = (semi >= 0 ? spec.substring(semi + 1) : spec).trim().split("\\|");
            labels.put(key, label);
            choices.put(key, opts);
            String wanted = overrides.get(key);
            String value = opts.length > 0 ? opts[0] : "";
            if (wanted != null) {
                for (String o : opts) {
                    if (o.equals(wanted)) {
                        value = wanted;
                        break;
                    }
                }
            }
            values.putIfAbsent(key, value);
        }
    }

    private void readAvInfo(MemorySegment av) {
        readGeometry(av);
        double f = av.get(JAVA_DOUBLE, 24);
        double r = av.get(JAVA_DOUBLE, 32);
        if (f > 1 && f < 300) {
            fps = f;
        }
        if (r > 1000) {
            sampleRate = r;
        }
    }

    private void readGeometry(MemorySegment g) {
        int w = g.get(JAVA_INT, 0);
        int h = g.get(JAVA_INT, 4);
        float a = g.get(JAVA_FLOAT, 16);
        if (w > 0 && h > 0) {
            baseWidth = w;
            baseHeight = h;
            aspect = a > 0 ? a : (double) w / h;
        }
    }

    private void readMemoryMaps(MemorySegment data) {
        MemorySegment map = data.reinterpret(16);
        MemorySegment descs = map.get(ADDRESS, 0);
        int n = map.get(JAVA_INT, 8);
        if (descs.address() == 0 || n <= 0) {
            return;
        }
        MemorySegment all = descs.reinterpret(64L * n);
        regions.clear();
        for (int i = 0; i < n; i++) {
            long base = 64L * i;
            MemorySegment ptr = all.get(ADDRESS, base + 8);
            long offset = all.get(JAVA_LONG, base + 16);
            long start = all.get(JAVA_LONG, base + 24);
            long len = all.get(JAVA_LONG, base + 48);
            if (ptr.address() != 0 && len > 0) {
                regions.add(new Region(start, len, buffer(MemorySegment.ofAddress(ptr.address() + offset).reinterpret(len))));
            }
        }
    }

    @SuppressWarnings("unused")
    private void video(MemorySegment data, int w, int h, long pitch) {
        try {
            if (data.address() == 0 || data.address() == -1L || w <= 0 || h <= 0) {
                return;
            }
            if (pixels.length != w * h) {
                pixels = new int[w * h];
            }
            MemorySegment src = data.reinterpret(pitch * h);
            if (pixelFormat == Retro.PIXEL_XRGB8888) {
                for (int y = 0; y < h; y++) {
                    MemorySegment.copy(src, JAVA_INT_UNALIGNED, y * pitch, pixels, y * w, w);
                }
            } else {
                if (rowBuffer.length < w) {
                    rowBuffer = new short[w];
                }
                boolean rgb565 = pixelFormat == Retro.PIXEL_RGB565;
                for (int y = 0; y < h; y++) {
                    MemorySegment.copy(src, JAVA_SHORT_UNALIGNED, y * pitch, rowBuffer, 0, w);
                    int o = y * w;
                    for (int x = 0; x < w; x++) {
                        int p = rowBuffer[x] & 0xFFFF;
                        int r;
                        int g;
                        int b;
                        if (rgb565) {
                            r = (p >> 11) & 0x1F;
                            g = (p >> 5) & 0x3F;
                            b = p & 0x1F;
                            g = (g << 2) | (g >> 4);
                        } else {
                            r = (p >> 10) & 0x1F;
                            g = (p >> 5) & 0x1F;
                            b = p & 0x1F;
                            g = (g << 3) | (g >> 2);
                        }
                        r = (r << 3) | (r >> 2);
                        b = (b << 3) | (b >> 2);
                        pixels[o + x] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            frameW = w;
            frameH = h;
            frameReady = true;
        } catch (Throwable t) {
            Log.error("Erreur d'affichage", t);
        }
    }

    @SuppressWarnings("unused")
    private void coreLog(int level, MemorySegment fmt) {
        try {
            if (level >= 2) { // warnings and errors only
                String text = cstr(fmt);
                if (text != null) {
                    Log.info("[" + libraryName + "] " + text.strip());
                }
            }
        } catch (Throwable ignored) {
            // never let a log line break the emulation
        }
    }

    @SuppressWarnings("unused")
    private static long perfTime() {
        return System.nanoTime() / 1000;
    }

    @SuppressWarnings("unused")
    private static long cpuFeatures() {
        return 0;
    }

    @SuppressWarnings("unused")
    private static long perfCounter() {
        return System.nanoTime();
    }

    @SuppressWarnings("unused")
    private static void perfNoop(MemorySegment counter) {
        // performance counters are not collected
    }

    @SuppressWarnings("unused")
    private static void perfLog() {
        // nothing to report
    }

    @SuppressWarnings("unused")
    private void audioSample(short left, short right) {
        ensureAudio(2);
        audio[audioLen++] = left;
        audio[audioLen++] = right;
    }

    @SuppressWarnings("unused")
    private long audioBatch(MemorySegment data, long frames) {
        try {
            int n = (int) frames * 2;
            ensureAudio(n);
            MemorySegment.copy(data.reinterpret(frames * 4), JAVA_SHORT_UNALIGNED, 0, audio, audioLen, n);
            audioLen += n;
        } catch (Throwable t) {
            Log.error("Erreur audio", t);
        }
        return frames;
    }

    private void ensureAudio(int extra) {
        if (audioLen + extra > audio.length) {
            short[] bigger = new short[Math.max(audio.length * 2, audioLen + extra)];
            System.arraycopy(audio, 0, bigger, 0, audioLen);
            audio = bigger;
        }
    }

    @SuppressWarnings("unused")
    private void inputPoll() {
        try {
            input.poll();
        } catch (Throwable t) {
            Log.error("Erreur d'entrée", t);
        }
    }

    @SuppressWarnings("unused")
    private short inputState(int port, int device, int index, int id) {
        try {
            if (port != 0) {
                return 0;
            }
            int dev = device & 0xFF;
            if (dev == Retro.DEVICE_JOYPAD) {
                int mask = input.buttons();
                if (id == Retro.JOY_MASK) {
                    return (short) mask;
                }
                return (short) ((mask >> id) & 1);
            }
            if (dev == Retro.DEVICE_POINTER) {
                switch (id) {
                    case Retro.POINTER_X:
                        return (short) input.pointerX();
                    case Retro.POINTER_Y:
                        return (short) input.pointerY();
                    case Retro.POINTER_PRESSED:
                        return (short) (input.pointerPressed() ? 1 : 0);
                    default:
                        return 0;
                }
            }
        } catch (Throwable t) {
            Log.error("Erreur d'entrée", t);
        }
        return 0;
    }

    // -------------------------------------------------------------- helpers

    private MemorySegment cString(String s) {
        return cStrings.computeIfAbsent(s, k -> arena.allocateUtf8String(k));
    }

    private static String cstr(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0) {
            return null;
        }
        return ptr.reinterpret(Integer.MAX_VALUE).getUtf8String(0);
    }

    @FunctionalInterface
    private interface NativeCall {
        void call() throws Throwable;
    }

    private static void invoke(NativeCall c) {
        try {
            c.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public void close() {
        try {
            if (loaded) {
                saveSram();
                invoke(() -> { retro.unloadGame.invokeExact(); });
            }
            invoke(() -> { retro.deinit.invokeExact(); });
        } finally {
            loaded = false;
        }
        // the arena is intentionally kept: the core DLL stays mapped until the process exits
    }

    String describe() {
        return libraryName + " " + libraryVersion + " — " + baseWidth + "x" + baseHeight + " @ "
                + String.format(java.util.Locale.ROOT, "%.2f", fps) + " i/s, son " + (int) sampleRate + " Hz";
    }
}
