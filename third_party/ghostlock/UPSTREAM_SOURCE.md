# GhostLock upstream source anchor

## Source snapshot

- Archive: `ghostlock-app-main (3).zip`, supplied on 2026-10-22
- SHA-256: `953FA5F65F9AE6BAE79C16E4769F327A143F4DBB84729AA544C6141559FD4E4B`
- Upstream app version name: `1.2`
- Application ID: `com.ghostlock.app`
- License: Apache-2.0

The archive hash matches the previously supplied `(2).zip`; the two archives are byte-for-byte identical.

## Synced source

- `app/src/main` and `app/src/test`: upstream app source and resources
- `profile-core/src`: upstream profile implementation and tests
- `src`: upstream C++ Native implementation
- `tools/extract_rs`: upstream Rust OTA/image extractor
- `LICENSE`: upstream license text

The five Native files that differed from the earlier local snapshot were replaced with the archive versions. The stale top-level `Makefile` and the assistant-side generated kernel gate were removed.

## YueHong integration

The upstream Android App module is built as an Android Library and merged into the YueHong assistant APK. The assistant button explicitly opens the upstream `com.ghostlock.app.ui.MainActivity`; no separate GhostLock APK or launcher entry is produced. The host APK uses its existing application ID and application class.

The packaging adapter removes the upstream Application registration so it can coexist with YueHong's `StellarApplication`; the GhostLock activity now creates the same upstream repository directly from `applicationContext`. The GhostLock activity keeps its upstream theme and is internal to the host app. Library BuildConfig fields preserve GhostLock's displayed version and Shizuku user-service version. The combined assistant APK supports Android 10 (API 29) and newer. GhostLock's system-bar appearance code uses AndroidX `WindowCompat` so it works below the upstream API 31 minimum; mode-specific Android 12 requirements remain enforced by those modes.

The Gradle module builds the Native executable and Rust extractor from the synced upstream sources. The embedded module lowers its library `minSdk` to match the host app. The `MainActivity` system-bar compatibility adaptation is the only additional Android runtime source change; later upstream profile additions are recorded below.

## Upstream repository sync

- Repository: https://github.com/YuKongA/ghostlock-app
- Reviewed upstream `main` at `437b3b257462e17d395cfd2cd8fb81536d6c6358` (2026-10-27).
- Synced the five upstream kernel profiles added since the 2026-10-22 source snapshot and their entries in `app/src/main/assets/kernel_profiles/index.conf`.
- The upstream changes in this range do not modify the embedded runtime Kotlin, Native, or Rust sources. Documentation, CI, and upstream developer-only files remain outside this assistant's embedded module.
- YueHong's Android Library/Activity packaging and assistant-specific OTA/provider adaptations remain unchanged.
