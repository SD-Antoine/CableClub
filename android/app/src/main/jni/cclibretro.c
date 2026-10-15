/*
 * Cable Club on Android: a minimal libretro frontend for the Java class slt.player.CoreHost.
 *
 * The PC version calls the libretro cores (mGBA, DeSmuME) from Java through the foreign function API; Android has
 * none, so this small library does the same work: it loads the core, answers its callbacks (environment, image,
 * sound, buttons, touch screen) and hands Java the image, the sound, the save data, the save states and the
 * emulated memory (as direct buffers: the readers of the game see the core's own memory, without copy).
 *
 * One core per process (the game runs in a process of its own, as on the PC).
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "CableClub"

/* ------------------------------------------------------------------ the part of libretro.h used here */

#define RETRO_DEVICE_JOYPAD 1
#define RETRO_DEVICE_POINTER 6
#define RETRO_DEVICE_ID_JOYPAD_MASK 256
#define RETRO_DEVICE_ID_POINTER_X 0
#define RETRO_DEVICE_ID_POINTER_Y 1
#define RETRO_DEVICE_ID_POINTER_PRESSED 2
#define RETRO_MEMORY_SAVE_RAM 0
#define RETRO_MEMORY_SYSTEM_RAM 2
#define RETRO_HW_FRAME_BUFFER_VALID ((void *) -1)

enum {
    ENV_GET_CAN_DUPE = 3, ENV_SET_MESSAGE = 6, ENV_SHUTDOWN = 7, ENV_GET_SYSTEM_DIRECTORY = 9,
    ENV_SET_PIXEL_FORMAT = 10, ENV_SET_INPUT_DESCRIPTORS = 11, ENV_GET_VARIABLE = 15, ENV_SET_VARIABLES = 16,
    ENV_GET_VARIABLE_UPDATE = 17, ENV_SET_SUPPORT_NO_GAME = 18, ENV_GET_INPUT_DEVICE_CAPABILITIES = 24,
    ENV_GET_LOG_INTERFACE = 27, ENV_GET_PERF_INTERFACE = 28, ENV_GET_CORE_ASSETS_DIRECTORY = 30,
    ENV_GET_SAVE_DIRECTORY = 31, ENV_SET_SYSTEM_AV_INFO = 32, ENV_SET_CONTROLLER_INFO = 35,
    ENV_SET_MEMORY_MAPS = 36, ENV_SET_GEOMETRY = 37, ENV_GET_USERNAME = 38, ENV_GET_LANGUAGE = 39,
    ENV_SET_SUPPORT_ACHIEVEMENTS = 42, ENV_SET_SERIALIZATION_QUIRKS = 44, ENV_GET_AUDIO_VIDEO_ENABLE = 47,
    ENV_GET_FASTFORWARDING = 49, ENV_GET_TARGET_REFRESH_RATE = 50, ENV_GET_INPUT_BITMASKS = 51,
    ENV_GET_CORE_OPTIONS_VERSION = 52, ENV_GET_MESSAGE_INTERFACE_VERSION = 59, ENV_GET_INPUT_MAX_USERS = 61,
    ENV_SET_MINIMUM_AUDIO_LATENCY = 63, ENV_SET_VARIABLE = 70, ENV_GET_SAVESTATE_CONTEXT = 72
};
#define LANGUAGE_FRENCH 2
#define PIXEL_0RGB1555 0
#define PIXEL_XRGB8888 1
#define PIXEL_RGB565 2

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};
struct retro_game_geometry {
    unsigned base_width, base_height, max_width, max_height;
    float aspect_ratio;
};
struct retro_system_timing {
    double fps;
    double sample_rate;
};
struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};
struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};
struct retro_variable {
    const char *key;
    const char *value;
};
struct retro_message {
    const char *msg;
    unsigned frames;
};
struct retro_log_callback {
    void (*log)(int level, const char *fmt, ...);
};
struct retro_memory_descriptor {
    uint64_t flags;
    void *ptr;
    size_t offset;
    size_t start;
    size_t select;
    size_t disconnect;
    size_t len;
    const char *addrspace;
};
struct retro_memory_map {
    const struct retro_memory_descriptor *descriptors;
    unsigned num_descriptors;
};
struct retro_perf_counter;
struct retro_perf_callback {
    int64_t (*get_time_usec)(void);
    uint64_t (*get_cpu_features)(void);
    uint64_t (*get_perf_counter)(void);
    void (*perf_register)(struct retro_perf_counter *);
    void (*perf_start)(struct retro_perf_counter *);
    void (*perf_stop)(struct retro_perf_counter *);
    void (*perf_log)(void);
};

