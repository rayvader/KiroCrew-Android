package io.github.rayvader.kirocrew

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
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
import io.github.rayvader.kirocrew.core.AutoText
import io.github.rayvader.kirocrew.core.DayRow
import io.github.rayvader.kirocrew.core.FailReason
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.SyncNote
import io.github.rayvader.kirocrew.core.Tone
import io.github.rayvader.kirocrew.core.UpcomingDays
import io.github.rayvader.kirocrew.core.Upcoming
import io.github.rayvader.kirocrew.core.UpcomingItem
import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.ZoneId
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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FroyoWidgets.ACTION_REFRESH) {
            FroyoWidgets.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }
}

/** The widget's saved list, what it shows, and when it refreshes. Android calls only at run time. */
object FroyoWidgets {
    private const val REFRESH = "froyo-widget-refresh"
    private const val REFRESH_NOW = "froyo-widget-refresh-now"
    private const val SP_ITEMS = "froyo_items"
    private const val SP_ITEMS_AT = "froyo_items_at"
    private const val SP_ERROR = "froyo_error"
    private const val SP_REFRESHING_AT = "froyo_refreshing_at"
    private const val SP_REASON = "froyo_fail_reason"

    /** The header's refresh button. Only this app can send it: the button's intent is explicit. */
    const val ACTION_REFRESH = "io.github.rayvader.kirocrew.FROYO_REFRESH"
    private const val SP_HIDDEN = "froyo_hidden"
    private const val SP_SLOT = "froyo_slot"
    private const val SP_MEMBER = "froyo_member"
    private const val SP_PROFILE = "froyo_profile"


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

