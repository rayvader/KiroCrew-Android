package io.github.rayvader.kirocrew

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.rayvader.kirocrew.core.DueState
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.Upcoming
import io.github.rayvader.kirocrew.core.UpcomingItem
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The "Froyo · Upcoming" home-screen widget. */
class FroyoWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = FroyoWidgets.schedule(context)

    override fun onDisabled(context: Context) = FroyoWidgets.unschedule(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        FroyoWidgets.render(context)
        FroyoWidgets.schedule(context)
        FroyoWidgets.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        FroyoWidgets.render(context)
    }
}

/** The widget's saved list, what it shows, and when it refreshes. Android calls only at run time. */
object FroyoWidgets {
    private const val REFRESH = "froyo-widget-refresh"
    private const val REFRESH_NOW = "froyo-widget-refresh-now"
    private const val SP_ITEMS = "froyo_items"
    private const val SP_ITEMS_AT = "froyo_items_at"
    private const val SP_ERROR = "froyo_error"
    private const val SP_HIDDEN = "froyo_hidden"
    private const val SP_SLOT = "froyo_slot"
    private const val SP_MEMBER = "froyo_member"
    private const val SP_PROFILE = "froyo_profile"

    private val rowIds = intArrayOf(R.id.froyo_row1, R.id.froyo_row2, R.id.froyo_row3, R.id.froyo_row4)
    private val iconIds = intArrayOf(R.id.froyo_icon1, R.id.froyo_icon2, R.id.froyo_icon3, R.id.froyo_icon4)
    private val dueIds = intArrayOf(R.id.froyo_due1, R.id.froyo_due2, R.id.froyo_due3, R.id.froyo_due4)
    private val titleIds = intArrayOf(R.id.froyo_title1, R.id.froyo_title2, R.id.froyo_title3, R.id.froyo_title4)
    private val doneIds = intArrayOf(R.id.froyo_done1, R.id.froyo_done2, R.id.froyo_done3, R.id.froyo_done4)

    enum class Error { NONE, NO_SERVER, SIGNED_OUT, FAILED }

    fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, FroyoWidgetProvider::class.java))

    fun hasWidgets(ctx: Context): Boolean = ids(ctx).isNotEmpty()

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<FroyoRefreshWorker>(30, TimeUnit.MINUTES)
            .setConstraints(online())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun unschedule(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(REFRESH)
    }

    /** Read Froyo's list now (app opened, a Done went through, a widget was added). */
    fun refresh(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<FroyoRefreshWorker>().setConstraints(online()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(REFRESH_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** From the app's onResume: only when a widget is on the home screen. */
    fun refreshIfShown(ctx: Context) {
        if (hasWidgets(ctx)) refresh(ctx)
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun sp(ctx: Context) = Prefs(ctx).sp

    fun slotKey(ctx: Context): String? = sp(ctx).getString(SP_SLOT, null)

    fun profile(ctx: Context): Profile? = Prefs(ctx).activeProfile

    /** Reads Froyo's thread and open items from the active server. Off the main thread. */
    fun fetch(ctx: Context): Error {
        val profile = profile(ctx) ?: return saveError(ctx, Error.NO_SERVER)
        val client = AlertsClient(ctx)
        return try {
            val thread = thread(ctx, client, profile) ?: return saveError(ctx, lastAuth ?: Error.FAILED)
            val res = client.call(profile, "GET", Upcoming.dashboardPath(thread.member))
            if (res.authRequired) return saveError(ctx, Error.SIGNED_OUT)
            if (res.code != 200) return saveError(ctx, Error.FAILED)
            val items = Upcoming.extractItems(res.body) ?: return saveError(ctx, Error.FAILED)
            sp(ctx).edit {
                putString(SP_ITEMS, items)
                putLong(SP_ITEMS_AT, System.currentTimeMillis())
                putString(SP_ERROR, Error.NONE.name)
            }
            Error.NONE
        } catch (e: IOException) {
            saveError(ctx, Error.FAILED)
        } catch (e: RuntimeException) {
            // CookieManager throws while the WebView package is being updated.
            saveError(ctx, Error.FAILED)
        }
    }

    @Volatile private var lastAuth: Error? = null

    /** Froyo's pinned chat, asked once per server and then remembered. */
    fun thread(ctx: Context, client: AlertsClient, profile: Profile): io.github.rayvader.kirocrew.core.FroyoThread? {
        lastAuth = null
        val sp = sp(ctx)
        val slot = sp.getString(SP_SLOT, null)
        val member = sp.getString(SP_MEMBER, null)
        if (sp.getString(SP_PROFILE, null) == profile.id && slot != null && member != null) {
            return io.github.rayvader.kirocrew.core.FroyoThread(slot, member)
        }
        val res = client.call(profile, "POST", Upcoming.THREAD_PATH)
        if (res.authRequired) lastAuth = Error.SIGNED_OUT
        if (res.code != 200) return null
        val thread = Upcoming.parseThread(res.body) ?: return null
        sp.edit {
            putString(SP_SLOT, thread.slotKey)
            putString(SP_MEMBER, thread.member)
            putString(SP_PROFILE, profile.id)
        }
        return thread
    }

    /** Sign out: forget the list and the thread, show "Open the app to sign in". */
    fun clear(ctx: Context) {
        sp(ctx).edit {
            remove(SP_ITEMS)
            remove(SP_ITEMS_AT)
            remove(SP_HIDDEN)
            remove(SP_SLOT)
            remove(SP_MEMBER)
            remove(SP_PROFILE)
            putString(SP_ERROR, Error.SIGNED_OUT.name)
        }
        render(ctx)
    }

    private fun saveError(ctx: Context, error: Error): Error {
        sp(ctx).edit { putString(SP_ERROR, error.name) }
        return error
    }

    fun hide(ctx: Context, title: String) {
        val hidden = Upcoming.decodeHidden(sp(ctx).getString(SP_HIDDEN, null)).toMutableMap()
        hidden[title] = System.currentTimeMillis()
        sp(ctx).edit { putString(SP_HIDDEN, Upcoming.encodeHidden(hidden)) }
    }

    fun unhide(ctx: Context, title: String) {
        val hidden = Upcoming.decodeHidden(sp(ctx).getString(SP_HIDDEN, null)) - title
        sp(ctx).edit { putString(SP_HIDDEN, Upcoming.encodeHidden(hidden)) }
    }

    /** Draws every widget from the saved list. Cheap: no network. */
    fun render(ctx: Context) {
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val sp = sp(ctx)
        val zone = ZoneId.systemDefault()
        val all = sp.getString(SP_ITEMS, null)?.let { Upcoming.parseItems(it, zone) }
        val (items, keep) = Upcoming.applyHidden(
            all.orEmpty(),
            Upcoming.decodeHidden(sp.getString(SP_HIDDEN, null)),
            System.currentTimeMillis(),
        )
        if (all != null) sp.edit { putString(SP_HIDDEN, Upcoming.encodeHidden(keep)) }
        val error = runCatching { Error.valueOf(sp.getString(SP_ERROR, Error.NONE.name)!!) }.getOrDefault(Error.NONE)
        val at = sp.getLong(SP_ITEMS_AT, 0L)
        for (id in ids) {
            manager.updateAppWidget(id, views(ctx, manager.getAppWidgetOptions(id), all != null, items, error, at))
        }
    }

    private fun views(
        ctx: Context,
        options: Bundle,
        loaded: Boolean,
        items: List<UpcomingItem>,
        error: Error,
        at: Long,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_froyo)
        val fontScale = ctx.resources.configuration.fontScale
        val fit = Upcoming.fit(heightDp(ctx, options), fontScale)
        val profile = profile(ctx)
        val open = openApp(ctx, profile, Upcoming.chatPagePath(slotKey(ctx)), 0)
        v.setOnClickPendingIntent(R.id.froyo_root, open)

        // Header: name and count, plus "as of HH:mm" when the last read failed.
        var count = ctx.getString(R.string.froyo_open, items.size)
        if (error != Error.NONE && loaded && at > 0) {
            val time = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
            count = ctx.getString(R.string.froyo_as_of, count, time)
        }
        v.setViewVisibility(R.id.froyo_header, if (fit.header) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.froyo_count, count)

        val message = when {
            loaded && items.isEmpty() -> ctx.getString(R.string.froyo_empty)
            loaded -> null
            error == Error.NO_SERVER -> ctx.getString(R.string.froyo_no_server)
            error == Error.SIGNED_OUT -> ctx.getString(R.string.froyo_signed_out)
            error == Error.FAILED -> ctx.getString(R.string.froyo_failed)
            else -> ctx.getString(R.string.froyo_loading)
        }
        v.setViewVisibility(R.id.froyo_message, if (message != null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.froyo_message, message ?: "")

        val now = LocalDateTime.now()
        val locale = Locale.getDefault()
        val normal = ContextCompat.getColor(ctx, R.color.widget_muted)
        val soon = ContextCompat.getColor(ctx, R.color.widget_soon)
        val late = ContextCompat.getColor(ctx, R.color.widget_late)
        for (i in rowIds.indices) {
            val item = items.getOrNull(i)
            if (item == null || i >= fit.rows) {
                v.setViewVisibility(rowIds[i], View.GONE)
                continue
            }
            v.setViewVisibility(rowIds[i], View.VISIBLE)
            v.setTextViewText(iconIds[i], Upcoming.icon(item.kind))
            val due = Upcoming.dueText(item, now, locale)
            v.setTextViewText(dueIds[i], due)
            v.setViewVisibility(dueIds[i], if (due.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextColor(
                dueIds[i],
                when (Upcoming.state(item, now)) {
                    DueState.LATE -> late
                    DueState.SOON -> soon
                    else -> normal
                },
            )
            v.setTextViewText(titleIds[i], item.title)
            v.setContentDescription(rowIds[i], ctx.getString(R.string.froyo_row_desc, item.title, due))
            v.setOnClickPendingIntent(rowIds[i], openApp(ctx, profile, Upcoming.chatPagePath(slotKey(ctx)), i + 1))
            v.setContentDescription(doneIds[i], ctx.getString(R.string.froyo_done_desc, item.title))
            v.setOnClickPendingIntent(doneIds[i], doneIntent(ctx, item.title, i))
        }
        // No header (4x1): the count goes on the one row instead, so it is not lost.
        v.setViewVisibility(R.id.froyo_row_count, if (!fit.header && items.isNotEmpty()) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.froyo_row_count, ctx.getString(R.string.froyo_open, items.size))
        return v
    }

    /** The widget's height in dp: the portrait height on a phone held upright, else the landscape one. */
    private fun heightDp(ctx: Context, options: Bundle): Float {
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val h = if (portrait) max else min
        return (if (h > 0) h else if (min > 0) min else DEFAULT_HEIGHT_DP).toFloat()
    }

    private const val DEFAULT_HEIGHT_DP = 110

    private fun openApp(ctx: Context, profile: Profile?, path: String, code: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("kirocrew-widget://froyo/$code"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_PATH, path)
        profile?.let { intent.putExtra(MainActivity.EXTRA_PROFILE, it.id) }
        return PendingIntent.getActivity(ctx, 7000 + code, intent, PI_FLAGS)
    }

    private fun doneIntent(ctx: Context, title: String, row: Int): PendingIntent {
        val intent = Intent(ctx, FroyoDoneActivity::class.java)
            .setData(Uri.parse("kirocrew-widget://froyo-done/$row"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(FroyoDoneActivity.EXTRA_TITLE, title)
        return PendingIntent.getActivity(ctx, 7100 + row, intent, PI_FLAGS)
    }

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Settings > Add Froyo widget: asks the launcher to place it. False when the launcher cannot. */
    fun requestPin(ctx: Context): Boolean {
        val manager = AppWidgetManager.getInstance(ctx)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(ctx, FroyoWidgetProvider::class.java), null, null)
    }
}

/** Reads Froyo's list and redraws the widgets. */
class FroyoRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        FroyoWidgets.fetch(applicationContext)
        FroyoWidgets.render(applicationContext)
        return Result.success()
    }
}

/**
 * Marking an item done: hidden at once, then after the undo window the chat message
 * goes to Froyo's own thread. Undo inside the window sends nothing; after it, "Undo done".
 */
object FroyoDone {
    const val UNDO_MS = 5_000L
    private const val SP_ATTEMPTED = "froyo_done_attempted_"

    private fun work(title: String) = "froyo-done:$title"

    fun markDone(ctx: Context, title: String) {
        FroyoWidgets.hide(ctx, title)
        Prefs(ctx).sp.edit { remove(SP_ATTEMPTED + title) }
        FroyoWidgets.render(ctx)
        send(ctx, title, Upcoming.doneMessage(title), UNDO_MS, undo = false)
    }

    /** Off the main thread: waits for the cancel to land before deciding. */
    fun undo(ctx: Context, title: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(work(title)).result.get()
        FroyoWidgets.unhide(ctx, title)
        FroyoWidgets.render(ctx)
        if (Prefs(ctx).sp.getBoolean(SP_ATTEMPTED + title, false)) {
            send(ctx, title, Upcoming.undoMessage(title), 0L, undo = true)
        }
    }

    private fun send(ctx: Context, title: String, message: String, delayMs: Long, undo: Boolean) {
        val request = OneTimeWorkRequestBuilder<FroyoSendWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(FroyoSendWorker.TITLE to title, FroyoSendWorker.MESSAGE to message, FroyoSendWorker.UNDO to undo))
            .build()
        val name = if (undo) work(title) + ":undo" else work(title)
        WorkManager.getInstance(ctx).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun attempted(ctx: Context, title: String) = Prefs(ctx).sp.edit { putBoolean(SP_ATTEMPTED + title, true) }
}

/** Sends one chat message to Froyo's thread with POST /api/chat, then reads the list again. */
class FroyoSendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val title = inputData.getString(TITLE) ?: return Result.failure()
        val message = inputData.getString(MESSAGE) ?: return Result.failure()
        val undo = inputData.getBoolean(UNDO, false)
        if (isStopped) return Result.failure()
        val profile = FroyoWidgets.profile(ctx) ?: return giveUp(ctx, title, undo)
        val client = AlertsClient(ctx)
        return try {
            val thread = FroyoWidgets.thread(ctx, client, profile) ?: return retryOrGiveUp(ctx, title, undo)
            if (!undo) FroyoDone.attempted(ctx, title)
            val res = client.call(profile, "POST", Upcoming.CHAT_PATH, Upcoming.chatBody(thread.slotKey, message))
            if (res.authRequired) return giveUp(ctx, title, undo)
            if (!Upcoming.chatAccepted(res.code, res.contentType, res.body)) return retryOrGiveUp(ctx, title, undo)
            FroyoWidgets.refresh(ctx)
            Result.success()
        } catch (e: IOException) {
            retryOrGiveUp(ctx, title, undo)
        } catch (e: RuntimeException) {
            retryOrGiveUp(ctx, title, undo)
        }
    }

    private fun retryOrGiveUp(ctx: Context, title: String, undo: Boolean): Result =
        if (runAttemptCount < MAX_TRIES) Result.retry() else giveUp(ctx, title, undo)

    /** The done never reached Froyo: show the row again so nothing looks done that is not. */
    private fun giveUp(ctx: Context, title: String, undo: Boolean): Result {
        if (!undo) {
            FroyoWidgets.unhide(ctx, title)
            FroyoWidgets.render(ctx)
        }
        return Result.failure()
    }

    companion object {
        const val TITLE = "title"
        const val MESSAGE = "message"
        const val UNDO = "undo"
        private const val MAX_TRIES = 4
    }
}
