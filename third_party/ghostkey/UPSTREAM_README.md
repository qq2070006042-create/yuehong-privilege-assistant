# GhostKey — CVE-2026-98260 一键提权工具

GhostKey 是一款面向 **已授权安全研究 / 自用设备** 的 Android 一键提权（privilege escalation）工具。它基于 **Shizuku + NDK Native** 实现，把提权所需的 Native exploit、内核偏移表与 OTA boot 镜像解析能力打包进一个独立 APK，用户在手机上点击即可触发，无需 PC、无需命令行。

> ⚠️ **安全与法律责任**
> 本仓库仅用于 **授权环境下的安全研究** 与 **本人持有设备的合法测试**。
> 未经设备所有者明确授权，对他人设备执行提权、获取 root 或突破系统限制，可能违反所在地法律法规与平台服务条款。
> 使用本工具即表示你理解并自行承担由此产生的一切法律风险。作者不对任何滥用行为负责。
> 本仓库编译出来的文件有概率在注入阶段重启
---

## 功能特性

- **一键 ROOT**：手机端点击即完成提权流程，无需连接电脑、无需 adb 命令。
- **Shizuku 衔接**：通过 Shizuku 获取 `adb shell` 上下文，作为提权入口。
- **纯 APK + NDK Native**：exploit 的 C 源码随 APK 通过 NDK/CMake 编译进 `libcve98260.so`。
- **内置内核偏移表**：随 APK 分发多版本内核 profile，按 `uname -r` 精确匹配；无匹配时引导用户导入对应 `boot.img` 自动解析。
- **OTA boot 镜像解析**：内置 `BootImageParser` / `ImgToProfile` / `KallsymsExtractor`，支持从 boot 镜像反推内核符号与偏移。
- **KernelSU 衔接**：`KsuResolver` / `KsudStage` 负责与 KernelSU 集成。
- **运行日志分档**：每次提权运行按 `runId` 分组展示，失败诊断（`FailureDiagnoser`）对 `exit` 码（如 KASLR 解算失败 `exit=2`）给出专项引导。
- **终端面板**：实时回显 exploit 执行日志与阶段状态。

---

## 技术栈与构建环境

| 项目 | 版本 |
|---|---|
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 31 |
| Kotlin | 1.9+（JVM target 17） |
| NDK / CMake | CMake 3.22.1，`ANDROID_STL=none` |
| Shizuku | 13.1.5 |
| Jetpack Compose | BOM 2024.10.00（Material3） |
| 目标 ABI | `arm64-v8a`（仅 64 位） |
| 产物命名 | `GhostKey-<版本>-arm64-v8a-release.apk` |

当前版本：**1.1.4**（versionCode 17，包名 `com.GhostKey.app`）。

---

## 构建

> 本仓库采用 **release** 构建（开启 minify + shrinkResources，产物约 5.8 MB）。请勿使用 `assembleDebug`——旧 debug 密钥与 release 不同，覆盖安装需先卸载。

### 1. 准备签名密码

签名密钥位于 `app/keystore/release.keystore`（已被 `.gitignore` 忽略，**不会**进入版本库）。密码通过以下两种方式之一注入，**不要**写回源码：

- 方式 A：`local.properties` 追加（推荐，该文件已被忽略）
  ```properties
  CVE98260_STORE_PASSWORD=你的密码
  CVE98260_KEY_PASSWORD=你的密码
  ```
- 方式 B：环境变量
  ```bash
  export CVE98260_STORE_PASSWORD=你的密码
  export CVE98260_KEY_PASSWORD=你的密码
  ```

### 2. 编译 release APK

```bash
./gradlew assembleRelease
```

产物路径：`app/build/outputs/apk/release/GhostKey-1.1.5-arm64-v8a-release.apk`

---

## 目录结构（核心模块）

```
app/src/main/
├── kotlin/com/GhostKey/app/
│   ├── Cve98260Application.kt        # Application 入口
│   ├── data/                       # 内核 profile 加载/解析/存储
│   │   ├── Profile.kt              # 内核 profile 数据模型
│   │   ├── BuiltinProfileCatalog.kt# 内置偏移表
│   │   ├── ProfileRepository.kt    # 精确 release 匹配（无回退）
│   │   ├── AssetConfigLoader.kt    # assets 配置加载
│   │   ├── LegacyProfileConverter.kt
│   │   ├── ProfileImportManager.kt # 用户导入 boot.img
│   │   ├── RunLogStore.kt         # 运行日志分档存储
│   │   ├── UserProfileStore.kt
│   │   └── ota/                   # BootImageParser / ImgToProfile / KallsymsExtractor
│   ├── device/                    # DeviceBinding / KernelProbe / PrivilegeGate
│   ├── engine/                    # ExploitEngine / FailureDiagnoser
│   ├── ksu/                       # KsuResolver / KsudStage
│   ├── model/                     # KernelProfile / ShizukuStatus / StageTracker ...
│   ├── native/                    # KernelProfileSerializer / NativeContract
│   ├── shizuku/                   # Cve98260UserService / ShizukuPermissionManager
│   ├── ui/                        # Compose 界面（MainScreen / LogViewerScreen / SettingsScreen）
│   └── viewmodel/                 # ExploitViewModel
├── aidl/com/GhostKey/app/shizuku/ # Shizuku 跨进程接口
├── cpp/                           # Native exploit（CMake 构建为 libcve98260.so）
└── assets/ksu/                    # ksud 二进制
```

---

## 使用流程（概览）

1. 在设备上安装并激活 **Shizuku**，对本应用授权。
2. 安装 KernelSU 管理器（用于接收提权结果）。
3. 打开 GhostKey，应用会探测内核版本并匹配内置偏移表；若无匹配，从设备导出 `boot.img` 导入以自动解析。
4. 点击提权，实时查看终端日志与阶段状态；失败时根据 `FailureDiagnoser` 的专项诊断（校准 A/B、profile 匹配、tracefs 采样等）排查。
5. 提权为**临时 root**，设备重启后失效。

---

## 支持设备

内置内核 profile 覆盖多个 5.15 / 6.1 / 6.6 / 6.12 / 6.18 系列内核。已验证机型示例：

- OPPO Find X8 Ultra（PKJ110）— 内核 `6.6.118-android15-...`

具体内核画像以 `app/src/main/kotlin/com/GhostKey/app/data/BuiltinProfileCatalog.kt` 与各 `*.profile` 资源为准。

---

## 版本约定

- 产物统一命名 `GhostKey-<ver>-release.apk` 后交付 `dist/`（该目录已被 `.gitignore` 忽略，不进版本库）。

---

## 许可证

仅供授权安全研究与自用设备测试。任何超出授权范围的用途均不在许可范围内。
