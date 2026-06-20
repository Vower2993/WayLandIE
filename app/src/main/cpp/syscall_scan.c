/*
 * syscall_scan.c — Identifies EXACT blocked syscalls on this Android device.
 *
 * Tests each syscall number (0-450) by calling it in a forked child.
 * If the child exits with 159 (SIGSYS), that syscall is blocked by seccomp.
 *
 * Compiled with NDK (bionic) — bionic doesn't trigger seccomp during startup.
 * The parent survives because children are separate processes.
 *
 * Output format (one line per blocked syscall):
 *   BLOCKED: syscall=N name=syscall_name
 * Summary at end:
 *   SCAN_COMPLETE: total_blocked=N
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <signal.h>
#include <errno.h>

/* Only name interesting syscalls — the rest are "unknown" */
static const char *syscall_name(long n) {
    switch (n) {
        case 56: return "clone";
        case 57: return "fork";
        case 58: return "vfork";
        case 59: return "execve";
        case 101: return "ptrace";
        case 167: return "getcpu";
        case 172: return "getpid";
        case 198: return "socket";
        case 220: return "clone";
        case 221: return "execve";
        case 228: return "mlock";
        case 241: return "perf_event_open";
        case 262: return "fanotify_init";
        case 263: return "fanotify_mark";
        case 264: return "name_to_handle_at";
        case 265: return "open_by_handle_at";
        case 270: return "process_vm_readv";
        case 271: return "process_vm_writev";
        case 334: return "rseq";
        case 384: return "getrandom";
        case 424: return "pidfd_send_signal";
        case 425: return "io_uring_setup";
        case 426: return "io_uring_enter";
        case 427: return "io_uring_register";
        case 434: return "pidfd_open";
        case 435: return "clone3";
        case 436: return "close_range";
        case 437: return "openat2";
        case 438: return "pidfd_getfd";
        case 439: return "faccessat2";
        case 440: return "process_madvise";
        case 441: return "epoll_pwait2";
        case 444: return "landlock_create_ruleset";
        case 445: return "landlock_add_rule";
        case 446: return "landlock_restrict_self";
        case 448: return "process_mrelease";
        case 449: return "futex_waitv";
        default: return "other";
    }
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    int start = 0;
    int end = 450;
    if (argc >= 3) {
        start = atoi(argv[1]);
        end = atoi(argv[2]);
        if (start < 0) start = 0;
        if (end > 500) end = 500;
    }

    printf("syscall-scan-start range=%d-%d\n", start, end);

    int blocked_count = 0;

    for (int n = start; n <= end; n++) {
        pid_t pid = fork();
        if (pid < 0) continue;
        if (pid == 0) {
            /* Child: call syscall N. If blocked → SIGSYS → exit 159. */
            syscall(n, 0, 0, 0, 0, 0, 0);
            _exit(0);
        }
        int status = 0;
        waitpid(pid, &status, 0);

        if (WIFSIGNALED(status) && WTERMSIG(status) == 31) {
            printf("BLOCKED: syscall=%d name=%s\n", n, syscall_name(n));
            blocked_count++;
        }
    }

    printf("SCAN_COMPLETE: total_blocked=%d\n", blocked_count);
    printf("syscall-scan-done\n");
    return 0;
}