typedef bool (*env_t)(unsigned, void *);
typedef void (*video_t)(const void *, unsigned, unsigned, size_t);
typedef void (*sample_t)(int16_t, int16_t);
typedef size_t (*batch_t)(const int16_t *, size_t);
typedef void (*poll_t)(void);
typedef int16_t (*state_t)(unsigned, unsigned, unsigned, unsigned);

static struct {
    void (*init)(void);
    void (*deinit)(void);
    void (*run)(void);
    void (*reset)(void);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    void (*set_environment)(env_t);
    void (*set_video_refresh)(video_t);
    void (*set_audio_sample)(sample_t);
    void (*set_audio_sample_batch)(batch_t);
    void (*set_input_poll)(poll_t);
    void (*set_input_state)(state_t);
    void (*set_controller_port_device)(unsigned, unsigned);
} core;

static void *library;
static char *system_dir;
static char *save_dir;
static char *user_name;

/* ------------------------------------------------------------------ options of the core */

#define MAX_VARS 256
static struct {
    char *key;
    char *label;
    char *choices; /* "a|b|c" */
    char *value;
} vars[MAX_VARS];
static int var_count;
static bool vars_dirty;
/* defaults chosen by Cable Club, "key=value" */
static char *overrides[MAX_VARS];
static int override_count;

static int find_var(const char *key) {
    for (int i = 0; i < var_count; i++) {
        if (strcmp(vars[i].key, key) == 0) {
            return i;
        }
    }
    return -1;
}

static const char *override_for(const char *key) {
    size_t n = strlen(key);
    for (int i = 0; i < override_count; i++) {
        if (strncmp(overrides[i], key, n) == 0 && overrides[i][n] == '=') {
            return overrides[i] + n + 1;
        }
    }
    return NULL;
}

static bool choice_exists(const char *choices, const char *wanted) {
    size_t n = strlen(wanted);
    for (const char *p = choices; p && *p;) {
        const char *bar = strchr(p, '|');
        size_t len = bar ? (size_t) (bar - p) : strlen(p);
        if (len == n && strncmp(p, wanted, n) == 0) {
            return true;
        }
        p = bar ? bar + 1 : NULL;
    }
    return false;
}

static char *trim_copy(const char *s, size_t len) {
    while (len > 0 && (*s == ' ' || *s == '\t')) {
        s++;
        len--;
    }
    while (len > 0 && (s[len - 1] == ' ' || s[len - 1] == '\t')) {
        len--;
    }
    char *out = malloc(len + 1);
    memcpy(out, s, len);
    out[len] = 0;
    return out;
}

/* "Label; first|second|third": the first choice is the default, unless Cable Club chose another one */
static void set_variables(const struct retro_variable *v) {
    for (; v && v->key; v++) {
        if (!v->value || find_var(v->key) >= 0 || var_count >= MAX_VARS) {
            continue;
        }
        const char *semi = strchr(v->value, ';');
        int i = var_count++;
        vars[i].key = strdup(v->key);
        vars[i].label = semi ? trim_copy(v->value, (size_t) (semi - v->value)) : strdup(v->key);
        vars[i].choices = trim_copy(semi ? semi + 1 : v->value, strlen(semi ? semi + 1 : v->value));
        const char *bar = strchr(vars[i].choices, '|');
        char *first = bar ? trim_copy(vars[i].choices, (size_t) (bar - vars[i].choices)) : strdup(vars[i].choices);
        const char *wanted = override_for(v->key);
        if (wanted && choice_exists(vars[i].choices, wanted)) {
            free(first);
            vars[i].value = strdup(wanted);
        } else {
            vars[i].value = first;
        }
    }
}

/* ------------------------------------------------------------------ state shared with Java */

static int pixel_format = PIXEL_0RGB1555;
static uint32_t *pixels;
static int pixels_cap;
static int frame_w;
static int frame_h;
static bool frame_ready;

static int16_t *audio;
static int audio_cap;
static int audio_len;

static double fps = 60;
static double sample_rate = 44100;
static int base_w = 240;
static int base_h = 160;
static float aspect = 1.5f;
static bool geometry_changed;

