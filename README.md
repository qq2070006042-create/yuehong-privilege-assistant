# 月虹提权助手

月虹提权助手是基于 [aShellYou](https://github.com/DP-Hridayan/aShellYou) 修改的 Android 应用。本项目不是上游官方版本；原项目作者为 DP Hridayan。

本衍生版本保留 Shizuku 本地 ADB shell，并增加设备信息采集、远程精确兼容性匹配、设备专属提权命令执行、真实 Root 状态检测以及可选的 KernelSU 越狱模式激活流程。最近修改日期：2026-08-06。

## 开源许可

本项目依照 GNU General Public License v3.0（GPL-3.0）发布。完整许可文本见 [LICENSE.md](LICENSE.md)，上游归属和本版本修改摘要见 [NOTICE.md](NOTICE.md)。再分发本应用或修改版本时，请继续遵守 GPL-3.0 并提供对应源代码。

## 公开版与服务器配置

公开源码保留通用的 HTTPS 客户端接口和响应解析代码，但不包含任何运营服务器地址、私有模块标识、服务端源码、payload、签名文件或部署配置。

不提供私有配置时，项目仍可编译；公告和兼容性接口为空，应用会按“接口未配置”处理，不会向运营服务器发起请求。

如需接入自行管理的兼容性服务，可将 `server.properties.example` 复制为根目录下的 `server.properties`，然后填写：

```properties
announcementEndpoint=
compatibilityEndpoint=
moduleId=
```

两个地址必须使用 HTTPS。`server.properties` 已被 Git 和源码交付脚本排除，请勿提交真实地址或凭据。

## 主要功能

- Shizuku 状态检测与授权
- 本地 ADB shell 命令执行、停止和输出清理
- 品牌化公告加载页与公告确认卡片，支持动态配色、深色模式和独立的强制更新警示状态
- 通过独立系统命令采集机型名称、厂商系统版本和真实内核 release，降低应用进程内属性伪装的影响；为小米/OPlus/vivo、三星、华为、荣耀、Pixel、联想/摩托罗拉、魅族、努比亚/ZTE、华硕、索尼、Nothing 等主流厂商设置专用属性顺序，其他品牌使用通用回退
- 通过兼容性服务选择唯一命令档案
- 客户端和服务端通过 `matchMode: exact` 强制使用区分大小写的精确兼容档案，拒绝通配符模式的旧服务端响应
- 对服务端下发的单条设备专属 shell 提权命令进行 UTF-8 字节长度和危险分隔符双重校验
- 每次提权流程只执行一次服务端命令，随后通过服务端档案返回的 `suPath` 执行 `-c id` 并确认 `uid=0`
- Root 验证成功后询问用户是否激活 KernelSU 越狱模式；拒绝时直接关闭弹窗
- 用户同意后先用该档案的 `suPath` 执行客户端固定 `late-load`；成功后由 KernelSU 接管，策略加载和飞行模式操作使用普通 `su`
- 飞行模式关闭使用兜底清理逻辑，中途失败时仍会尝试恢复
- 中英文界面和系统动态配色

## 构建

需要 JDK 17 和 Android SDK。在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleRelease
```

Release 签名从根目录的 `keystore.properties` 读取；该文件和 `*.jks` 均被 Git 忽略。生成文件为：

```text
app/build/outputs/apk/release/月虹提权助手-v8.0.0-release.apk
```

未配置签名时，可执行 `:app:assembleDebug` 构建调试版。

## 安全说明

本项目涉及高权限操作，只应在您拥有或获授权测试的设备上使用。兼容性服务返回的设备专属命令本身负责完成提权，客户端会以 Shizuku shell 身份直接执行它，因此必须使用可信 HTTPS 服务并逐条审查后台配置。只有服务端档案返回的 `suPath` 成功执行 `-c id` 并确认 `uid=0` 后才会显示成功并提供 KernelSU 激活选项；公开源码不提供实际提权命令档案或 payload，也不保证特定设备或内核可用。
