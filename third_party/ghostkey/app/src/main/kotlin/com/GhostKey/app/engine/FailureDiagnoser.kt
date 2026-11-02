package com.GhostKey.app.engine

/**
 * 终端失败归因器（v1.1.1 增量，仍在 1.1.0 包内）。
 *
 * 输入：本次提权运行的终端日志行（原样，含 `[stage] error: ...`）。
 * 输出：命中的 [Diagnosis]（可能为 null → 通用回退），纯函数、无 Android 依赖，可单测。
 *
 * 规则来源：逐条核对 app/src/main/cpp 目录下各 C 源文件中 fprintf 的错误串，
 * 每条规则 = (限定阶段, 关键字正则) → 诊断（标题 / 按概率排序的原因 / 建议动作）。
 */
object FailureDiagnoser {

    /** 一条失败诊断。 */
    data class Diagnosis(
        /** 失败阶段标签（calib_a/calib_b/inject/verify/ksu），未知为 null */
        val stage: String?,
        /** 诊断标题（一句话结论） */
        val title: String,
        /** 可能原因（按概率从高到低） */
        val causes: List<String>,
        /** 建议动作（用户可执行的步骤） */
        val suggestions: List<String>,
    )

    private class Rule(
        /** 仅当出现该阶段标签的错误行才匹配；null = 不限阶段 */
        val stage: String?,
        val keyword: Regex,
        val build: () -> Diagnosis,
    )