static int buttons;
static int pointer_x;
static int pointer_y;
static bool pointer_down;

static bool fast_forwarding;
static bool shutdown_requested;
static char *last_message;

#define MAX_REGIONS 64
static struct {
    uint64_t start;
    uint64_t len;
    void *ptr;
} regions[MAX_REGIONS];
static int region_count;
/* the core gave its memory map (mGBA does it on its first image, not when the game loads) */
static bool regions_changed;

/* ------------------------------------------------------------------ callbacks of the core */

static void core_log(int level, const char *fmt, ...) {
    if (level < 2) { /* warnings and errors only */
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(level >= 3 ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, fmt, ap);
    va_end(ap);
}

static int64_t perf_time(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000 + t.tv_nsec / 1000;
}

static uint64_t perf_features(void) {
    return 0;
}

static uint64_t perf_counter(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t) t.tv_sec * 1000000000ull + (uint64_t) t.tv_nsec;
}

static void perf_noop(struct retro_perf_counter *c) {
    (void) c;
}

static void perf_log(void) {
}

static struct retro_log_callback log_interface = {core_log};
static struct retro_perf_callback perf_interface = {perf_time, perf_features, perf_counter, perf_noop, perf_noop, perf_noop, perf_log};

static void read_geometry(const struct retro_game_geometry *g) {
    if (g->base_width > 0 && g->base_height > 0) {
        base_w = (int) g->base_width;
        base_h = (int) g->base_height;
        aspect = g->aspect_ratio > 0 ? g->aspect_ratio : (float) base_w / (float) base_h;
        geometry_changed = true;
    }
}

static void read_av_info(const struct retro_system_av_info *av) {
    read_geometry(&av->geometry);
    if (av->timing.fps > 1 && av->timing.fps < 300) {
        fps = av->timing.fps;
    }
    if (av->timing.sample_rate > 1000) {
        sample_rate = av->timing.sample_rate;
    }
}

/* the commands not answered, said once each in the log (cores ask for many optional things) */
static uint8_t told[1024];

