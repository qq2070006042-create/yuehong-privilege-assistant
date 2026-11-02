# 上游许可证核对

## 本工程与 Stellar

- 本工程根目录 `LICENSE` 为 MPL-2.0，适用于本项目声明的自有集成与修改部分；Stellar/其 API 的对应源码继续按 MPL-2.0 处理。
- Shizuku 原始 AIDL/兼容代码按 Apache-2.0 处理，文本见 `LICENSES/Apache-2.0.txt`。

## GhostLock

- 上游：https://github.com/YuKongA/ghostlock-app
- 许可证：Apache-2.0；上游许可证保存在 `third_party/ghostlock/LICENSE` 和 `LICENSES/GhostLock-Apache-2.0.txt`。
- 集成说明：`third_party/ghostlock/UPSTREAM_SOURCE.md`。

## GhostKey

- 上游：https://github.com/Again1996-bit/CVE-2026-98260-GhostKey
- 集成目录：`third_party/ghostkey/`。
- 上游仓库当前没有正式 `LICENSE` 文件，README 中的授权研究/自用说明不等同于一份开源许可证。
- 项目所有者确认已取得公开再分发该源码的单独授权。该确认只记录再分发依据；没有将 GhostKey 改标为 MPL-2.0，也不推定下游获得超出该授权范围的修改、再许可或商用权利。

## DirtyFrag VR / DFRoot

- DirtyFrag VR 上游：https://github.com/DONGDERS/DirtyFalg-vr
- 中间引擎：https://github.com/mitschud/DirtyFrag
- 上游来源链：DONGDERS README 说明其基于 mitschud/DirtyFrag，而该引擎基于 https://github.com/diabl0w/DFRoot 的 DFRoot 引擎。
- 当前上游仓库页面均标示 GPL-2.0；对应文本见 `LICENSES/GPL-2.0.txt`。本地同步快照为用户提供的 `DirtyFalg-vr-master.zip`（v1.16-vr / 46，SHA-256 `B6997E293DFCC2D9FF252FA1E202900880A01FB40631E755F4F4BCF3CDCF5BDA`）。
- 集成目录：`manager/src/main/jni/dfroot/` 与 `assistant/src/main/jni/vrext/`。文件头中另有 Odzhan BSD 风格许可声明；保留原始文件头，不把所有文件笼统描述为直接源自 DFRoot。

## KSuRoot

- 上游：https://github.com/hmascs/KSuRoot/
- 快照：用户提供的 `KSuRoot-main.zip`（2026-09-29，SHA-256 `AE24388FEA5ED0E21EF4F344DB59CB05E7A6F60742C6BF052CE8F2B3413440FC`）。
- 许可证：Apache-2.0；文本见 `LICENSES/Apache-2.0.txt`。本工程仅保留随 APK 发布的 ARM64 载荷组件及其校验信息。

## 组合发行边界

- MPL-2.0：根许可证声明的月虹集成/修改部分与相应 Stellar 文件。
- Apache-2.0：Shizuku、GhostLock、KSuRoot 对应组件。
- GPL-2.0：DirtyFrag VR / DFRoot 来源链中适用的文件；文本见 `LICENSES/GPL-2.0.txt`，并保留逐文件额外许可头。
- GhostKey：没有上游正式开源许可证；仅依项目所有者确认的单独源码再分发授权纳入，不标为 MPL-2.0，亦不推定下游再利用权。

完整来源和修改范围见 `NOTICE.md`。不同组件的许可证/授权不可互相替代。