    private val RULES = listOf(
        Rule("calib_a", Regex("no plausible slide candidate|no k_itimer records found")) {
            Diagnosis(
                stage = "calib_a",
                title = "KASLR 偏移解算失败：候选地址里找不到可信的内核基址",
                causes = listOf(
                    "内核刚完成 OTA / 刚重启，运行时布局与 profile 假设不符",
                    "导入的 profile 与当前内核版本不匹配（boot.img 版本对不上）",
                    "设备后台负载异常，k_itimer 采样被污染",
                    "tracefs 采样窗口未抓到任何 k_itimer 记录（SELinux 拦截 / Shizuku 权限不足 / 后台负载过低）",
                ),
                suggestions = listOf(
                    "重启设备后重试一次",
                    "确认 profile 对应当前内核版本（设备信息条上的内核版本）",
                    "若连续失败，请保存运行日志以便排查",
                ),
            )
        },
        Rule("calib_a", Regex("no k_itimer records found")) {
            Diagnosis(
                stage = "calib_a",
                title = "KASLR 偏移解算失败：多尺寸探针没采到任何 k_itimer 分配记录",
                causes = listOf(
                    "tracefs 的 kmem_cache_alloc 事件没被打开（被 SELinux 拦截，或 Shizuku 授权 / 挂载异常）",
                    "本机 k_itimer 结构体尺寸不在候选集 {0x108,0x100,0x110,0x118} 内，所有尺寸都没匹配到记录",
                    "采样窗口内系统负载过低，定时器调用点未被充分触发",
                ),
                suggestions = listOf(
                    "确认 Shizuku 已授权且 tracefs 可写（设置 → 开发者选项）",
                    "重启设备后重试，让采样在单次开机内完成",
                    "仍失败请保存运行日志（含 [calib_a] 与 arm_kmem_cache_alloc 行）以便排查",
                ),
            )
        },
        Rule("calib_a", Regex("tracefs not writable|cannot attach|trace_pipe_raw|cannot read")) {
            Diagnosis(
                stage = "calib_a",
                title = "tracefs 不可用：内核追踪事件挂载/读取失败",
                causes = listOf(
                    "SELinux 策略拦截了 tracefs 写入",
                    "Shizuku 授权异常（未启动或权限被回收）",
                    "部分 ROM 精简了 tracefs 节点",
                ),
                suggestions = listOf(
                    "确认 Shizuku 正在运行且已给本应用授权",
                    "检查开发者选项中 USB 调试（无线调试）是否可用",
                    "重启 Shizuku 后重试；仍失败请保存日志排查",
                ),
            )
        },
        Rule("calib_b", Regex("no matching k_itimer records|slide may be wrong")) {
            Diagnosis(
                stage = "calib_b",
                title = "页偏移解算失败：校准 A 得出的基址与实际不符",
                causes = listOf(
                    "校准 A 的 KASLR slide 不准（上游错误，先解决校准 A）",
                    "两次校准之间内核内存布局发生变化",
                ),
                suggestions = listOf(
                    "重启设备让两次校准在同一次开机状态内完成",
                    "重跑完整流水线（不要单独跳过校准步骤）",
                ),
            )
        },
        Rule("calib_b", Regex("insufficient samples|no candidate base hits")) {
            Diagnosis(
                stage = "calib_b",
                title = "采样不足：触发样本太少，无法解算页偏移",
                causes = listOf(
                    "系统后台负载过低，定时器调用点未被充分触发",
                    "设备刚冷启动，运行时服务还没起来",
                ),
                suggestions = listOf(
                    "让设备处于轻度使用状态（如播放音乐）后再重试",
                    "直接多跑一次，样本量通常第二次更充分",
                ),
            )
        },
        Rule(null, Regex("previous round left native process")) {
            Diagnosis(
                stage = "inject",
                title = "存在上一轮残留进程：残留进程还钉着已释放的页，继续注入会导致内核崩溃",
                causes = listOf(
                    "上一轮提权未走完清理步骤（中途强退 / 断电）",
                ),
                suggestions = listOf(
                    "必须重启设备后再试（杀掉残留进程不解决问题）",
                ),
            )
        },
        Rule("inject", Regex("rootd not ready in time")) {
            Diagnosis(
                stage = "inject",
                title = "漏洞已触发但 root 通道未建立：rootd 超时未就绪",
                causes = listOf(
                    "设备内核对该漏洞打了部分回移植补丁，UAF 窗口不稳定",
                    "系统负载导致竞态时序偏差",
                ),
                suggestions = listOf(
                    "重启设备后再试 1~2 次",
                    "若每次都在同一位置超时，内核可能已修复，请记录机型与内核版本以便反馈",
                ),
            )
        },
        Rule("inject", Regex("kernel offset table not loaded|profile")) {
            Diagnosis(
                stage = "inject",
                title = "内核偏移表未加载：注入缺少 profile 数据",
                causes = listOf(
                    "未导入 / 导入了损坏的 profile 文件",
                    "profile 与当前内核不匹配被引擎拒绝",
                ),
                suggestions = listOf(
                    "重新导入对应内核版本的 profile（boot.img 提取）",
                ),
            )
        },
        Rule("task", Regex("leak failed")) {
            Diagnosis(
                stage = "inject",
                title = "task_struct 地址泄漏失败：扫描窗口内没有匹配到 task_struct 的 slab 分配记录",
                causes = listOf(
                    "task_struct 缓存尺寸与 BTF 的 sizeof(task_struct) 不一致：缓存由 kmem_cache_create_usercopy(\"task_struct\", arch_task_struct_size, 0x40, ...) 创建，" +
                        "arch_task_struct_size 是运行时变量，厂商可用 android_arch_task_struct_size= 在其上追加（最多 0x400）",
                    "kmem_cache_alloc 事件未被打开（tracefs 不可写 / SELinux 拒绝）",
                    "fork 落在未被读取的 CPU 上（按实际核数扫描，理论上不会）",
                ),
                suggestions = listOf(
                    "更新到本修复之后的版本重试（尺寸匹配已改为 [sizeof, sizeof+0x400] 窗口，兼容厂商扩展）",
                    "日志里 [task] raw=0 说明是事件没开；raw>0 但 hits=0 则是尺寸窗口不对，请把 [task] sizes= 那行保存下来以便排查",
                ),
            )
        },
        Rule("verify", Regex("verify failed")) {
            Diagnosis(
                stage = "verify",
                title = "临时 root 验证失败：提权通道建立后 su 校验未通过",
                causes = listOf(
                    "注入阶段的凭据改写不完整（上游竞态部分成功）",
                    "验证子进程被 SELinux 或系统策略拦截",
                ),
                suggestions = listOf(
                    "重启设备后重试完整流水线",
                    "检查 SELinux 状态（设置 → 开发者选项），不强制改 Permissive",
                ),
            )
        },
        Rule("ksu", Regex("already live")) {
            Diagnosis(
                stage = "ksu",
                title = "KernelSU 模块已驻留：旧模块仍在内存，late-load 被跳过",
                causes = listOf(
                    "上一次提权注入的 kernelsu 模块还活着",
                ),
                suggestions = listOf(
                    "重启设备后再跑一次，管理器才能看到新接管",
                    "若只是想用 root，无需重跑——当前已是临时 root 状态",
                ),
            )
        },
        Rule("ksu", Regex("no kernelsu|/proc/modules")) {
            Diagnosis(
                stage = "ksu",
                title = "KSU late-load 失败：模块没有出现在 /proc/modules",
                causes = listOf(
                    "设备上的 KernelSU 管理器 / ksud 版本过旧，KMI 不匹配",
                    "模块加载路径被内核限制",
                ),
                suggestions = listOf(
                    "更新设备上的 KernelSU 管理器到最新版",
                    "重试一次；连续失败请保存日志并注明管理器版本",
                ),
            )
        },
        Rule("ksu", Regex("ksud not found")) {
            Diagnosis(
                stage = "ksu",
                title = "找不到 ksud：设备上未安装 KernelSU 管理器且内置兜底失效",
                causes = listOf(
                    "KernelSU 管理器未安装",
                    "内置兜底 ksud 与当前架构 / KMI 不兼容",
                ),
                suggestions = listOf(
                    "先安装 KernelSU 官方管理器，再重跑流水线",
                ),
            )
        },
        Rule(null, Regex("Stellar Shizuku-compatible Binder not connected|Stellar manager authorization required|Stellar privilege service not connected|Shizuku not running|Shizuku.*permission")) {
            Diagnosis(
                stage = null,
                title = "Stellar 的 Shizuku 兼容 Binder 未就绪：GhostKey 无法启动执行流水线",
                causes = listOf(
                    "无线调试卡片显示的 Stellar Binder 与 GhostKey 所需的 Shizuku 兼容 Binder 是两条独立连接",
                    "Stellar shell 服务进程过旧，尚未向更新后的应用重新发送兼容 Binder",
                    "助手自身的 Stellar 应用授权尚未完成",
                ),
                suggestions = listOf(
                    "若无线调试卡片已显示 Stellar 已连接但 GhostKey 仍报此错，重启该服务以刷新兼容 Binder",
                    "确认提权助手自身已获 Stellar 应用授权；本模式不需要另装 Shizuku",
                ),
            )
        },
    )