static bool environment(unsigned cmd, void *data) {
    unsigned c = cmd & 0xFFFF;
    switch (c) {
        case ENV_GET_LOG_INTERFACE:
            *(struct retro_log_callback *) data = log_interface;
            return true;
        case ENV_GET_PERF_INTERFACE:
            *(struct retro_perf_callback *) data = perf_interface;
            return true;
        case ENV_GET_CAN_DUPE:
            if (data) {
                *(bool *) data = true;
            }
            return true;
        case ENV_SET_MESSAGE:
            if (data && ((struct retro_message *) data)->msg) {
                free(last_message);
                last_message = strdup(((struct retro_message *) data)->msg);
            }
            return true;
        case ENV_SHUTDOWN:
            shutdown_requested = true;
            return true;
        case ENV_GET_SYSTEM_DIRECTORY:
        case ENV_GET_CORE_ASSETS_DIRECTORY:
            *(const char **) data = system_dir;
            return true;
        case ENV_GET_SAVE_DIRECTORY:
            *(const char **) data = save_dir;
            return true;
        case ENV_GET_USERNAME:
            *(const char **) data = user_name;
            return true;
        case ENV_SET_PIXEL_FORMAT: {
            int f = *(const int *) data;
            if (f >= 0 && f <= 2) {
                pixel_format = f;
                return true;
            }
            return false;
        }
        case ENV_SET_INPUT_DESCRIPTORS:
        case ENV_SET_CONTROLLER_INFO:
        case ENV_SET_SUPPORT_ACHIEVEMENTS:
        case ENV_SET_SERIALIZATION_QUIRKS:
        case ENV_SET_MINIMUM_AUDIO_LATENCY:
        case ENV_SET_SUPPORT_NO_GAME:
            return true;
        case ENV_GET_VARIABLE: {
            struct retro_variable *v = data;
            int i = v->key ? find_var(v->key) : -1;
            if (i < 0) {
                v->value = NULL;
                return false;
            }
            v->value = vars[i].value;
            return true;
        }
        case ENV_SET_VARIABLES:
            set_variables(data);
            return true;
        case ENV_SET_VARIABLE: {
            const struct retro_variable *v = data;
            if (v && v->key && v->value) {
                int i = find_var(v->key);
                if (i >= 0) {
                    free(vars[i].value);
                    vars[i].value = strdup(v->value);
                }
            }
            return true;
        }
        case ENV_GET_VARIABLE_UPDATE:
            *(bool *) data = vars_dirty;
            vars_dirty = false;
            return true;
        case ENV_GET_INPUT_DEVICE_CAPABILITIES:
            *(uint64_t *) data = (1ull << RETRO_DEVICE_JOYPAD) | (1ull << RETRO_DEVICE_POINTER);
            return true;
        case ENV_SET_SYSTEM_AV_INFO:
            read_av_info(data);
            return true;
        case ENV_SET_GEOMETRY:
            read_geometry(data);
            return true;
        case ENV_SET_MEMORY_MAPS: {
            const struct retro_memory_map *map = data;
            region_count = 0;
            regions_changed = true;
            for (unsigned i = 0; map && i < map->num_descriptors && region_count < MAX_REGIONS; i++) {
                const struct retro_memory_descriptor *d = &map->descriptors[i];
                if (d->ptr && d->len > 0) {
                    regions[region_count].start = d->start;
                    regions[region_count].len = d->len;
                    regions[region_count].ptr = (char *) d->ptr + d->offset;
                    region_count++;
                }
            }
            return true;
        }
        case ENV_GET_LANGUAGE:
            *(unsigned *) data = LANGUAGE_FRENCH;
            return true;
        case ENV_GET_AUDIO_VIDEO_ENABLE:
            if (data) {
                *(int *) data = 3;
            }
            return true;
        case ENV_GET_FASTFORWARDING:
            if (data) {
                *(bool *) data = fast_forwarding;
            }
            return true;
        case ENV_GET_TARGET_REFRESH_RATE:
            *(float *) data = 60.0f;
            return true;
        case ENV_GET_INPUT_BITMASKS:
            if (data) {
                *(bool *) data = true;
            }
            return true;
        case ENV_GET_CORE_OPTIONS_VERSION:
        case ENV_GET_MESSAGE_INTERFACE_VERSION:
        case ENV_GET_SAVESTATE_CONTEXT:
            *(unsigned *) data = 0;
            return true;
        case ENV_GET_INPUT_MAX_USERS:
            *(unsigned *) data = 1;
            return true;
        default:
            if (c < sizeof told && !told[c]) {
                told[c] = 1;
                __android_log_print(ANDROID_LOG_INFO, TAG, "commande libretro non prise en charge : %u", cmd);
            }
            return false;
    }
}

/* the image as 0x00RRGGBB, colours widened like the PC version does */
static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID || w == 0 || h == 0) {
        return;
    }
    int n = (int) (w * h);
    if (pixels_cap < n) {
        free(pixels);
        pixels = malloc(sizeof(uint32_t) * (size_t) n);
        pixels_cap = n;
    }
    const uint8_t *src = data;
    for (unsigned y = 0; y < h; y++) {
        uint32_t *row = pixels + (size_t) y * w;
        if (pixel_format == PIXEL_XRGB8888) {
            const uint32_t *s = (const uint32_t *) (src + y * pitch);
            for (unsigned x = 0; x < w; x++) {
                row[x] = s[x] & 0xFFFFFF;
            }
        } else {
            const uint16_t *s = (const uint16_t *) (src + y * pitch);
            bool rgb565 = pixel_format == PIXEL_RGB565;
            for (unsigned x = 0; x < w; x++) {
                unsigned p = s[x], r, g, b;
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
                row[x] = (r << 16) | (g << 8) | b;
            }
        }
    }
    frame_w = (int) w;
    frame_h = (int) h;
    frame_ready = true;
}

static void ensure_audio(int extra) {
    if (audio_len + extra > audio_cap) {
        int cap = audio_cap * 2 > audio_len + extra ? audio_cap * 2 : audio_len + extra;
        audio = realloc(audio, sizeof(int16_t) * (size_t) cap);
        audio_cap = cap;
    }
}

static void audio_sample(int16_t l, int16_t r) {
    ensure_audio(2);
    audio[audio_len++] = l;
    audio[audio_len++] = r;
}

static size_t audio_batch(const int16_t *data, size_t frames) {
    int n = (int) frames * 2;
    ensure_audio(n);
    memcpy(audio + audio_len, data, sizeof(int16_t) * (size_t) n);
    audio_len += n;
    return frames;
}

