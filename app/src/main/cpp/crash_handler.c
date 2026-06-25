/*
 * crash_handler.c — native signal handler for WayLandIE.
 *
 * Installs an alternate signal stack and sigaction handlers for
 * SIGSEGV, SIGABRT, SIGBUS so that a native crash inside
 * libwaylandie_display_native.so (or any of its dlopen'd dependencies)
 * produces a tombstone file at <crash_dir>/crash-<timestamp>.txt
 * BEFORE the process dies. The tombstone contains:
 *   - signal info (signo, si_addr, si_code)
 *   - backtrace via backtrace_symbols_fd
 *   - /proc/self/maps excerpt for libwaylandie_display_native.so
 *
 * After writing, we _exit(11). We do NOT re-raise the signal —
 * re-raising in a corrupted state re-crashes before the file flushes.
 *
 * Reference patterns:
 *   - Android's own libc-debug tombstone format.
 *   - GameNative vulkan_jni.cpp:70-75 (return-0-on-failure, never
 *     crash the foreground process) — but we go further and install
 *     actual signal handlers because GameNative's pattern only handles
 *     JNI-init failures, not runtime crashes.
 *
 * This file is compiled into libwaylandie_display_native.so and
 * install_native_crash_handler() is called from JNI_OnLoad.
 */
#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dlfcn.h>
#include <execinfo.h>

#define LOG_TAG "WayLandIE/CrashNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CRASH_HANDLER_MAX_FRAMES 64
static char g_crash_dir[PATH_MAX] = {0};
static stack_t g_alt_stack;

static void write_str(int fd, const char *s) {
    if (fd >= 0 && s != NULL) {
        ssize_t n = (ssize_t)strlen(s);
        while (n > 0) {
            ssize_t w = write(fd, s, (size_t)n);
            if (w <= 0) break;
            s += w;
            n -= w;
        }
    }
}

static void write_strf(int fd, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) write_str(fd, buf);
}

static void dump_maps_excerpt(int fd, const char *lib_name) {
    if (fd < 0 || lib_name == NULL) return;
    write_str(fd, "## Native Library Maps (");
    write_str(fd, lib_name);
    write_str(fd, ")\n-------------------------------------------\n");
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp == NULL) {
        write_str(fd, "(failed to open /proc/self/maps)\n");
        return;
    }
    char line[1024];
    int found = 0;
    while (fgets(line, sizeof(line), fp) != NULL) {
        if (strstr(line, lib_name) != NULL) {
            write_str(fd, line);
            found = 1;
        }
    }
    fclose(fp);
    if (!found) {
        write_str(fd, "(");
        write_str(fd, lib_name);
        write_str(fd, " not loaded)\n");
    }
    write_str(fd, "-------------------------------------------\n\n");
}

static void crash_signal_handler(int signo, siginfo_t *info, void *context) {
    (void)context;

    /* Open the tombstone file directly — do NOT use libc FILE* buffered
     * I/O, since the heap may be corrupted. Use raw open/write. */
    char path[PATH_MAX];
    time_t now = time(NULL);
    struct tm tm_info;
    localtime_r(&now, &tm_info);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%d_%H-%M-%S", &tm_info);
    snprintf(path, sizeof(path), "%s/crash-%s-native.txt", g_crash_dir, ts);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        /* Can't write the file — at least log to logcat. */
        LOGE("CRASH: failed to open tombstone %s (errno=%d)", path, errno);
        _exit(11);
    }

    write_str(fd, "=== WayLandIE Native Crash Tombstone ===\n");
    write_strf(fd, "Time: %s", ctime(&now));
    write_strf(fd, "Signal: %d (%s)\n", signo,
               signo == SIGSEGV ? "SIGSEGV" :
               signo == SIGABRT ? "SIGABRT" :
               signo == SIGBUS  ? "SIGBUS"  : "?");
    if (info != NULL) {
        write_strf(fd, "si_code: %d\n", info->si_code);
        write_strf(fd, "si_addr: %p\n", info->si_addr);
    }
    write_str(fd, "===========================================\n\n");

    /* Backtrace — uses libgcc's _Unwind_Backtrace, which is signal-safe
     * on arm64. May return garbage if the stack itself is corrupted. */
    write_str(fd, "## Native Backtrace\n");
    void *frames[CRASH_HANDLER_MAX_FRAMES];
    int n = backtrace(frames, CRASH_HANDLER_MAX_FRAMES);
    if (n > 0) {
        /* backtrace_symbols_fd uses malloc — risky in a signal handler,
         * but on Android it's typically safe enough for first-pass
         * diagnostics. If you need stricter async-signal-safety,
         * replace with manual dladdr() calls. */
        backtrace_symbols_fd(frames, n, fd);
    } else {
        write_str(fd, "(backtrace returned 0 frames — stack may be corrupted)\n");
    }
    write_str(fd, "\n");

    dump_maps_excerpt(fd, "libwaylandie_display_native.so");
    dump_maps_excerpt(fd, "libvulkan.so");
    dump_maps_excerpt(fd, "libadrenotools.so");

    write_str(fd, "=== End of Native Tombstone ===\n");
    fsync(fd);
    close(fd);

    LOGE("CRASH: tombstone written to %s — _exit(11)", path);
    _exit(11);
}

/* Called from JNI_OnLoad. crash_dir must be a writable absolute path,
 * typically the app's getExternalFilesDir(null)/logs/. */
void install_native_crash_handler(const char *crash_dir) {
    if (crash_dir == NULL || crash_dir[0] == '\0') {
        LOGE("install_native_crash_handler: crash_dir is NULL — skipping");
        return;
    }
    strncpy(g_crash_dir, crash_dir, sizeof(g_crash_dir) - 1);
    g_crash_dir[sizeof(g_crash_dir) - 1] = '\0';

    /* Ensure the directory exists */
    mkdir(g_crash_dir, 0700);

    /* Allocate alternate signal stack — required because SIGSEGV on
     * the main stack can overflow if the crash itself is a stack overflow. */
    g_alt_stack.ss_size = SIGSTKSZ * 4;  /* 32 KiB typically */
    g_alt_stack.ss_sp = malloc(g_alt_stack.ss_size);
    if (g_alt_stack.ss_sp == NULL) {
        LOGE("install_native_crash_handler: malloc alt stack failed");
        return;
    }
    g_alt_stack.ss_flags = 0;
    if (sigaltstack(&g_alt_stack, NULL) != 0) {
        LOGE("install_native_crash_handler: sigaltstack failed (errno=%d)", errno);
        free(g_alt_stack.ss_sp);
        return;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    int signals[] = { SIGSEGV, SIGABRT, SIGBUS };
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); i++) {
        if (sigaction(signals[i], &sa, NULL) != 0) {
            LOGE("install_native_crash_handler: sigaction(%d) failed (errno=%d)",
                 signals[i], errno);
        }
    }
    LOGI("install_native_crash_handler: handlers installed, crash_dir=%s", g_crash_dir);
}
