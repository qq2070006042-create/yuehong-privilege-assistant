# 月虹提权助手

月虹提权助手是面向用户自有或明确授权设备的 Android 提权工作台，支持 Stellar、DirtyFrag VR、GhostLock、GhostKey 和 vivo/iQOO 有线模式。Root 管理器与提权模式在同一工作台选择，页面只显示当前模式的操作控件。

- 应用 ID：`roro.stellar.yuehong`
- 当前源码版本：`v1.6.7`（`versionCode 167`）
- 最低系统：Android 10（API 29）；DirtyFrag VR 等模式另有各自的系统/内核要求
- 目标系统：Android API 37
- ABI：`arm64-v8a`

## 模式概览

- **Stellar**：通过应用内无线调试激活 Stellar 特权服务，提供本地/内置载荷和命令工作台。
- **DirtyFrag VR**：基于 DirtyFrag（CVE-2026-43284），支持用户手动启用 VR 探针处理，并通过运行内核 BTF、boot.img/OTA 解析或手动输入获取偏移。
- **GhostLock**：基于 GhostLock（CVE-2026-43499）；按缓存、APK 内置精确 profile、ROM V2 OTA 的顺序准备 profile。
- **GhostKey**：基于 GhostKey（CVE-2026-98260）与 shell；支持内置 profile、用户提供 boot.img 或 OTA 链接。
- **vivo/iQOO 有线**：通过对应载荷与双设备 OTG/USB 连接完成操作。

各模式的设备兼容性、内核范围和必要前置条件以对应上游及应用中的检查为准。Root 提权可能导致设备重启、临时失去响应或无法启动；仅在自有或明确获授权设备上使用，并提前准备恢复方式。

## 源码结构

```text
assistant/   月虹提权助手 UI、载荷与业务逻辑
manager/     Android 宿主、无线调试、授权、工作台与 Native 代码
server/      Stellar 特权服务
api/         固定版本的 Stellar API、AIDL、Provider 与共享代码
shizuku/     Stellar 内部使用的 Shizuku 兼容层
third_party/ 上游 GhostLock 与 GhostKey 源码
LICENSES/    第三方许可证文本
```

## 主要上游项目

- [Stellar](https://github.com/roro2239/Stellar) 与 [Stellar-API](https://github.com/roro2239/Stellar-API)：内置管理器、服务、API 与无线调试框架。
- [GhostLock-App](https://github.com/YuKongA/ghostlock-app)：GhostLock 界面、profile、OTA 解析与 Native 核心。
- [GhostKey](https://github.com/Again1996-bit/CVE-2026-98260-GhostKey)：GhostKey Native、profile、boot/OTA 解析与执行组件。
- [DirtyFalg-vr](https://github.com/DONGDERS/DirtyFalg-vr)：DirtyFrag VR 及探针处理上游；其 README 将代码来源追溯至 [mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag)，并说明其基于 [DFRoot](https://github.com/diabl0w/DFRoot) 的引擎。
- [DFRoot](https://github.com/diabl0w/DFRoot)：DirtyFrag 上游引擎来源。
- [KSuRoot](https://github.com/hmascs/KSuRoot/)：APK 内置载荷来源。
- [Shizuku](https://github.com/RikkaApps/Shizuku)：Stellar 兼容层中的原始 AIDL/组件来源。

第三方来源、改动范围和许可证边界见 [NOTICE.md](NOTICE.md)、[LICENSE_REVIEW.md](LICENSE_REVIEW.md)、[LICENSES](LICENSES/) 与各 `UPSTREAM_*.md` 文件。GhostKey 上游没有发布正式许可证；本仓库按单独取得的源码再分发授权包含该组件，不将它改标为 MPL-2.0，也不据此推定下游获得超出上游授权范围的使用权。

## 启动验证与服务配置

本公开源码不包含月虹私有的启动版本/频道门禁、运营接口配置或密钥。启动后按权限状态进入工作台。Stellar/ROM 等需要联网的功能使用 `server.properties.example` 中的空白字段；构建者可复制为 `server.properties` 并配置自己的服务，不配置时相应在线功能不可用。不要将实际 `server.properties`、签名属性或密钥库提交到仓库。

## 构建

需要 JDK 21、Android SDK Platform 37、Build Tools 36、Android NDK `29.0.13113456`（或设置 `ANDROID_NDK_HOME`）、Rust `aarch64-linux-android` target 和 CMake 3.22.1+。

Windows 中文路径下使用工程脚本：

```powershell
.\tools\build_android.ps1 -Variant Debug
.\tools\build_android.ps1 -Variant Release
```

Release 构建使用构建者本机自行创建的 `keystore.properties` 和密钥库；它们不随源码提供。输出位于 `out/apk` 和 `out/mapping`，版本号从根工程 `build.gradle` 读取。

## 安全与许可

本软件包含本地提权与内核模块加载组件，风险由使用者自行评估。只对自有或明确获授权设备测试。不要将本仓库的 MPL-2.0 根许可证误认为覆盖所有第三方文件：各组件按其上游许可证、文件头和单独授权分别处理；GhostKey 的许可证状态尤其见 [LICENSE_REVIEW.md](LICENSE_REVIEW.md)。