static void input_poll(void) {
    /* Java gives the buttons before each image (nativeInput) */
}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void) index;
    if (port != 0) {
        return 0;
    }
    unsigned dev = device & 0xFF;
    if (dev == RETRO_DEVICE_JOYPAD) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
            return (int16_t) buttons;
        }
        return (int16_t) ((buttons >> id) & 1);
    }
    if (dev == RETRO_DEVICE_POINTER) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X:
                return (int16_t) pointer_x;
            case RETRO_DEVICE_ID_POINTER_Y:
                return (int16_t) pointer_y;
            case RETRO_DEVICE_ID_POINTER_PRESSED:
                return pointer_down ? 1 : 0;
            default:
                return 0;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------ JNI */

static char *java_string(JNIEnv *env, jstring s) {
    if (!s) {
        return strdup("");
    }
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

static void *symbol(const char *name, char *missing, size_t size) {
    void *p = dlsym(library, name);
    if (!p && missing[0] == 0) {
        snprintf(missing, size, "%s", name);
    }
    return p;
}

/* Loads the core and starts it. @return null, or what went wrong */
JNIEXPORT jstring JNICALL
Java_slt_player_CoreHost_nativeOpen(JNIEnv *env, jclass cls, jstring path, jstring systemDir, jstring saveDir,
                                    jstring user, jobjectArray defaults) {
    (void) cls;
    char *p = java_string(env, path);
    library = dlopen(p, RTLD_NOW | RTLD_LOCAL);
    free(p);
    if (!library) {
        return (*env)->NewStringUTF(env, dlerror());
    }
    char missing[96] = {0};
    core.init = symbol("retro_init", missing, sizeof missing);
    core.deinit = symbol("retro_deinit", missing, sizeof missing);
    core.run = symbol("retro_run", missing, sizeof missing);
    core.reset = symbol("retro_reset", missing, sizeof missing);
    core.load_game = symbol("retro_load_game", missing, sizeof missing);
    core.unload_game = symbol("retro_unload_game", missing, sizeof missing);
    core.get_system_info = symbol("retro_get_system_info", missing, sizeof missing);
    core.get_system_av_info = symbol("retro_get_system_av_info", missing, sizeof missing);
    core.serialize_size = symbol("retro_serialize_size", missing, sizeof missing);
    core.serialize = symbol("retro_serialize", missing, sizeof missing);
    core.unserialize = symbol("retro_unserialize", missing, sizeof missing);
    core.get_memory_data = symbol("retro_get_memory_data", missing, sizeof missing);
    core.get_memory_size = symbol("retro_get_memory_size", missing, sizeof missing);
    core.set_environment = symbol("retro_set_environment", missing, sizeof missing);
    core.set_video_refresh = symbol("retro_set_video_refresh", missing, sizeof missing);
    core.set_audio_sample = symbol("retro_set_audio_sample", missing, sizeof missing);
    core.set_audio_sample_batch = symbol("retro_set_audio_sample_batch", missing, sizeof missing);
    core.set_input_poll = symbol("retro_set_input_poll", missing, sizeof missing);
    core.set_input_state = symbol("retro_set_input_state", missing, sizeof missing);
    core.set_controller_port_device = symbol("retro_set_controller_port_device", missing, sizeof missing);
    if (missing[0]) {
        char msg[160];
        snprintf(msg, sizeof msg, "Fonction absente du cœur : %s", missing);
        return (*env)->NewStringUTF(env, msg);
    }
    system_dir = java_string(env, systemDir);
    save_dir = java_string(env, saveDir);
    user_name = java_string(env, user);
    override_count = 0;
    int n = defaults ? (*env)->GetArrayLength(env, defaults) : 0;
    for (int i = 0; i < n && override_count < MAX_VARS; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, defaults, i);
        overrides[override_count++] = java_string(env, s);
        (*env)->DeleteLocalRef(env, s);
    }
    core.set_environment(environment);
    core.set_video_refresh(video);
    core.set_audio_sample(audio_sample);
    core.set_audio_sample_batch(audio_batch);
    core.set_input_poll(input_poll);
    core.set_input_state(input_state);
    core.init();
    return NULL;
}

