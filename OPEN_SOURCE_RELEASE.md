# 开源发行说明

本地 GitHub 开源副本准备为月虹提权助手 `v1.6.7`（`versionCode 167`），最低 Android 10/API 29。源码来自权威开发仓库的已提交文件，并保留公开副本此前已完成的私有配置清理。

## 私有内容边界

- 已从公开源码删除月虹私有启动版本/频道验证代码、对应服务接口调用、专属字符串与 BuildConfig 配置字段；不得从权威私有源码重新带回。
- 不含 `server.properties`、发行签名属性/密钥库、运营域名、私有频道链接、正式服务配置、APK/AAB、R8 mapping、构建缓存、调试日志或 `.codex_transaction`。
- 保留 `server.properties.example`，所有配置值为空；构建者需要时自行复制为 `server.properties` 并填写自己的服务配置。
- 不含仅供月虹正式版使用的 `tools/build_fast.ps1`。

## 上游与许可证

新增 GhostKey 上游：<https://github.com/Again1996-bit/CVE-2026-98260-GhostKey>。其源码再分发权限已由项目所有者确认；上游没有正式 `LICENSE` 文件，因此不将 GhostKey 改标为 MPL-2.0，也不推定下游有额外使用权。

新增 DirtyFrag 引擎来源 DFRoot：<https://github.com/diabl0w/DFRoot>。DirtyFrag VR 直接来源仍记为 <https://github.com/DONGDERS/DirtyFalg-vr>，其 README 描述的引擎链还包括 <https://github.com/mitschud/DirtyFrag>。完整许可证范围见 `NOTICE.md`、`LICENSE_REVIEW.md`、`LICENSES/`。

## 发布前检查

```powershell
pwsh -NoProfile -File .\tools\verify_public_source.ps1
```

打包使用当前提交的 Git 跟踪文件，不把 `.git`、未跟踪文件或忽略文件放进 ZIP。发布前复核版本字段、上游链接、许可证清单以及公开源码扫描结果。该本地副本不会自动推送 GitHub。
