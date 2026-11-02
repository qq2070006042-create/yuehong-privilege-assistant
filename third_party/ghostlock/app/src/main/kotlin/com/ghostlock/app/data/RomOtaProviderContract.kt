package com.ghostlock.app.data

/** Private same-UID contract between the embedded GhostLock library and assistant app. */
object RomOtaProviderContract {
    const val AUTHORITY_SUFFIX = ".ghostlock.ota"
    const val METHOD_RESOLVE = "resolve_rom_v2_ota"
    const val KEY_SUCCESS = "success"
    const val KEY_URL = "ota_url"
    const val KEY_PROVIDER = "provider"
    const val KEY_ERROR = "error"
}
