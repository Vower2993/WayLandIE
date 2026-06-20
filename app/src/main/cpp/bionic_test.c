/*
 * bionic_test.c — Minimal bionic test binary.
 *
 * This binary is compiled with the Android NDK (bionic libc, NOT glibc).
 * It is packaged as libwaylandie_bionic_test.so (Android requires .so
 * naming for jniLibs) but is actually a PIE executable.
 *
 * Purpose: Prove that bionic binaries execute without SIGSYS on Android 16.
 * If this binary runs successfully (exit=0, prints "hello-bionic"), it
 * confirms that the seccomp SIGSYS issue is glibc-specific, and that
 * bionic is the correct path for native execution.
 *
 * Build: NDK clang compiles this as a PIE executable linked against
 *        bionic libc.so. No glibc dependency whatsoever.
 */

/* bionic gates cpu_set_t / CPU_ZERO / sched_getaffinity behind __USE_GNU,
 * which is only defined when _GNU_SOURCE is set. */
#define _GNU_SOURCE 1

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sched.h>      /* cpu_set_t, CPU_ZERO, CPU_SETSIZE, CPU_ISSET, sched_getaffinity */
#include <sys/syscall.h>
#include <sys/types.h>
#include <errno.h>

int main(int argc, char **argv) {
    /* Basic bionic runtime test — if we got here, bionic startup succeeded. */
    printf("hello-bionic\n");
    printf("argc=%d\n", argc);
    for (int i = 1; i < argc; i++) {
        printf("argv[%d]=%s\n", i, argv[i]);
    }

    /* Test basic syscalls that glibc might be failing on */
    printf("getpid=%d\n", (int)getpid());
    printf("getuid=%d\n", (int)getuid());

    /* Test getcpu — glibc uses this for cache topology */
    unsigned int cpu = 0, node = 0;
    long ret = syscall(SYS_getcpu, &cpu, &node, NULL);
    if (ret == 0) {
        printf("getcpu=ok cpu=%u node=%u\n", cpu, node);
    } else {
        printf("getcpu=fail errno=%d\n", errno);
    }

    /* Test sched_getaffinity — glibc uses this for CPU detection */
    cpu_set_t mask;
    CPU_ZERO(&mask);
    ret = sched_getaffinity(0, sizeof(mask), &mask);
    if (ret == 0) {
        int count = 0;
        for (int i = 0; i < CPU_SETSIZE; i++) {
            if (CPU_ISSET(i, &mask)) count++;
        }
        printf("sched_getaffinity=ok cpus=%d\n", count);
    } else {
        printf("sched_getaffinity=fail errno=%d\n", errno);
    }

    /* Test rseq — if this fails with ENOSYS, seccomp allows it but kernel
     * doesn't support it. If it fails with SIGSYS, seccomp blocks it. */
#ifdef __NR_rseq
    /* Minimal rseq struct — just enough to test the syscall */
    volatile struct {
        int cpu_id_start;
        int cpu_id;
        unsigned long long rseq_cs;
        unsigned long long flags;
        unsigned long long node_id;
        unsigned long long mm_cid;
    } rseq_data = {0};
    ret = syscall(__NR_rseq, (void *)&rseq_data, sizeof(rseq_data), 0, 0);
    if (ret == 0) {
        printf("rseq=ok (supported)\n");
    } else {
        printf("rseq=fail errno=%d (%s)\n", errno, strerror(errno));
    }
#else
    printf("rseq=not-defined\n");
#endif

    /* Test clone3 (435) — glibc may use this for thread creation */
#ifdef __NR_clone3
    ret = syscall(__NR_clone3, NULL, 0);
    if (ret == -1 && errno == EFAULT) {
        printf("clone3=allowed (EFAULT expected with NULL args)\n");
    } else if (ret == -1) {
        printf("clone3=fail errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("clone3=unexpected-success ret=%ld\n", ret);
    }
#else
    printf("clone3=not-defined\n");
#endif

    printf("bionic-test-complete\n");
    fflush(stdout);
    return 0;
}