    /**
     * 对一次运行的日志做失败归因。
     * 从最后往前扫（失败通常在结尾），命中第一条匹配规则即返回；查不到返回 null。
     */
    fun diagnose(logLines: List<String>): Diagnosis? {
        val errorLines = logLines.filter {
            it.contains("error", ignoreCase = true) || it.contains("failed", ignoreCase = true)
        }
        for (line in errorLines.asReversed()) {
            val stage = Regex("\\[([a-z_]+)]").find(line)?.groupValues?.get(1)
            for (rule in RULES) {
                if (rule.stage != null && rule.stage != stage) continue
                if (rule.keyword.containsMatchIn(line)) return rule.build()
            }
        }
        return null
    }

    /**
     * 查不到专用规则时的通用回退诊断。
     *
     * 对 exit=2（KASLR 偏移解算失败）做专项精细化：校准 A/B 已各有专用规则，
     * 走到这里说明 native 打出的失败串未被现有规则捕获（例如换了新的失败措辞、
     * 或失败发生在尚未覆盖的环节但退出码仍是 2）。此时给出更贴近 KASLR 的引导，
     * 而不是笼统的「竞态/环境异常」。其余退出码仍走通用文案。
     */
    fun fallback(exitCode: Int): Diagnosis {
        if (exitCode == 2) {
            return Diagnosis(
                stage = null,
                title = "流水线终止（exit=2）：KASLR 偏移解算失败，且未命中任何已知细分模式",
                causes = listOf(
                    "校准 A 未能从 k_itimer 采样解出可信内核基址（最常见的失败点）",
                    "当前内核布局与所选 profile 不符（刚 OTA/重启、或 profile 版本对不上，导致 timer_link/cred_link 偏移错误）",
                    "tracefs 采样被环境异常打断（SELinux 拦截 / Shizuku 授权失效 / 系统负载异常）",
                    "或本次失败属于尚未被归因器覆盖的新变体（退出码为 2 但日志串未被规则捕获）",
                ),
                suggestions = listOf(
                    "重启设备后重试一次——KASLR 每次开机都不同，重跑通常可解",
                    "确认运行的是最新构建（1.1.4），且 profile 由本机 boot.img 提取、release 精确匹配当前内核",
                    "不要只靠重试：请把「运行日志」页完整内容（尤其含 [calib_a] / [calib_b] 的行）保存下来，便于定位内核适配问题",
                ),
            )
        }
        return Diagnosis(
            stage = null,
            title = "流水线终止（exit=$exitCode）：未命中已知失败模式",
            causes = listOf("时序敏感的竞态窗口未命中，或环境临时异常"),
            suggestions = listOf(
                "不要只靠重试：请把「运行日志」页完整内容复制保存，便于定位内核适配问题",
                "连续失败请重启设备后再试；仍失败请保存运行日志以便排查",
            ),
        )
    }

    /** 渲染为终端高亮块（每行一个字符串，由调用方 appendLog）。 */
    fun format(d: Diagnosis): List<String> = buildList {
        add("<d> 诊断: ${d.title}")
        d.causes.forEach { add("<d>   可能原因: $it") }
        d.suggestions.forEach { add("<d>   建议: $it") }
    }
}
