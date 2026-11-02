package com.GhostKey.app.native

/** Kotlin 与原生体之间的协议常量。 */
object NativeContract {
    /** 阶段状态行前缀（与原生 main.c 的 STATUS_MARKER 一致）。 */
    const val STATUS_MARKER = "\u001eC98260_STATUS"

    /** 阶段名，顺序即 UI 进度节点。 */
    object Stage {
        const val CALIB_A = "calib_a"
        const val CALIB_B = "calib_b"
        const val INJECT = "inject"
        const val VERIFY = "verify"
        const val KSU = "ksu"

        val ORDERED = listOf(CALIB_A, CALIB_B, INJECT, VERIFY, KSU)

        fun indexOf(stage: String): Int = ORDERED.indexOf(stage).let { if (it < 0) 0 else it }

        /**
         * 各阶段在总进度里的权重（合计 100），比例与 EXPECTED_MS 的预期耗时一致
         * （13/15/38/8/26 ≈ 10s/12s/30s/6s/20s），避免权重与爬升斜率互相矛盾。
         */
        val WEIGHTS = listOf(13f, 15f, 38f, 8f, 26f)

        /**
         * 各阶段的预期耗时（毫秒）。用于让「正在执行」的阶段内进度平滑爬升，
         * 而不是像以前那样整个阶段内进度条一动不动。
         *
         * 校准依据：真机（X8U）完整流程实测约 1 分钟量级——
         * 校准两步各 ~10s，注入 ~30s，校验 ~6s，KSU 接管 ~20s。
         * 此前注入段按 75s 预期，导致进度条在注入段提前封顶后长时间卡住。
         */
        val EXPECTED_MS = listOf(10_000L, 12_000L, 30_000L, 6_000L, 20_000L)

        /** 阶段刚开始时的默认动作说明。 */
        val ENTER_HINT = listOf(
            "启动 KASLR slide 校准",
            "启动线性映射基址校准",
            "下发注入指令",
            "校验临时 root 通道",
            "接管 KernelSU",
        )
    }

    /** 宿主上报的阶段状态取值。 */
    object Status {
        const val RUNNING = "running"
        const val OK = "ok"
        const val DEGRADED = "degraded"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"
    }

    /** 原生体环境变量名。 */
    object Env {
        const val HOME = "CVE98260_HOME"
        const val KSUD = "CVE98260_KSUD"
        const val KSU_LOG = "CVE98260_KSU_LOG"
    }
}
