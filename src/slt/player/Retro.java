package slt.player;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Bindings to a libretro core DLL (mGBA, DeSmuME…) through the Java
 * foreign function API. Constants mirror libretro.h.
 */
final class Retro {

    static final int DEVICE_JOYPAD = 1;
    static final int DEVICE_POINTER = 6;

    static final int JOY_B = 0, JOY_Y = 1, JOY_SELECT = 2, JOY_START = 3, JOY_UP = 4, JOY_DOWN = 5,
            JOY_LEFT = 6, JOY_RIGHT = 7, JOY_A = 8, JOY_X = 9, JOY_L = 10, JOY_R = 11, JOY_MASK = 256;
    static final int POINTER_X = 0, POINTER_Y = 1, POINTER_PRESSED = 2;

    static final int MEMORY_SAVE_RAM = 0, MEMORY_SYSTEM_RAM = 2;

    static final int PIXEL_0RGB1555 = 0, PIXEL_XRGB8888 = 1, PIXEL_RGB565 = 2;

    // environment commands (without the EXPERIMENTAL / PRIVATE flags)
    static final int ENV_GET_CAN_DUPE = 3, ENV_SET_MESSAGE = 6, ENV_SHUTDOWN = 7, ENV_GET_SYSTEM_DIRECTORY = 9,
            ENV_SET_PIXEL_FORMAT = 10, ENV_SET_INPUT_DESCRIPTORS = 11, ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16,
            ENV_GET_VARIABLE_UPDATE = 17, ENV_SET_SUPPORT_NO_GAME = 18, ENV_GET_INPUT_DEVICE_CAPABILITIES = 24, ENV_GET_LOG_INTERFACE = 27, ENV_GET_PERF_INTERFACE = 28,
            ENV_GET_CORE_ASSETS_DIRECTORY = 30, ENV_GET_SAVE_DIRECTORY = 31, ENV_SET_SYSTEM_AV_INFO = 32,
            ENV_SET_CONTROLLER_INFO = 35, ENV_SET_MEMORY_MAPS = 36, ENV_SET_GEOMETRY = 37, ENV_GET_USERNAME = 38,
            ENV_GET_LANGUAGE = 39, ENV_SET_SUPPORT_ACHIEVEMENTS = 42, ENV_SET_SERIALIZATION_QUIRKS = 44,
            ENV_GET_AUDIO_VIDEO_ENABLE = 47, ENV_GET_FASTFORWARDING = 49, ENV_GET_TARGET_REFRESH_RATE = 50,
            ENV_GET_INPUT_BITMASKS = 51, ENV_GET_CORE_OPTIONS_VERSION = 52, ENV_GET_MESSAGE_INTERFACE_VERSION = 59,
            ENV_GET_INPUT_MAX_USERS = 61, ENV_SET_MINIMUM_AUDIO_LATENCY = 63, ENV_SET_VARIABLE = 70,
            ENV_GET_SAVESTATE_CONTEXT = 72;
    static final int LANGUAGE_FRENCH = 2;

    final MethodHandle init, deinit, run, reset, loadGame, unloadGame, getSystemInfo, getAvInfo,
            serializeSize, serialize, unserialize, getMemoryData, getMemorySize,
            setEnvironment, setVideoRefresh, setAudioSample, setAudioSampleBatch, setInputPoll, setInputState,
            setControllerPortDevice;

    private final SymbolLookup lib;
    private final Linker linker = Linker.nativeLinker();

    Retro(Path dll, Arena arena) {
        lib = SymbolLookup.libraryLookup(dll, arena);
        init = fn("retro_init", FunctionDescriptor.ofVoid());
        deinit = fn("retro_deinit", FunctionDescriptor.ofVoid());
        run = fn("retro_run", FunctionDescriptor.ofVoid());
        reset = fn("retro_reset", FunctionDescriptor.ofVoid());
        loadGame = fn("retro_load_game", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        unloadGame = fn("retro_unload_game", FunctionDescriptor.ofVoid());
        getSystemInfo = fn("retro_get_system_info", FunctionDescriptor.ofVoid(ADDRESS));
        getAvInfo = fn("retro_get_system_av_info", FunctionDescriptor.ofVoid(ADDRESS));
        serializeSize = fn("retro_serialize_size", FunctionDescriptor.of(JAVA_LONG));
        serialize = fn("retro_serialize", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG));
        unserialize = fn("retro_unserialize", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, JAVA_LONG));
        getMemoryData = fn("retro_get_memory_data", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        getMemorySize = fn("retro_get_memory_size", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
        setEnvironment = fn("retro_set_environment", FunctionDescriptor.ofVoid(ADDRESS));
        setVideoRefresh = fn("retro_set_video_refresh", FunctionDescriptor.ofVoid(ADDRESS));
        setAudioSample = fn("retro_set_audio_sample", FunctionDescriptor.ofVoid(ADDRESS));
        setAudioSampleBatch = fn("retro_set_audio_sample_batch", FunctionDescriptor.ofVoid(ADDRESS));
        setInputPoll = fn("retro_set_input_poll", FunctionDescriptor.ofVoid(ADDRESS));
        setInputState = fn("retro_set_input_state", FunctionDescriptor.ofVoid(ADDRESS));
        setControllerPortDevice = fn("retro_set_controller_port_device", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
    }

    private MethodHandle fn(String name, FunctionDescriptor fd) {
        MemorySegment addr = lib.find(name).orElseThrow(() -> new IllegalStateException("Fonction absente du cœur : " + name));
        return linker.downcallHandle(addr, fd);
    }

    // descriptors of the callbacks we hand to the core
    static final FunctionDescriptor ENV_FN = FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, ADDRESS);
    static final FunctionDescriptor VIDEO_FN = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
    static final FunctionDescriptor AUDIO_FN = FunctionDescriptor.ofVoid(JAVA_SHORT, JAVA_SHORT);
    static final FunctionDescriptor AUDIO_BATCH_FN = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG);
    static final FunctionDescriptor INPUT_POLL_FN = FunctionDescriptor.ofVoid();
    static final FunctionDescriptor INPUT_STATE_FN = FunctionDescriptor.of(JAVA_SHORT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    // printf-like log callback: on Windows x64 the callee may ignore the variadic arguments
    static final FunctionDescriptor LOG_FN = FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS);
    static final FunctionDescriptor PERF_TIME_FN = FunctionDescriptor.of(JAVA_LONG);
    static final FunctionDescriptor PERF_COUNTER_FN = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor PERF_LOG_FN = FunctionDescriptor.ofVoid();

    MemorySegment upcall(MethodHandle target, FunctionDescriptor fd, Arena arena) {
        return linker.upcallStub(target, fd, arena);
    }
}
