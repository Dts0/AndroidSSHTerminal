LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# Keep every packaged ABI's PT_LOAD segments compatible with 16 KiB pages.
# NDK r27's APP_SUPPORT_FLEXIBLE_PAGE_SIZES adds this automatically only for
# 64-bit ABIs, while this project also ships armeabi-v7a and x86.
LOCAL_LDFLAGS:= -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
