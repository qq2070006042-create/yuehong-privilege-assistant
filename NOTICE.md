# Notices and attribution

月虹提权助手是一个按组件分别适用许可证或单独授权的组合工程。根目录 `LICENSE` 的 MPL-2.0 仅适用于本工程声明的自有集成/修改部分，不覆盖单独列明的第三方代码。

## Stellar

- Project: Stellar / Stellar-API
- Sources: https://github.com/roro2239/Stellar and https://github.com/roro2239/Stellar-API
- License: MPL-2.0

工程内置 Stellar Manager、Server 与 API，并将月虹提权助手作为唯一桌面入口。Stellar 文件及修改继续遵循 MPL-2.0。

## Shizuku

- Project: Shizuku
- Source: https://github.com/RikkaApps/Shizuku
- License: Apache-2.0

Stellar 保留的 Shizuku AIDL 与兼容层依照原始文件许可处理；文本见 `LICENSES/Apache-2.0.txt`。

## GhostLock

- Project: GhostLock-App
- Source: https://github.com/YuKongA/ghostlock-app
- Snapshot: user-provided archive, 2026-10-22; SHA-256 `953FA5F65F9AE6BAE79C16E4769F327A143F4DBB84729AA544C6141559FD4E4B`
- License: Apache-2.0

上游 Android App、profile-core、C++ Native、Rust OTA/image extractor、内核 profile 与许可证位于 `third_party/ghostlock`。应用模块作 Library/启动适配；细节见 `third_party/ghostlock/UPSTREAM_SOURCE.md`。许可证文本见 `LICENSES/GhostLock-Apache-2.0.txt`。

## GhostKey

- Project: GhostKey — CVE-2026-98260
- Source: https://github.com/Again1996-bit/CVE-2026-98260-GhostKey
- Vendored source: `third_party/ghostkey`
- License status: upstream repository has no formal `LICENSE` file. The project owner confirmed that separate authorization for public source redistribution has been obtained. This repository does not assign MPL-2.0, Apache-2.0, GPL, or another SPDX license to GhostKey files, and does not infer downstream rights beyond that separate authorization.

上游归属和集成范围保留于 `third_party/ghostkey/UPSTREAM_README.md`。有关许可状态的说明见 `LICENSE_REVIEW.md`。

## DirtyFrag VR and DFRoot

- DirtyFrag VR: https://github.com/DONGDERS/DirtyFalg-vr
- Intermediate DirtyFrag engine: https://github.com/mitschud/DirtyFrag
- DFRoot upstream: https://github.com/diabl0w/DFRoot
- Integrated snapshot: user-provided `DirtyFalg-vr-master.zip`, DirtyFrag v1.16-vr, version code 46; SHA-256 `B6997E293DFCC2D9FF252FA1E202900880A01FB40631E755F4F4BCF3CDCF5BDA`
- License: upstream repositories currently identify GPL-2.0; files retain their own notices where present. `LICENSES/GPL-2.0.txt` is included.

The DONGDERS README describes its source chain as mitschud/DirtyFrag based on DFRoot. The integrated manager package contains the DirtyFrag native chain and VR probe logic; `assistant/src/main/jni/vrext/` handles offset extraction. This attribution does not claim that every file originated directly from DFRoot. The `include.inc` file retains Odzhan's BSD-style notice.

## KSuRoot bundled payloads

- Project: KSuRoot
- Source: https://github.com/hmascs/KSuRoot/
- Snapshot: user-provided archive, 2026-09-29; SHA-256 `AE24388FEA5ED0E21EF4F344DB59CB05E7A6F60742C6BF052CE8F2B3413440FC`
- License: Apache-2.0

Only the bundled ARM64 payload components and their integrity metadata are retained. License text: `LICENSES/Apache-2.0.txt`.

Copyright remains with each respective author/contributor. See `LICENSE_REVIEW.md` and the included license files for the exact scope.
