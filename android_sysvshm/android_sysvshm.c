#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <errno.h>

#include "sys/shm.h"

#define REQUEST_CODE_SHMGET 0
#define REQUEST_CODE_GET_FD 1
#define REQUEST_CODE_DELETE 2

#define MIN_REQUEST_LENGTH 5
#define ROUND_UP(N, S) ((((N) + (S) - 1) / (S)) * (S))

/* based on https://github.com/pelya/android-shmem */

typedef struct {
    int id;
    void* addr;
    int fd;
    size_t size;
    char marked_for_delete;
} shmemory_t;

static shmemory_t* shmemories = NULL;
static int shmemory_count = 0;
static int sysvshm_server_fd = -1;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

static int find_shmemory_index(int shmid) {
    for (int i = 0; i < shmemory_count; i++) if (shmemories[i].id == shmid) return i;
    return -1;
}

static void sysvshm_connect() {
    if (sysvshm_server_fd >= 0) return;
    char* path = getenv("ANDROID_SYSVSHM_SERVER");
    if (path == NULL) return;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return;
    
    struct sockaddr_un server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_LOCAL;
    
    strncpy(server_addr.sun_path, path, sizeof(server_addr.sun_path) - 1);
    
    int res;
    do {
        res = 0;
        if (connect(fd, (struct sockaddr*)&server_addr, sizeof(struct sockaddr_un)) < 0) res = -errno;
    } 
    while (res == -EINTR);        
    
    if (res < 0) {
        close(fd);
        return;
    }

    sysvshm_server_fd = fd;    
}

static void sysvshm_close() {
    if (sysvshm_server_fd >= 0) {
        close(sysvshm_server_fd);
        sysvshm_server_fd = -1;
    }
}

static int sysvshm_shmget_request(size_t size) {
    if (sysvshm_server_fd < 0) return 0;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_SHMGET;
    memcpy(request_data + 1, &size, 4);
    
    int res = write(sysvshm_server_fd, request_data, sizeof(request_data));
    if (res < 0) return 0;
    
    int shmid;
    res = read(sysvshm_server_fd, &shmid, 4);
    return res == 4 ? shmid : 0;
}

static int sysvshm_get_fd_request(int shmid) {
    if (sysvshm_server_fd < 0) return 0;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_GET_FD;
    memcpy(request_data + 1, &shmid, 4);
    
    int res = write(sysvshm_server_fd, request_data, sizeof(request_data));
    if (res < 0) return -1;
    
    char zero = 0;
    struct iovec iovmsg = {.iov_base = &zero, .iov_len = 1};
    struct {
        struct cmsghdr align;
        int fds[1];
    } ctrlmsg;

    struct msghdr msg = {
        .msg_name = NULL,
        .msg_namelen = 0,
        .msg_iov = &iovmsg,
        .msg_iovlen = 1,
        .msg_flags = 0,
        .msg_control = &ctrlmsg,
        .msg_controllen = sizeof(struct cmsghdr) + sizeof(int)
    };

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = msg.msg_controllen;
    ((int*)CMSG_DATA(cmsg))[0] = -1;

    recvmsg(sysvshm_server_fd, &msg, 0);
    return ((int*)CMSG_DATA(cmsg))[0];
}

static void sysvshm_delete_request(int shmid) {
    if (sysvshm_server_fd < 0) return;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_DELETE;
    memcpy(request_data + 1, &shmid, 4);
    
    write(sysvshm_server_fd, request_data, sizeof(request_data));
}

static void sysvshm_delete(int index) {
    sysvshm_connect();
    sysvshm_delete_request(shmemories[index].id);
    sysvshm_close();

    if (shmemories[index].fd >= 0) close(shmemories[index].fd);
    shmemory_count--;
    memmove(&shmemories[index], &shmemories[index+1], (shmemory_count - index) * sizeof(shmemory_t));
}

