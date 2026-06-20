/*
 * waylandie_syscall_shim.c — LD_PRELOAD shim that intercepts syscalls
 * blocked by Android's seccomp filter.
 *
 * ROOT CAUSE: libwayland-server 1.22 and libvulkan have library constructors
 * that call syscalls Android's seccomp blocks (SIGSYS, exit 159). This shim
 * intercepts those syscalls via LD_PRELOAD and returns ENOSYS (or fake
 * success) so the library falls back to older methods.
 *
 * The shim is compiled with glibc 2.31 (in the rootfs chroot) so it's
 * ABI-compatible with glibc libraries. glibc 2.31 doesn't call rseq/clone3
 * during startup, so the shim itself won't trigger SIGSYS.
 *
 * Build: cc -shared -fPIC -o libwaylandie_shim.so waylandie_syscall_shim.c -ldl
 * Usage: LD_PRELOAD=/usr/local/lib/libwaylandie_shim.so <program>
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>

/* Arm64 syscall numbers for potentially-blocked syscalls */
#ifndef __NR_rseq
#define __NR_rseq 334
#endif
#ifndef __NR_clone3
#define __NR_clone3 435
#endif
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#endif
#ifndef __NR_io_uring_enter
#define __NR_io_uring_enter 426
#endif
#ifndef __NR_io_uring_register
#define __NR_io_uring_register 427
#endif
#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_pidfd_send_signal
#define __NR_pidfd_send_signal 424
#endif
#ifndef __NR_getrandom
#define __NR_getrandom 384
#endif
#ifndef __NR_close_range
#define __NR_close_range 436
#endif

/* Logging — write to stderr (unbuffered) so output is not lost on SIGSYS */
static int shim_enabled = -1;
static void shim_log(const char *msg) {
    if (shim_enabled == -1) {
        const char *env = getenv("WAYLANDIE_SHIM_DEBUG");
        shim_enabled = (env != NULL && env[0] == '1') ? 1 : 0;
    }
    if (shim_enabled) {
        const char prefix[] = "WAYLANDIE_SHIM: ";
        write(2, prefix, sizeof(prefix) - 1);
        write(2, msg, strlen(msg));
        write(2, "\n", 1);
    }
}

/* Inline syscall for arm64 — used to forward allowed syscalls to the kernel
 * without going through the overridden syscall() function. */
static long raw_syscall6(long number, long a1, long a2, long a3,
                          long a4, long a5, long a6) {
    register long x8 asm("x8") = number;
    register long x0 asm("x0") = a1;
    register long x1 asm("x1") = a2;
    register long x2 asm("x2") = a3;
    register long x3 asm("x3") = a4;
    register long x4 asm("x4") = a5;
    register long x5 asm("x5") = a6;
    asm volatile("svc 0" : "+r"(x0)
                 : "r"(x8), "r"(x1), "r"(x2),
                   "r"(x3), "r"(x4), "r"(x5)
                 : "memory", "cc");
    return x0;
}

/* Check if a syscall number is in our blocklist */
static int is_blocked(long number) {
    switch (number) {
        case __NR_rseq:
        case __NR_clone3:
        case __NR_openat2:
        case __NR_io_uring_setup:
        case __NR_io_uring_enter:
        case __NR_io_uring_register:
        case __NR_pidfd_open:
        case __NR_pidfd_send_signal:
        case __NR_close_range:
            return 1;
        default:
            return 0;
    }
}

/* Get syscall name for logging */
static const char *syscall_name(long number) {
    switch (number) {
        case __NR_rseq: return "rseq";
        case __NR_clone3: return "clone3";
        case __NR_openat2: return "openat2";
        case __NR_io_uring_setup: return "io_uring_setup";
        case __NR_io_uring_enter: return "io_uring_enter";
        case __NR_io_uring_register: return "io_uring_register";
        case __NR_pidfd_open: return "pidfd_open";
        case __NR_pidfd_send_signal: return "pidfd_send_signal";
        case __NR_close_range: return "close_range";
        case __NR_getrandom: return "getrandom";
        default: return "unknown";
    }
}

/*
 * Override the generic syscall() function.
 * This catches ALL raw syscall() calls from any library.
 * Blocked syscalls return ENOSYS (or 0 for rseq).
 * Allowed syscalls are forwarded to the kernel via inline assembly.
 */
long syscall(long number, ...) {
    va_list args;
    va_start(args, number);
    long a1 = va_arg(args, long);
    long a2 = va_arg(args, long);
    long a3 = va_arg(args, long);
    long a4 = va_arg(args, long);
    long a5 = va_arg(args, long);
    long a6 = va_arg(args, long);
    va_end(args);

    if (is_blocked(number)) {
        char buf[128];
        const char *name = syscall_name(number);
        /* rseq returns 0 (success) — some code doesn't check the return value */
        if (number == __NR_rseq) {
            snprintf(buf, sizeof(buf), "intercepted %s(%ld) -> returning 0 (fake success)", name, number);
            shim_log(buf);
            return 0;
        }
        /* All others return ENOSYS — library falls back to older method */
        snprintf(buf, sizeof(buf), "intercepted %s(%ld) -> returning ENOSYS", name, number);
        shim_log(buf);
        errno = ENOSYS;
        return -1;
    }

    /* Forward allowed syscalls to the kernel */
    return raw_syscall6(number, a1, a2, a3, a4, a5, a6);
}

/*
 * Override specific glibc wrapper functions.
 * These are called by libraries that use glibc wrappers instead of raw syscall().
 */

/* rseq — return 0 (fake success) */
int rseq(void *rseq_area, size_t rseq_len, int flags, uint32_t sig) {
    shim_log("intercepted rseq() wrapper -> returning 0");
    return 0;
}

/* getrandom — fall back to /dev/urandom */
ssize_t getrandom(void *buffer, size_t length, unsigned int flags) {
    /* Some Android seccomp filters block getrandom. Fall back to /dev/urandom. */
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        errno = EIO;
        return -1;
    }
    ssize_t n = read(fd, buffer, length);
    close(fd);
    return n;
}

/* clone3 — return ENOSYS so glibc falls back to clone */
long clone3(void *args, size_t size) {
    shim_log("intercepted clone3() wrapper -> returning ENOSYS");
    errno = ENOSYS;
    return -1;
}