    /** The refresh button: show "Updating…" at once, then read the list now. */
    fun refreshNow(ctx: Context) {
        sp(ctx).edit { putLong(SP_REFRESHING_AT, System.currentTimeMillis()) }
        render(ctx)
        // No network wait: offline, the read fails at once and the header says "as of HH:mm"
        // instead of spinning until the network comes back.
        val request = OneTimeWorkRequestBuilder<FroyoRefreshWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(REFRESH_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** A read finished, good or not: stop showing "Updating…". */
    fun refreshDone(ctx: Context) {
        sp(ctx).edit { remove(SP_REFRESHING_AT) }
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
        val profile = profile(ctx) ?: return saveError(ctx, Error.NO_SERVER, null, "no server")
        val client = AlertsClient(ctx)
        return try {
            var thread = thread(ctx, client, profile)
                ?: return failThread(ctx)
            var res = client.call(profile, "GET", Upcoming.dashboardPath(thread.member), readTimeoutMs = DASHBOARD_READ_TIMEOUT_MS)
            if (!res.authRequired && !Upcoming.isHtml(res.contentType) && Upcoming.threadMayBeStale(res.code)) {
                // The remembered member or slot may be gone: ask for the thread again, once.
                forgetThread(ctx)
                thread = thread(ctx, client, profile) ?: return failThread(ctx)
                res = client.call(profile, "GET", Upcoming.dashboardPath(thread.member), readTimeoutMs = DASHBOARD_READ_TIMEOUT_MS)
            }
            val items = if (res.code == 200 && !res.authRequired && !Upcoming.isHtml(res.contentType)) Upcoming.extractItems(res.body) else null
            val reason = Upcoming.failReason(res.code, res.authRequired, items != null, res.contentType)
            if (reason != null || items == null) {
                val error = if (reason == FailReason.SIGN_IN) Error.SIGNED_OUT else Error.FAILED
                return saveError(ctx, error, reason ?: FailReason.FORMAT, "HTTP ${res.code}")
            }
            sp(ctx).edit {
                putString(SP_ITEMS, items)
                putLong(SP_ITEMS_AT, System.currentTimeMillis())
                putString(SP_ERROR, Error.NONE.name)
                remove(SP_REASON)
            }
            Error.NONE
        } catch (e: SocketTimeoutException) {
            saveError(ctx, Error.FAILED, FailReason.TIMEOUT, e.javaClass.simpleName)
        } catch (e: IOException) {
            saveError(ctx, Error.FAILED, FailReason.NETWORK, e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // CookieManager throws while the WebView package is being updated.
            saveError(ctx, Error.FAILED, FailReason.NETWORK, e.javaClass.simpleName)
        }
    }

    private fun failThread(ctx: Context): Error {
        val auth = lastAuth
        return if (auth == Error.SIGNED_OUT) {
            saveError(ctx, Error.SIGNED_OUT, FailReason.SIGN_IN, "thread: sign-in")
        } else {
            saveError(ctx, Error.FAILED, lastThreadReason ?: FailReason.SERVER, "thread")
        }
    }

    private fun forgetThread(ctx: Context) {
        sp(ctx).edit {
            remove(SP_SLOT)
            remove(SP_MEMBER)
            remove(SP_PROFILE)
        }
    }

    /** The dashboard composes Froyo's whole page per read, which can be slow over a far link. */
    private const val DASHBOARD_READ_TIMEOUT_MS = 45_000

    @Volatile private var lastThreadReason: FailReason? = null

    @Volatile private var lastAuth: Error? = null

    /** Froyo's pinned chat, asked once per server and then remembered. */
    fun thread(ctx: Context, client: AlertsClient, profile: Profile): io.github.rayvader.kirocrew.core.FroyoThread? {
        lastAuth = null
        lastThreadReason = null
        val sp = sp(ctx)
        val slot = sp.getString(SP_SLOT, null)
        val member = sp.getString(SP_MEMBER, null)
        if (sp.getString(SP_PROFILE, null) == profile.id && slot != null && member != null) {
            return io.github.rayvader.kirocrew.core.FroyoThread(slot, member)
        }
        val res = client.call(profile, "POST", Upcoming.THREAD_PATH)
        if (res.authRequired) lastAuth = Error.SIGNED_OUT
        if (res.code != 200 || Upcoming.isHtml(res.contentType)) {
            lastThreadReason = Upcoming.failReason(res.code, res.authRequired, false, res.contentType)
            if (Upcoming.isHtml(res.contentType)) lastAuth = null
            return null
        }
        val thread = Upcoming.parseThread(res.body)
        if (thread == null) {
            lastThreadReason = FailReason.FORMAT
            return null
        }
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
            putString(SP_REASON, FailReason.SIGN_IN.name)
        }
        render(ctx)
    }

    private fun saveError(ctx: Context, error: Error, reason: FailReason? = null, detail: String = ""): Error {
        // In logcat too, so a stuck widget can be traced: adb logcat -s FroyoWidget
        Log.w(TAG, "read failed: ${error.name} ${reason?.word ?: "-"} $detail")
        sp(ctx).edit {
            putString(SP_ERROR, error.name)
            if (reason != null) putString(SP_REASON, reason.name) else remove(SP_REASON)
        }
        return error
    }

    private const val TAG = "FroyoWidget"

    private fun savedReason(ctx: Context): FailReason? =
        sp(ctx).getString(SP_REASON, null)?.let { runCatching { FailReason.valueOf(it) }.getOrNull() }

    fun hide(ctx: Context, title: String) {
        val hidden = Upcoming.decodeHidden(sp(ctx).getString(SP_HIDDEN, null)).toMutableMap()
        hidden[title] = System.currentTimeMillis()
        sp(ctx).edit { putString(SP_HIDDEN, Upcoming.encodeHidden(hidden)) }
    }

    fun unhide(ctx: Context, title: String) {
        val hidden = Upcoming.decodeHidden(sp(ctx).getString(SP_HIDDEN, null)) - title
        sp(ctx).edit { putString(SP_HIDDEN, Upcoming.encodeHidden(hidden)) }
    }

    /** The saved list minus rows marked done on the phone. Drops hidden marks Froyo has cleared. */
    fun visibleItems(ctx: Context): Pair<Boolean, List<UpcomingItem>> {
        val sp = sp(ctx)
        val all = sp.getString(SP_ITEMS, null)?.let { Upcoming.parseItems(it, ZoneId.systemDefault()) }
        val (items, keep) = Upcoming.applyHidden(
            all.orEmpty(),
            Upcoming.decodeHidden(sp.getString(SP_HIDDEN, null)),
            System.currentTimeMillis(),
        )
        if (all != null) sp.edit { putString(SP_HIDDEN, Upcoming.encodeHidden(keep)) }
        return (all != null) to items
    }

    /** Draws every widget from the saved list, and tells each list to reload. Cheap: no network. */
    fun render(ctx: Context) {
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val sp = sp(ctx)
        val (loaded, items) = visibleItems(ctx)
        val error = runCatching { Error.valueOf(sp.getString(SP_ERROR, Error.NONE.name)!!) }.getOrDefault(Error.NONE)
        val at = sp.getLong(SP_ITEMS_AT, 0L)
        for (id in ids) {
            manager.updateAppWidget(id, views(ctx, id, manager.getAppWidgetOptions(id), loaded, items, error, at))
        }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.froyo_list)
    }

    private fun views(
        ctx: Context,
        id: Int,
        options: Bundle,
        loaded: Boolean,
        items: List<UpcomingItem>,
        error: Error,
        at: Long,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_froyo)
        val profile = profile(ctx)
        val scale = textScale(ctx)
        v.setTextViewTextSize(R.id.froyo_name, TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        v.setTextViewTextSize(R.id.froyo_count, TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        v.setTextViewTextSize(R.id.froyo_message, TypedValue.COMPLEX_UNIT_SP, 13f * scale)
        v.setOnClickPendingIntent(R.id.froyo_root, openApp(ctx, profile, Upcoming.chatPagePath(slotKey(ctx)), 0))

        // Header: name and count, then "Updated HH:mm" after a good read, "as of HH:mm" after a
        // failed one, or "Updating…" while the refresh button's read runs. It shares the header
        // line, so no row is given up for it.
        val open = ctx.getString(R.string.froyo_open, items.size)
        val refreshing = Upcoming.isRefreshing(sp(ctx).getLong(SP_REFRESHING_AT, 0L), System.currentTimeMillis())
        val line = Upcoming.syncLine(refreshing, error != Error.NONE, loaded, at, ZoneId.systemDefault(), savedReason(ctx))
        val count = when (line.note) {
            SyncNote.UPDATED -> ctx.getString(R.string.froyo_updated, open, line.time)
            SyncNote.AS_OF -> line.reason?.let { ctx.getString(R.string.froyo_as_of_why, open, line.time, it.word) }
                ?: ctx.getString(R.string.froyo_as_of, open, line.time)
            SyncNote.UPDATING -> ctx.getString(R.string.froyo_updating_count, open)
            SyncNote.NONE -> open
        }
        v.setTextViewText(R.id.froyo_count, count)
        v.setViewVisibility(R.id.froyo_refresh, if (refreshing) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.froyo_spinner, if (refreshing) View.VISIBLE else View.GONE)
        v.setOnClickPendingIntent(R.id.froyo_refresh, refreshIntent(ctx))

        val message = when {
            loaded && items.isEmpty() -> ctx.getString(R.string.froyo_empty)
            loaded -> null
            error == Error.NO_SERVER -> ctx.getString(R.string.froyo_no_server)
            error == Error.SIGNED_OUT -> ctx.getString(R.string.froyo_signed_out)
            error == Error.FAILED -> savedReason(ctx)?.let { ctx.getString(R.string.froyo_failed_why, it.word) }
                ?: ctx.getString(R.string.froyo_failed)
            else -> ctx.getString(R.string.froyo_loading)
        }
        v.setViewVisibility(R.id.froyo_message, if (message != null) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.froyo_message, message ?: "")

        // Every item, in one list that scrolls: a header per day, then that day's cards. Taps
        // fill in this one template (Android allows one per list): a card opens the app, its
        // Done chip asks to mark it done.
        v.setPendingIntentTemplate(R.id.froyo_list, tapTemplate(ctx))
        v.setViewVisibility(R.id.froyo_list, if (items.isEmpty()) View.GONE else View.VISIBLE)
        if (Build.VERSION.SDK_INT >= 31) {
            val rows = dayRows(items)
            val builder = RemoteViews.RemoteCollectionItems.Builder()
                .setViewTypeCount(VIEW_TYPES)
                .setHasStableIds(false)
            rows.forEachIndexed { i, row -> builder.addItem(i.toLong(), rowViews(ctx, row, scale)) }
            v.setRemoteAdapter(R.id.froyo_list, builder.build())
        } else {
            val adapter = Intent(ctx, FroyoWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.froyo_list, adapter)
        }
        return v
    }

    /** Day headers and cards for [items], in the phone's time and language. */
    fun dayRows(items: List<UpcomingItem>): List<DayRow> =
        UpcomingDays.rows(items, LocalDateTime.now(), Locale.getDefault())

    /** A day header and an item card: the two kinds of line in the list. */
    const val VIEW_TYPES = 2

    /**
     * The widget follows the app's Auto text size (bigger when folded) on top of the system
     * font size, which sp already includes.
     */
    fun textScale(ctx: Context): Float {
        if (!Prefs(ctx).autoTextSize) return 1f
        return AutoText.percent(ctx.resources.configuration.smallestScreenWidthDp) / 100f
    }

    /** One line of the list: a day header, or an item card. */
    fun rowViews(ctx: Context, row: DayRow, scale: Float): RemoteViews = when (row) {
        is DayRow.Header -> {
            val v = RemoteViews(ctx.packageName, R.layout.widget_froyo_day)
            val text = SpannableString(row.label)
            if (row.today) text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.setTextViewText(R.id.froyo_day, text)
            v.setTextColor(
                R.id.froyo_day,
                ContextCompat.getColor(ctx, if (row.today) R.color.widget_day_today else R.color.widget_day_other),
            )
            v.setTextViewTextSize(R.id.froyo_day, TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            v
        }
        is DayRow.Card -> cardViews(ctx, row, scale)
    }

    private fun cardViews(ctx: Context, row: DayRow.Card, scale: Float): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_froyo_item)
        val item = row.item
        v.setInt(R.id.froyo_item, "setBackgroundResource", cardBackground(row.tone))
        v.setTextColor(R.id.froyo_done, ContextCompat.getColor(ctx, toneColor(row.tone)))
        v.setTextViewText(R.id.froyo_time, row.timeLine)
        v.setViewVisibility(R.id.froyo_time, if (row.timeLine.isEmpty()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.froyo_icon, Upcoming.icon(item.kind))
        v.setTextViewText(R.id.froyo_title, item.title)
        v.setTextViewTextSize(R.id.froyo_time, TypedValue.COMPLEX_UNIT_SP, 11f * scale)
        v.setTextViewTextSize(R.id.froyo_icon, TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        v.setTextViewTextSize(R.id.froyo_title, TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        v.setTextViewTextSize(R.id.froyo_done, TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        v.setContentDescription(R.id.froyo_item, ctx.getString(R.string.froyo_row_desc, item.title, row.timeLine))
        v.setOnClickFillInIntent(
            R.id.froyo_item,
            Intent().putExtra(FroyoDoneActivity.EXTRA_OPEN_PATH, Upcoming.chatPagePath(slotKey(ctx)))
                .also { i -> profile(ctx)?.let { i.putExtra(MainActivity.EXTRA_PROFILE, it.id) } },
        )
        v.setContentDescription(R.id.froyo_done, ctx.getString(R.string.froyo_done_desc, item.title))
        v.setOnClickFillInIntent(R.id.froyo_done, Intent().putExtra(FroyoDoneActivity.EXTRA_TITLE, item.title))
        return v
    }

    private fun cardBackground(tone: Tone): Int = when (tone) {
        Tone.LATE -> R.drawable.widget_card_late
        Tone.EVENT -> R.drawable.widget_card_event
        Tone.FLIGHT -> R.drawable.widget_card_flight
        Tone.DEADLINE -> R.drawable.widget_card_deadline
        Tone.BILL -> R.drawable.widget_card_bill
        Tone.OTHER -> R.drawable.widget_card_other
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.LATE -> R.color.widget_tone_late
        Tone.EVENT -> R.color.widget_tone_event
        Tone.FLIGHT -> R.color.widget_tone_flight
        Tone.DEADLINE -> R.color.widget_tone_deadline
        Tone.BILL -> R.color.widget_tone_bill
        Tone.OTHER -> R.color.widget_tone_other
    }


    private fun openApp(ctx: Context, profile: Profile?, path: String, code: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("kirocrew-widget://froyo/$code"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_PATH, path)
        profile?.let { intent.putExtra(MainActivity.EXTRA_PROFILE, it.id) }
        return PendingIntent.getActivity(ctx, 7000 + code, intent, PI_FLAGS)
    }

    /**
     * The list's one tap template, to FroyoDoneActivity. Each row fills in either a path to
     * open (row tap) or a title to mark done (Done chip). Mutable so the fill-in can land; it
     * names its own component, which a fill-in cannot change.
     */
    private fun tapTemplate(ctx: Context): PendingIntent {
        val intent = Intent(ctx, FroyoDoneActivity::class.java)
            .setData(Uri.parse("kirocrew-widget://froyo-tap"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(ctx, 7100, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun refreshIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, FroyoWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(ctx, 7200, intent, PI_FLAGS)
    }

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
        try {
            FroyoWidgets.fetch(applicationContext)
        } finally {
            FroyoWidgets.refreshDone(applicationContext)
            FroyoWidgets.render(applicationContext)
        }
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

/** Feeds the widget's scrolling list: every open item, from the saved list. */
class FroyoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        FroyoListFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}

class FroyoListFactory(private val ctx: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    @Volatile private var rows: List<DayRow> = emptyList()
    @Volatile private var scale = 1f

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = FroyoWidgets.dayRows(FroyoWidgets.visibleItems(ctx).second)
        scale = FroyoWidgets.textScale(ctx)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_froyo_day)
        return FroyoWidgets.rowViews(ctx, row, scale)
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = FroyoWidgets.VIEW_TYPES

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