int shmget(key_t key, size_t size, int flags) {
    if (key != IPC_PRIVATE) return -1;
    
    pthread_mutex_lock(&mutex);
        
    sysvshm_connect();
    int shmid = sysvshm_shmget_request(size);
    if (shmid == 0) {
        sysvshm_close();
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    
    size = ROUND_UP(size, getpagesize());
    int index = shmemory_count;
    shmemory_count++;
    shmemories = realloc(shmemories, shmemory_count * sizeof(shmemory_t));
    shmemories[index].size = size;
    shmemories[index].fd = sysvshm_get_fd_request(shmid);
    shmemories[index].addr = NULL;
    shmemories[index].id = shmid;
    shmemories[index].marked_for_delete = 0;
    
    sysvshm_close();
    
    if (shmemories[index].fd < 0) {
        shmemory_count--;
        shmemories = realloc(shmemories, shmemory_count * sizeof(shmemory_t));
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    
    pthread_mutex_unlock(&mutex);
    return shmid;
}

void* shmat(int shmid, const void* shmaddr, int shmflg) {
    pthread_mutex_lock(&mutex);

    void* addr = (void *)-1;
    int index = find_shmemory_index(shmid);
    if (index != -1) {
        if (shmemories[index].addr == NULL) {
            shmemories[index].addr = mmap(NULL, shmemories[index].size, PROT_READ | (shmflg == 0 ? PROT_WRITE : 0), MAP_SHARED, shmemories[index].fd, 0);
            if (shmemories[index].addr == MAP_FAILED) shmemories[index].addr = NULL;
        }
        addr = shmemories[index].addr;
    }
    
    pthread_mutex_unlock(&mutex);
    return addr ? addr : (void *)-1;
}

int shmdt(const void* shmaddr) {
    pthread_mutex_lock(&mutex);
    
    for (int i = 0; i < shmemory_count; i++) {
        if (shmemories[i].addr == shmaddr) {
            munmap(shmemories[i].addr, shmemories[i].size);
            shmemories[i].addr = NULL;
            if (shmemories[i].marked_for_delete) sysvshm_delete(i);
            break;
        }
    }    
    
    pthread_mutex_unlock(&mutex);
    return 0;
}

int shmctl(int shmid, int cmd, struct shmid_ds* buf) {
    if (cmd == IPC_RMID) {
        pthread_mutex_lock(&mutex);
        
        int index = find_shmemory_index(shmid);
        if (index != -1) {
            if (shmemories[index].addr) {
                shmemories[index].marked_for_delete = 1;
            } 
            else sysvshm_delete(index);                
        }        
        
        pthread_mutex_unlock(&mutex);
        return 0;
    } 
    else if (cmd == IPC_STAT) {
        pthread_mutex_lock(&mutex);
        
        int index = find_shmemory_index(shmid);
        if (!buf || index == -1) {
            pthread_mutex_unlock(&mutex);
            return -1;
        }
        
        memset(buf, 0, sizeof(struct shmid_ds));
        buf->shm_segsz = shmemories[index].size;
        buf->shm_nattch = 1;
        buf->shm_perm.__key = IPC_PRIVATE;
        buf->shm_perm.uid = geteuid();
        buf->shm_perm.gid = getegid();
        buf->shm_perm.cuid = geteuid();
        buf->shm_perm.cgid = getegid();
        buf->shm_perm.mode = 0666;
        buf->shm_perm.__seq = 1;
        
        pthread_mutex_unlock(&mutex);
        return 0;
    }
    return -1;
}
/* ===========================================================================
 * POSIX shm_open / shm_unlink shim for Android bionic libc
 *
 * Bionic does NOT implement shm_open()/shm_unlink() — without this shim
 * every wlroots-derived compositor (incl. gamescope) segfaults within
 * milliseconds of startup on Android. Backed by memfd_create() (raw
 * syscall — bionic only exposes the wrapper on API 30+, raw syscall
 * works on all NDK targets including our API 33 baseline).
 *
 * Strategy: maintain a name -> fd registry so shm_unlink + a second
 * shm_open with the same name behave like POSIX. The fds themselves
 * are anonymous memfds, so they never appear on a /dev/shm filesystem.
 * shm_unlink() just marks the registry entry as unlinked; the fd stays
 * live until last close — matching wlroots/gamescope usage patterns
 * (they always shm_unlink immediately after shm_open to get anon fd).
 * =========================================================================== */

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC       0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif

#ifndef __NR_memfd_create
#define __NR_memfd_create 279  /* aarch64 */
#endif

typedef struct {
    char  name[256];
    int   fd;
    int   refcount;
    int   unlinked;
} posix_shm_entry_t;

static posix_shm_entry_t *posix_shm_registry = NULL;
static size_t posix_shm_registry_count = 0;
/* Reuses the global `mutex` declared earlier in this file. */

static int memfd_create_raw(const char *name, unsigned int flags) {
    return (int)syscall(__NR_memfd_create, name, flags);
}

static const char *normalize_shm_name(const char *name) {
    if (!name) return "anon";
    while (*name == '/') name++;
    if (*name == '\0') return "anon";
    return name;
}

int shm_open(const char *name, int oflag, mode_t mode) {
    (void)mode;  /* memfd_create ignores mode */
    const char *norm = normalize_shm_name(name);
    int existing_idx = -1;

    pthread_mutex_lock(&mutex);
    for (size_t i = 0; i < posix_shm_registry_count; i++) {
        if (posix_shm_registry[i].fd >= 0 &&
            !posix_shm_registry[i].unlinked &&
            strcmp(posix_shm_registry[i].name, norm) == 0) {
            existing_idx = (int)i;
            break;
        }
    }

    if (existing_idx >= 0) {
        if ((oflag & O_CREAT) && (oflag & O_EXCL)) {
            pthread_mutex_unlock(&mutex);
            errno = EEXIST;
            return -1;
        }
        int new_fd = fcntl(posix_shm_registry[existing_idx].fd, F_DUPFD_CLOEXEC, 0);
        if (new_fd >= 0) {
            posix_shm_registry[existing_idx].refcount++;
        } else {
            errno = EMFILE;
        }
        pthread_mutex_unlock(&mutex);
        return new_fd;
    }

    if (!(oflag & O_CREAT)) {
        pthread_mutex_unlock(&mutex);
        errno = ENOENT;
        return -1;
    }

    int fd = memfd_create_raw(norm, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) {
        pthread_mutex_unlock(&mutex);
        errno = ENOMEM;
        return -1;
    }

    size_t idx = posix_shm_registry_count;
    posix_shm_entry_t *new_arr = realloc(
        posix_shm_registry,
        (posix_shm_registry_count + 1) * sizeof(posix_shm_entry_t));
    if (!new_arr) {
        close(fd);
        pthread_mutex_unlock(&mutex);
        errno = ENOMEM;
        return -1;
    }
    posix_shm_registry = new_arr;
    posix_shm_registry[idx].fd = fd;
    posix_shm_registry[idx].refcount = 1;
    posix_shm_registry[idx].unlinked = 0;
    strncpy(posix_shm_registry[idx].name, norm,
            sizeof(posix_shm_registry[idx].name) - 1);
    posix_shm_registry[idx].name[sizeof(posix_shm_registry[idx].name) - 1] = '\0';
    posix_shm_registry_count++;

    pthread_mutex_unlock(&mutex);
    return fd;
}

int shm_unlink(const char *name) {
    const char *norm = normalize_shm_name(name);

    pthread_mutex_lock(&mutex);
    for (size_t i = 0; i < posix_shm_registry_count; i++) {
        if (posix_shm_registry[i].fd >= 0 &&
            !posix_shm_registry[i].unlinked &&
            strcmp(posix_shm_registry[i].name, norm) == 0) {
            posix_shm_registry[i].unlinked = 1;
            if (posix_shm_registry[i].refcount <= 0) {
                close(posix_shm_registry[i].fd);
                posix_shm_registry[i].fd = -1;
            }
            pthread_mutex_unlock(&mutex);
            return 0;
        }
    }
    pthread_mutex_unlock(&mutex);
    errno = ENOENT;
    return -1;
}