/* {library name, version, "1" when the core wants the file's path rather than its bytes} */
JNIEXPORT jobjectArray JNICALL
Java_slt_player_CoreHost_nativeInfo(JNIEnv *env, jclass cls) {
    (void) cls;
    struct retro_system_info info;
    memset(&info, 0, sizeof info);
    core.get_system_info(&info);
    jobjectArray out = (*env)->NewObjectArray(env, 3, (*env)->FindClass(env, "java/lang/String"), NULL);
    (*env)->SetObjectArrayElement(env, out, 0, (*env)->NewStringUTF(env, info.library_name ? info.library_name : "?"));
    (*env)->SetObjectArrayElement(env, out, 1, (*env)->NewStringUTF(env, info.library_version ? info.library_version : "?"));
    (*env)->SetObjectArrayElement(env, out, 2, (*env)->NewStringUTF(env, info.need_fullpath ? "1" : "0"));
    return out;
}

static void *game_data;
static size_t game_size;

/* Loads the game: by its path, or (cores that want the bytes) the file mapped in memory, never copied through
 * Java (a DS ROM weighs up to 512 MB). @return null, or what went wrong */
JNIEXPORT jstring JNICALL
Java_slt_player_CoreHost_nativeLoad(JNIEnv *env, jclass cls, jstring path, jboolean bytes) {
    (void) cls;
    struct retro_game_info g;
    memset(&g, 0, sizeof g);
    g.path = java_string(env, path);
    if (bytes) {
        int fd = open(g.path, O_RDONLY);
        struct stat st;
        if (fd < 0 || fstat(fd, &st) != 0) {
            if (fd >= 0) {
                close(fd);
            }
            return (*env)->NewStringUTF(env, "ROM illisible");
        }
        game_size = (size_t) st.st_size;
        game_data = mmap(NULL, game_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
        close(fd);
        if (game_data == MAP_FAILED) {
            game_data = NULL;
            return (*env)->NewStringUTF(env, "ROM trop grande pour la mémoire");
        }
        g.data = game_data;
        g.size = game_size;
    }
    if (!core.load_game(&g)) {
        return (*env)->NewStringUTF(env, "jeu refusé par le cœur");
    }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof av);
    core.get_system_av_info(&av);
    read_av_info(&av);
    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    return NULL;
}

/* {fps, sample rate, base width, base height, aspect} */
JNIEXPORT jdoubleArray JNICALL
Java_slt_player_CoreHost_nativeAv(JNIEnv *env, jclass cls) {
    (void) cls;
    jdouble v[5] = {fps, sample_rate, base_w, base_h, aspect};
    jdoubleArray out = (*env)->NewDoubleArray(env, 5);
    (*env)->SetDoubleArrayRegion(env, out, 0, 5, v);
    return out;
}

JNIEXPORT void JNICALL
Java_slt_player_CoreHost_nativeInput(JNIEnv *env, jclass cls, jint mask, jint x, jint y, jboolean down, jboolean fast) {
    (void) env;
    (void) cls;
    buttons = mask;
    pointer_x = x;
    pointer_y = y;
    pointer_down = down;
    fast_forwarding = fast;
}

/* runs one image; @return 1 when an image was drawn, +2 when the size of the screen changed, +4 when the core
 * asked to stop, +8 when it gave (again) its memory map */
JNIEXPORT jint JNICALL
Java_slt_player_CoreHost_nativeRun(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    audio_len = 0;
    frame_ready = false;
    geometry_changed = false;
    core.run();
    int r = (frame_ready ? 1 : 0) | (geometry_changed ? 2 : 0) | (shutdown_requested ? 4 : 0) | (regions_changed ? 8 : 0);
    regions_changed = false;
    return r;
}

JNIEXPORT void JNICALL
Java_slt_player_CoreHost_nativeReset(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    core.reset();
}

/* the last image into an int array of at least width x height; @return width << 16 | height */
JNIEXPORT jint JNICALL
Java_slt_player_CoreHost_nativePixels(JNIEnv *env, jclass cls, jintArray out) {
    (void) cls;
    int n = frame_w * frame_h;
    if (n <= 0 || !out || (*env)->GetArrayLength(env, out) < n) {
        return (frame_w << 16) | frame_h;
    }
    (*env)->SetIntArrayRegion(env, out, 0, n, (const jint *) pixels);
    return (frame_w << 16) | frame_h;
}

/* the sound of the last image (stereo samples) into a short array; @return the number of values, or -needed */
JNIEXPORT jint JNICALL
Java_slt_player_CoreHost_nativeAudio(JNIEnv *env, jclass cls, jshortArray out) {
    (void) cls;
    if (!out || (*env)->GetArrayLength(env, out) < audio_len) {
        return -audio_len;
    }
    if (audio_len > 0) {
        (*env)->SetShortArrayRegion(env, out, 0, audio_len, audio);
    }
    return audio_len;
}

