package roro.stellar.manager.util

import android.app.ActivityManager
import android.content.Context

object BackgroundVisibilityUtils {
    fun setHidden(context: Context, hidden: Boolean) {
        context.getSystemService(ActivityManager::class.java)
            .appTasks
            .forEach { it.setExcludeFromRecents(hidden) }
    }
}
