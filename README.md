# 月虹提权助手

月虹提权助手是基于 [aShellYou](https://github.com/DP-Hridayan/aShellYou) 修改的 Android 应用。本项目不是上游官方版本；原项目作者为 DP Hridayan。

本衍生版本保留 Shizuku 本地 ADB shell，并增加设备信息采集、远程兼容性匹配、payload 下载与 SHA-256 校验、LD_PRELOAD 执行以及 Root 状态检测流程。最近修改日期：2026-08-05。

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
- 通过独立系统命令采集机型名称、厂商系统版本和真实内核 release，降低应用进程内属性伪装的影响；为小米/OPlus/vivo、三星、华为、荣耀、Pixel、联想/摩托罗拉、魅族、努比亚/ZTE、华硕、索尼、Nothing 等主流厂商设置专用属性顺序，其他品牌使用通用回退
- 通过兼容性服务选择唯一 payload
- 下载大小限制与 SHA-256 校验
- 通过 Shizuku 写入临时目录并使用 LD_PRELOAD 执行
- Root 状态检测与结果提示
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

本项目涉及高权限操作，只应在您拥有或获授权测试的设备上使用。请自行审查服务端响应和 payload 来源；公开源码不提供任何 payload，也不保证特定设备或内核可用。

