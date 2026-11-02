package roro.stellar

import roro.stellar.shared.BuildConfig

object StellarApiConstants {
    val SERVER_VERSION = BuildConfig.SERVER_API_VERSION

    const val PERMISSION_KEY = "roro.stellar.permissions"
    const val ACTION_FOLLOW_STARTUP = "roro.stellar.intent.action.FOLLOW_STELLAR_STARTUP"
    const val PERMISSION_STELLAR = "stellar"
    const val PERMISSION_FOLLOW_STARTUP = "follow_stellar_startup"

    val PERMISSIONS = arrayOf(
        PERMISSION_STELLAR,
        PERMISSION_FOLLOW_STARTUP
    )

    fun isRuntimePermission(permission: String): Boolean =
        permission == PERMISSION_STELLAR || permission == "shizuku" || permission.endsWith(":runtime")

    const val BINDER_DESCRIPTOR = "com.stellar.server.IStellarService"
    const val BINDER_TRANSACTION_transact = 1

    const val BIND_APPLICATION_SERVER_VERSION = "stellar:attach-reply-version"
    const val BIND_APPLICATION_SERVER_UID = "stellar:attach-reply-uid"
    const val BIND_APPLICATION_SERVER_SECONTEXT = "stellar:attach-reply-secontext"
    const val BIND_APPLICATION_PERMISSION_GRANTED = "stellar:attach-reply-permission-granted"
    const val BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE = "stellar:attach-reply-should-show-request-permission-rationale"

    const val REQUEST_PERMISSION_REPLY_ALLOWED = "stellar:request-permission-reply-allowed"
    const val REQUEST_PERMISSION_REPLY_IS_ONETIME = "stellar:request-permission-reply-is-onetime"
    const val REQUEST_PERMISSION_REPLY_PERMISSION = "stellar:request-permission-reply-permission"

    const val ATTACH_APPLICATION_PACKAGE_NAME = "stellar:attach-package-name"
    const val ATTACH_APPLICATION_API_VERSION = "stellar:attach-api-version"
}
