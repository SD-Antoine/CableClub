# The libretro frontend of Cable Club for Android (the cores themselves are prebuilt libretro builds).
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := cclibretro
LOCAL_SRC_FILES := cclibretro.c
LOCAL_CFLAGS := -O2 -Wall -Wextra -Wno-unused-parameter
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
