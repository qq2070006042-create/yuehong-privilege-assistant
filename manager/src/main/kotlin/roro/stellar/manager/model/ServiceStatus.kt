package roro.stellar.manager.model

import roro.stellar.Stellar

enum class RestrictedFeature {
    TERMINAL_COMMAND,
    SHELL_ID_COMMAND,
    PROPERTY_READ_COMMAND,
    SETTINGS_READ_COMMAND,
    PACKAGE_LIST_COMMAND,
    SERVICE_LIST_COMMAND,
    PROCESS_LIST_COMMAND,
    FILESYSTEM_READ_COMMAND,
    SELINUX_STATUS_COMMAND,
    APPOPS_MANAGE,
    RUNTIME_PERMISSION_MANAGE,
    SECURE_SETTINGS_WRITE,
    BOOT_ADB_START
}

data class FeatureAvailability(
        val feature: RestrictedFeature,
        val available: Boolean,
        val children: List<FeatureAvailability> = emptyList()
)

data class ServiceStatus(
        val uid: Int = -1,
        val apiVersion: Int = -1,
        val permission: Boolean = false,
        val featureStates: List<FeatureAvailability> = emptyList()
) {
    val isRunning: Boolean
        get() = uid != -1 && Stellar.pingBinder()
}

