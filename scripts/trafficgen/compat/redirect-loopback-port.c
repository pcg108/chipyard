/* Test infrastructure only: run unchanged historical endpoints on another
 * loopback port. No frame, scheduler, RTL, or timestamp transformation.
 * Opt in with LD_PRELOAD plus TG_COMPAT_SOCKET_PORT. Only exact IPv4
 * 127.0.0.1:50051 bind/connect addresses are rewritten. */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <sys/socket.h>

static int redirected(const struct sockaddr *address, socklen_t length,
                      struct sockaddr_in *output) {
    if (!address || length != sizeof(*output) || address->sa_family != AF_INET)
        return 0;
    const struct sockaddr_in *input = (const struct sockaddr_in *)address;
    if (input->sin_addr.s_addr != htonl(INADDR_LOOPBACK) || input->sin_port != htons(50051))
        return 0;
    const char *value = getenv("TG_COMPAT_SOCKET_PORT");
    if (!value || !*value)
        return 0;
    char *end = NULL;
    errno = 0;
    unsigned long port = strtoul(value, &end, 10);
    if (errno || *end || port == 0 || port > 65535) {
        errno = EINVAL;
        return -1;
    }
    *output = *input;
    output->sin_port = htons((unsigned short)port);
    return 1;
}

int bind(int fd, const struct sockaddr *address, socklen_t length) {
    int (*original)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, "bind");
    if (!original) { errno = ENOSYS; return -1; }
    struct sockaddr_in changed;
    int result = redirected(address, length, &changed);
    if (result < 0) return -1;
    return original(fd, result ? (const struct sockaddr *)&changed : address, length);
}

int connect(int fd, const struct sockaddr *address, socklen_t length) {
    int (*original)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, "connect");
    if (!original) { errno = ENOSYS; return -1; }
    struct sockaddr_in changed;
    int result = redirected(address, length, &changed);
    if (result < 0) return -1;
    return original(fd, result ? (const struct sockaddr *)&changed : address, length);
}
