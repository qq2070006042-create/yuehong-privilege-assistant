# GhostKey integration

Upstream: [Again1996-bit/CVE-2026-98260-GhostKey](https://github.com/Again1996-bit/CVE-2026-98260-GhostKey)

The upstream app sources are vendored here for integration into the Yuehong
Privilege Assistant workbench. The standalone launcher, application identity,
release-signing configuration, and Shizuku provider declaration are not used;
the host app provides its own entry point and already merges the Shizuku
provider from GhostLock. `libcve98260.so` is built as an ARM64 PIE executable
and staged as a native-library payload for the Shizuku UserService.

The upstream repository does not include a formal LICENSE file. The project owner confirmed that separate authorization for public source redistribution has been obtained. Upstream attribution is preserved in `UPSTREAM_README.md`; these files are not relicensed under MPL-2.0 and no downstream rights beyond that authorization are inferred.
