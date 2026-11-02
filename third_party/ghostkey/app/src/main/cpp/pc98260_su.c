#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define ROOT_SOCK "/data/local/tmp/pc98260-root.sock"

static int write_all(int fd, const void *buffer, size_t length)
{
	const char *p = buffer;
	while (length) {
		ssize_t sent = write(fd, p, length);
		if (sent < 0 && errno == EINTR) continue;
		if (sent <= 0) return -1;
		p += sent;
		length -= (size_t)sent;
	}
	return 0;
}

int pc98260_su_main(int argc, char **argv)
{
	const char *path = getenv("PC98260_SOCK");
	if (!path || !*path) path = ROOT_SOCK;
	const char *command = "";
	if (argc == 3 && !strcmp(argv[1], "-c")) command = argv[2];
	else if (argc != 1) {
		fprintf(stderr, "usage: %s [-c command]\n", argv[0]);
		return 2;
	}
	size_t length = strlen(command);
	if (length > 4096U) return 2;
	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) return 1;
	struct sockaddr_un address = { .sun_family = AF_UNIX };
	if (strlen(path) >= sizeof(address.sun_path)) {
		close(fd);
		return 1;
	}
	strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
	/*
	 * rootd comes up asynchronously (cred escalation runs several rounds of UAF), so we cannot connect only once here.
	 * The previous impl errored on a single failure, hitting a 0600 dead socket or a not-yet-listening window
	 * and only showed "Permission denied", unable to tell 'not up yet' from 'really no permission'.
	 * Now: retryable errno waits up to ~15s; on failure we print the socket's uid/gid/mode together.
	 */
	int connected = -1;
	int last_errno = 0;
	struct timespec backoff = { .tv_sec = 0, .tv_nsec = 250000000L };
	for (int attempt = 0; attempt < 60; attempt++) {
		connected = connect(fd, (struct sockaddr *)&address, sizeof(address));
		if (connected == 0)
			break;
		last_errno = errno;
		if (last_errno != ENOENT && last_errno != ECONNREFUSED &&
		    last_errno != EACCES && last_errno != EAGAIN)
			break;
		if (attempt == 0)
			fprintf(stderr, "[su] waiting for rootd (%s), up to 15s\n",
				strerror(last_errno));
		nanosleep(&backoff, NULL);
	}
	if (connected) {
		struct stat st;
		if (stat(path, &st) == 0)
			fprintf(stderr,
				"connect root daemon: %s (sock uid=%u gid=%u mode=%04o)\n",
				strerror(last_errno), (unsigned)st.st_uid,
				(unsigned)st.st_gid, (unsigned)(st.st_mode & 07777));
		else
			fprintf(stderr,
				"connect root daemon: %s (sock does not exist: %s)\n",
				strerror(last_errno), strerror(errno));
		close(fd);
		return 1;
	}
	uint32_t command_length = (uint32_t)length;
	if (write_all(fd, &command_length, sizeof(command_length)) ||
	    write_all(fd, command, length)) {
		close(fd);
		return 1;
	}
	if (length) (void)shutdown(fd, SHUT_WR);
	bool input_open = !length;
	for (;;) {
		struct pollfd fds[2] = {
			{ .fd = fd, .events = POLLIN },
			{ .fd = STDIN_FILENO, .events = input_open ? POLLIN : 0 }
		};
		int ready = poll(fds, input_open ? 2 : 1, -1);
		if (ready < 0 && errno == EINTR) continue;
		if (ready < 0) break;
		if (fds[0].revents & (POLLIN | POLLHUP)) {
			char buffer[4096];
			ssize_t got = read(fd, buffer, sizeof(buffer));
			if (got <= 0) break;
			if (write_all(STDOUT_FILENO, buffer, (size_t)got)) break;
		}
		if (input_open && (fds[1].revents & (POLLIN | POLLHUP))) {
			char buffer[4096];
			ssize_t got = read(STDIN_FILENO, buffer, sizeof(buffer));
			if (got <= 0) {
				input_open = false;
				(void)shutdown(fd, SHUT_WR);
			} else if (write_all(fd, buffer, (size_t)got)) break;
		}
	}
	close(fd);
	return 0;
}