JNIEXPORT jstring JNICALL
Java_slt_player_CoreHost_nativeMessage(JNIEnv *env, jclass cls) {
    (void) cls;
    if (!last_message) {
        return NULL;
    }
    jstring s = (*env)->NewStringUTF(env, last_message);
    free(last_message);
    last_message = NULL;
    return s;
}

/* the core's own memory (save data, main RAM) as a direct buffer, or null */
JNIEXPORT jobject JNICALL
Java_slt_player_CoreHost_nativeMemory(JNIEnv *env, jclass cls, jint which) {
    (void) cls;
    size_t size = core.get_memory_size((unsigned) which);
    void *data = size > 0 ? core.get_memory_data((unsigned) which) : NULL;
    return data ? (*env)->NewDirectByteBuffer(env, data, (jlong) size) : NULL;
}

/* the memory map declared by the core: {start, length} pairs, the buffers by nativeRegion */
JNIEXPORT jlongArray JNICALL
Java_slt_player_CoreHost_nativeRegions(JNIEnv *env, jclass cls) {
    (void) cls;
    jlongArray out = (*env)->NewLongArray(env, region_count * 2);
    for (int i = 0; i < region_count; i++) {
        jlong v[2] = {(jlong) regions[i].start, (jlong) regions[i].len};
        (*env)->SetLongArrayRegion(env, out, i * 2, 2, v);
    }
    return out;
}

JNIEXPORT jobject JNICALL
Java_slt_player_CoreHost_nativeRegion(JNIEnv *env, jclass cls, jint i) {
    (void) cls;
    if (i < 0 || i >= region_count) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, regions[i].ptr, (jlong) regions[i].len);
}

JNIEXPORT jbyteArray JNICALL
Java_slt_player_CoreHost_nativeSaveState(JNIEnv *env, jclass cls) {
    (void) cls;
    size_t size = core.serialize_size();
    if (size == 0) {
        return NULL;
    }
    void *buf = malloc(size);
    jbyteArray out = NULL;
    if (core.serialize(buf, size)) {
        out = (*env)->NewByteArray(env, (jsize) size);
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) size, buf);
    }
    free(buf);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_slt_player_CoreHost_nativeLoadState(JNIEnv *env, jclass cls, jbyteArray state) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, state);
    void *buf = malloc((size_t) n);
    (*env)->GetByteArrayRegion(env, state, 0, n, buf);
    bool ok = core.unserialize(buf, (size_t) n);
    free(buf);
    return ok;
}

/* the core's options: key, label, value, choices ("a|b|c"), four strings each */
JNIEXPORT jobjectArray JNICALL
Java_slt_player_CoreHost_nativeVariables(JNIEnv *env, jclass cls) {
    (void) cls;
    jobjectArray out = (*env)->NewObjectArray(env, var_count * 4, (*env)->FindClass(env, "java/lang/String"), NULL);
    for (int i = 0; i < var_count; i++) {
        (*env)->SetObjectArrayElement(env, out, i * 4, (*env)->NewStringUTF(env, vars[i].key));
        (*env)->SetObjectArrayElement(env, out, i * 4 + 1, (*env)->NewStringUTF(env, vars[i].label));
        (*env)->SetObjectArrayElement(env, out, i * 4 + 2, (*env)->NewStringUTF(env, vars[i].value));
        (*env)->SetObjectArrayElement(env, out, i * 4 + 3, (*env)->NewStringUTF(env, vars[i].choices));
    }
    return out;
}

JNIEXPORT void JNICALL
Java_slt_player_CoreHost_nativeSetVariable(JNIEnv *env, jclass cls, jstring key, jstring value) {
    (void) cls;
    char *k = java_string(env, key);
    int i = find_var(k);
    free(k);
    if (i >= 0) {
        free(vars[i].value);
        vars[i].value = java_string(env, value);
        vars_dirty = true;
    }
}

JNIEXPORT void JNICALL
Java_slt_player_CoreHost_nativeClose(JNIEnv *env, jclass cls, jboolean loaded) {
    (void) env;
    (void) cls;
    if (loaded) {
        core.unload_game();
    }
    core.deinit();
    /* the library stays loaded: the process ends with the game */
}
