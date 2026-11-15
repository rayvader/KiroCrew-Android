package io.github.rayvader.kirocrew

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkManager

/**
 * One-time cleanup after the Froyo widget was removed (v2.0.220). Android drops the widget
 * itself on update, but the saved list and the two background jobs of v2.0.219 stay behind.
 */
object LegacyWidget {
    private const val DONE = "legacy_froyo_widget_dropped"
    private const val OLD_PREFIX = "froyo_"
    private val OLD_JOBS = listOf("froyo-widget-refresh", "froyo-widget-refresh-now")

    fun dropOnce(ctx: Context) {
        val sp = Prefs(ctx).sp
        if (sp.getBoolean(DONE, false)) return
        val work = WorkManager.getInstance(ctx)
        OLD_JOBS.forEach { work.cancelUniqueWork(it) }
        sp.edit {
            sp.all.keys.filter { it.startsWith(OLD_PREFIX) }.forEach { remove(it) }
            putBoolean(DONE, true)
        }
    }
}
