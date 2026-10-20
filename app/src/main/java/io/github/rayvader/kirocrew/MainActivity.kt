package io.github.rayvader.kirocrew

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.Lifecycle
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.journeyapps.barcodescanner.ScanContract
import io.github.rayvader.kirocrew.core.LiveTags
import io.github.rayvader.kirocrew.core.Profile
import io.github.rayvader.kirocrew.core.Scripts
import io.github.rayvader.kirocrew.core.Shortcut
import io.github.rayvader.kirocrew.core.UrlTools
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var app: KiroCrewApp
    private lateinit var webView: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var menuFab: FloatingActionButton
    private lateinit var messageView: View
    private lateinit var messageTitle: TextView
    private lateinit var messageBody: TextView
    private lateinit var messagePrimary: MaterialButton
    private lateinit var messageSecondary: MaterialButton
    private lateinit var messageHelp: MaterialButton
    private lateinit var insecureHint: TextView
    private lateinit var lockView: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var appLock: AppLock
    private lateinit var downloads: DownloadHelper
    private lateinit var updates: UpdateFlow

    private var startScript: ScriptHandler? = null
    private var pageHooksKey: String? = null
    private val permissionReplies = mutableListOf<JavaScriptReplyProxy>()
    private var askingNotifyPermission = false

    private var defaultUserAgent = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var loadedProfileId: String? = null
    private var appliedStyleKey: String? = null
    private var appliedDesktopMode: Boolean? = null
    private var navShortcuts: List<Shortcut> = emptyList()
    private var updatingNav = false
    private var lastBackAt = 0L
    private var mainFrameError = false
    private var failingUrl: String? = null
    private var webViewGone = false

    @Volatile private var currentServerBase: String? = null
    @Volatile private var currentPageUrl: String? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(parseFileResult(result.resultCode, result.data))
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val request = pendingPermission ?: return@registerForActivityResult
            pendingPermission = null
            val allowed = request.resources.filter { res ->
                val perm = androidPermissionFor(res)
                perm != null && (granted[perm] == true || hasPermission(perm))
            }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScannedText(it) }
    }

    private val notifyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            askingNotifyPermission = false
            val state = notifyPermissionState()
            permissionReplies.forEach { reply -> reply.postMessage(permissionMessage(state)) }
            permissionReplies.clear()
            AlertScheduler.apply(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        app = application as KiroCrewApp
        Theming.applyAccent(this, prefs)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appliedStyleKey = Theming.styleKey(prefs)
        bindViews()
        setSupportActionBar(toolbar)
        downloads = DownloadHelper(this)
        updates = UpdateFlow(this)
        appLock = AppLock(this, ::onUnlocked, ::onUnlockFailed)
        configureWebView()
        refreshPageHooks()
        setupBackHandling()
        swipe.setOnRefreshListener { webView.reload() }
        menuFab.setOnClickListener { showAppMenu(it) }
        insecureHint.setOnClickListener { it.isVisible = false }
        messageHelp.setOnClickListener { ConnectHelp.show(this) }
        findViewById<MaterialButton>(R.id.unlock_button).setOnClickListener { promptUnlock() }
        bottomNav.setOnItemSelectedListener { item ->
            if (!updatingNav) navShortcuts.getOrNull(item.itemId - NAV_ID_BASE)?.let { navigateTo(it.path) }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            navShortcuts.getOrNull(item.itemId - NAV_ID_BASE)?.let { navigateTo(it.path) }
        }

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (restored) {
            loadedProfileId = savedInstanceState?.getString(STATE_PROFILE)
            val profile = prefs.profiles.firstOrNull { it.id == loadedProfileId }
            currentServerBase = profile?.baseUrl
            // The rebuilt screen (after a theme or accent change) starts with the
            // default title; show the server's name and HTTP warning again.
            if (profile != null) {
                supportActionBar?.title = profile.name
                insecureHint.isVisible = UrlTools.isUnencryptedPublic(profile.baseUrl)
            }
        } else {
            loadInitial(intent)
            if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) updates.checkNow()
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        webView = findViewById(R.id.webview)
        bottomNav = findViewById(R.id.bottom_nav)
        menuFab = findViewById(R.id.menu_fab)
        messageView = findViewById(R.id.message)
        messageTitle = findViewById(R.id.message_title)
        messageBody = findViewById(R.id.message_body)
        messagePrimary = findViewById(R.id.message_primary)
        messageSecondary = findViewById(R.id.message_secondary)
        messageHelp = findViewById(R.id.message_help)
        insecureHint = findViewById(R.id.insecure_hint)
        lockView = findViewById(R.id.lock_view)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
    }

    override fun onResume() {
        super.onResume()
        if (Theming.styleKey(prefs) != appliedStyleKey) {
            recreate()
            return
        }
        if (webViewGone) return
        applySettings()
        refreshPageHooks()
        processPendingActions()
        val active = prefs.activeProfile
        if (active == null) {
            showWelcome()
        } else if (active.id != loadedProfileId) {
            loadProfile(active, prefs.startPath())
        }
        prefs.pendingOpenUrl?.let { url ->
            prefs.pendingOpenUrl = null
            if (active != null) {
                loadedProfileId = active.id
                currentServerBase = active.baseUrl
                hideMessage()
                webView.loadUrl(url)
            }
        }
        refreshLock()
        webView.onResume()
        app.visibleProfileId = loadedProfileId
        AlertScheduler.apply(this)
        UpdateScheduler.apply(this)
        updates.onResume()
        if (!lockView.isVisible) updates.autoCheck()
    }

    override fun onPause() {
        app.visibleProfileId = null
        if (!webViewGone) webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (!webViewGone) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewGone) webView.saveState(outState)
        outState.putString(STATE_PROFILE, loadedProfileId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) updates.checkNow()
        val path = pathFromIntent(intent)
        // A notification from another saved server switches to that server.
        val target = profileFromIntent(intent)
        if (target != null && target.id != loadedProfileId) {
            loadProfile(target, path ?: prefs.startPath())
            return
        }
        path ?: return
        val active = prefs.activeProfile ?: return
        if (active.id == loadedProfileId) navigateTo(path) else loadProfile(active, path)
    }

    // ---- Loading -------------------------------------------------------------------------

    private fun loadInitial(intent: Intent?) {
        val active = profileFromIntent(intent) ?: prefs.activeProfile
        if (active == null) {
            showWelcome()
            return
        }
        loadProfile(active, pathFromIntent(intent) ?: prefs.startPath())
    }

    private fun profileFromIntent(intent: Intent?): Profile? {
        val id = intent?.getStringExtra(EXTRA_PROFILE) ?: return null
        return prefs.profiles.firstOrNull { it.id == id }
    }

    private fun pathFromIntent(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_PATH)?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme != "kirocrew") return null
        return data.getQueryParameter("path") ?: data.path?.takeIf { it.isNotBlank() && it != "/" }
    }

    private fun loadProfile(profile: Profile, path: String) {
        loadedProfileId = profile.id
        currentServerBase = profile.baseUrl
        prefs.activeProfileId = profile.id
        hideMessage()
        supportActionBar?.title = profile.name
        updateBottomNavVisibility()
        insecureHint.isVisible = UrlTools.isUnencryptedPublic(profile.baseUrl)
        if (isResumedNow()) app.visibleProfileId = profile.id
        refreshPageHooks()
        webView.loadUrl(UrlTools.buildUrl(profile.baseUrl, path))
    }

    private fun navigateTo(path: String) {
        val base = currentServerBase ?: return
        val target = UrlTools.buildUrl(base, path)
        hideMessage()
        if (!prefs.fastNav || mainFrameError) {
            webView.loadUrl(target)
            return
        }
        webView.evaluateJavascript(Scripts.spaNavigate(target)) { result ->
            if (result != "\"ok\"" && result != "\"same\"") webView.loadUrl(target)
        }
    }

    private fun processPendingActions() {
        if (prefs.pendingClearCache) {
            prefs.pendingClearCache = false
            webView.clearCache(true)
            toast(getString(R.string.cache_cleared))
        }
        if (prefs.pendingReload) {
            prefs.pendingReload = false
            webView.clearHistory()
            prefs.activeProfile?.let { loadProfile(it, prefs.startPath()) }
        }
    }

    // ---- WebView setup --------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        defaultUserAgent = s.userAgentString

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = DashboardClient()
        webView.webChromeClient = DashboardChromeClient()
        webView.addJavascriptInterface(Bridge(), Scripts.BRIDGE)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> webView.evaluateJavascript(Scripts.fetchBlob(url, name)) { r ->
                    if (r != "\"ok\"") toast(getString(R.string.download_failed, "blob"))
                }
                url.startsWith("data:") -> downloads.saveDataUrl(url, name)
                else -> downloads.enqueue(url, userAgent, contentDisposition, mimeType)
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private fun applySettings() {
        val s = webView.settings
        s.textZoom = prefs.textZoom

        val desktop = prefs.desktopMode
        s.loadWithOverviewMode = desktop
        val baseUa = if (desktop) desktopUserAgent(defaultUserAgent) else defaultUserAgent
        val ua = buildString {
            append(baseUa)
            append(" KiroCrewAndroid/").append(BuildConfig.VERSION_NAME)
            val extra = prefs.uaSuffix.trim()
            if (extra.isNotEmpty()) append(' ').append(extra)
        }
        if (s.userAgentString != ua) s.userAgentString = ua
        if (appliedDesktopMode != null && appliedDesktopMode != desktop) webView.reload()
        appliedDesktopMode = desktop

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.forceDarkPages)
        }

        toolbar.isVisible = prefs.showToolbar
        menuFab.isVisible = !prefs.showToolbar
        rebuildBottomNav()
        updateBottomNavVisibility()
        swipe.isEnabled = prefs.pullToRefresh

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (prefs.privacyScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        applySystemBars()
        updateLauncherShortcuts()
        if (currentPageIsTrusted()) injectPageScripts()
    }

    private fun desktopUserAgent(ua: String): String =
        ua.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "")
            .replace(Regex("Version/\\S+ "), "")

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val primary = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary, Color.BLACK)
        @Suppress("DEPRECATION")
        window.statusBarColor = primary
        controller.isAppearanceLightStatusBars = false
        if (prefs.fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun injectPageScripts() {
        webView.evaluateJavascript(Scripts.customCss(prefs.customCss), null)
        webView.evaluateJavascript(Scripts.DOWNLOAD_HOOKS, null)
        // Normally already installed at document start; this covers WebViews without that feature.
        webView.evaluateJavascript(Scripts.NOTIFY_SHIM, null)
        if (prefs.desktopMode) webView.evaluateJavascript(Scripts.DESKTOP_VIEWPORT, null)
    }

    // ---- Notifications from the page ---------------------------------------------------------

    /**
     * Installs the Notification stand-in and its message channel for the saved
     * servers only. WebView hands the channel to frames from those origins and
     * no others, so other sites and sandboxed frames cannot post notifications.
     */
    private fun refreshPageHooks() {
        val origins = prefs.profiles.mapNotNull { UrlTools.origin(it.baseUrl) }.toSortedSet()
        val key = origins.joinToString(" ")
        if (key == pageHooksKey) return
        pageHooksKey = key
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        startScript?.remove()
        startScript = null
        WebViewCompat.removeWebMessageListener(webView, Scripts.NOTIFY_CHANNEL)
        if (origins.isEmpty()) return
        try {
            WebViewCompat.addWebMessageListener(webView, Scripts.NOTIFY_CHANNEL, origins, ::onPageMessage)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                startScript = WebViewCompat.addDocumentStartJavaScript(webView, Scripts.NOTIFY_SHIM, origins)
            }
        } catch (e: IllegalArgumentException) {
            // An address WebView will not accept as an origin rule: no page notifications for it.
        }
    }

    private fun onPageMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        val base = currentServerBase ?: return
        if (!isMainFrame || !UrlTools.isSameServer(base, sourceOrigin.toString())) return
        val msg = try {
            JSONObject(message.data ?: return)
        } catch (e: JSONException) {
            return
        }
        val tag = msg.optString("tag", "")
        when (msg.optString("op", "")) {
            "notify" -> onPageNotification(msg.optString("title", ""), msg.optString("body", ""), tag)
            "close" -> Notifier.cancelLive(this, tag)
            "request" -> requestNotifyPermission(reply)
        }
    }

    private fun onPageNotification(title: String, body: String, tag: String) {
        if (!prefs.notifyEnabled || title.isBlank()) return
        // While you look at the dashboard it shows its own banner.
        if (isResumedNow()) return
        // Feed notes come from the background check when that is on, so they are not shown twice.
        if (!LiveTags.isSessionEvent(tag) && AlertScheduler.mode(prefs) != AlertScheduler.MODE_OFF) return
        val profiles = prefs.profiles
        Notifier.postLive(this, profiles.firstOrNull { it.id == loadedProfileId }, profiles.size > 1, title, body, tag)
    }

    /** What the page sees as Notification.permission. */
    private fun notifyPermissionState(): String = when {
        !prefs.notifyEnabled -> "denied"
        Notifier.canPost(this) -> "granted"
        Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifyPermission -> "default"
        else -> "denied"
    }

    private fun permissionMessage(state: String) =
        JSONObject().put("op", "permission").put("value", state).toString()

    private fun requestNotifyPermission(reply: JavaScriptReplyProxy?) {
        val state = notifyPermissionState()
        if (state != "default" || Build.VERSION.SDK_INT < 33) {
            reply?.postMessage(permissionMessage(state))
            return
        }
        reply?.let { permissionReplies.add(it) }
        if (askingNotifyPermission) return
        askingNotifyPermission = true
        prefs.askedNotifyPermission = true
        notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks once, after the first dashboard page loads, so the phone can show alerts. */
    private fun maybeAskNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33 || prefs.askedNotifyPermission || !prefs.notifyEnabled) return
        if (lockView.isVisible || !isResumedNow() || Notifier.canPost(this)) return
        requestNotifyPermission(null)
    }

    private fun isResumedNow() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun currentPageIsTrusted(): Boolean {
        val base = currentServerBase ?: return false
        val page = currentPageUrl ?: return false
        return UrlTools.isSameServer(base, page)
    }

    // ---- Bottom bar and launcher shortcuts --------------------------------------------------

    private fun rebuildBottomNav() {
        val wanted = prefs.bottomShortcuts
        if (wanted == navShortcuts && bottomNav.menu.size() == wanted.size) return
        navShortcuts = wanted
        bottomNav.menu.clear()
        wanted.forEachIndexed { i, sc ->
            bottomNav.menu.add(Menu.NONE, NAV_ID_BASE + i, i, ShortcutUi.label(sc)).setIcon(ShortcutUi.icon(sc))
        }
        bottomNav.post {
            wanted.indices.forEach { i ->
                bottomNav.findViewById<View>(NAV_ID_BASE + i)?.setOnLongClickListener { v ->
                    showAppMenu(v)
                    true
                }
            }
        }
        currentPageUrl?.let { syncNavSelection(it) }
    }

    private fun updateBottomNavVisibility() {
        bottomNav.isVisible = prefs.showBottomBar && navShortcuts.isNotEmpty() &&
            prefs.activeProfile != null && !messageView.isVisible
    }

    private fun syncNavSelection(url: String) {
        if (navShortcuts.isEmpty()) return
        val match = Shortcut.matching(UrlTools.pathOf(url), navShortcuts) ?: return
        val id = NAV_ID_BASE + navShortcuts.indexOf(match)
        if (bottomNav.selectedItemId != id) {
            updatingNav = true
            bottomNav.selectedItemId = id
            updatingNav = false
        }
    }

    private fun updateLauncherShortcuts() {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(this).coerceIn(1, 4)
        val items = prefs.bottomShortcuts.ifEmpty { listOf(Shortcut.CHAT) }.take(max).map { sc ->
            ShortcutInfoCompat.Builder(this, "nav_${sc.key}")
                .setShortLabel(getString(ShortcutUi.label(sc)))
                .setIcon(IconCompat.createWithResource(this, ShortcutUi.icon(sc)))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(EXTRA_PATH, sc.path)
                )
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(this, items)
        } catch (e: Exception) {
            // Launcher shortcuts are a nicety; some launchers refuse them.
        }
    }

    // ---- Menus -----------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        onMenuAction(item.itemId) || super.onOptionsItemSelected(item)

    private fun showAppMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { onMenuAction(it.itemId) }
            show()
        }
    }

    private fun onMenuAction(id: Int): Boolean {
        when (id) {
            R.id.action_reload -> {
                hideMessage()
                failingUrl?.takeIf { mainFrameError }?.let { webView.loadUrl(it) } ?: webView.reload()
            }
            R.id.action_home -> prefs.activeProfile?.let { loadProfile(it, prefs.startPath()) }
            R.id.action_servers -> openProfiles(add = false)
            R.id.action_copy_link -> webView.url?.let { url ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("KiroCrew link", url))
                toast(getString(R.string.link_copied))
            }
            R.id.action_share -> webView.url?.let { url ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
            R.id.action_open_browser -> webView.url?.let { openExternal(Uri.parse(it)) }
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return false
        }
        return true
    }

    private fun openProfiles(add: Boolean) {
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_ADD, add))
    }

    // ---- Message overlay (welcome and errors) -----------------------------------------------

    private fun showWelcome() {
        loadedProfileId = null
        currentServerBase = null
        supportActionBar?.title = getString(R.string.app_name)
        showMessage(
            getString(R.string.welcome_title),
            getString(R.string.welcome_body),
            getString(R.string.add_server), { openProfiles(add = true) },
            getString(R.string.scan_qr), { launchQr() },
        )
    }

    private fun showError(description: String) {
        showMessage(
            getString(R.string.error_title),
            getString(R.string.error_body, description, currentServerBase ?: ""),
            getString(R.string.retry), { retryFailedLoad() },
            getString(R.string.servers), { openProfiles(add = false) },
        )
    }

    private fun retryFailedLoad() {
        hideMessage()
        val url = failingUrl ?: currentServerBase
        if (url != null) webView.loadUrl(url)
    }

    private fun showMessage(
        title: String,
        body: String,
        primaryLabel: String,
        primaryAction: () -> Unit,
        secondaryLabel: String,
        secondaryAction: () -> Unit,
    ) {
        messageTitle.text = title
        messageBody.text = body
        messagePrimary.text = primaryLabel
        messagePrimary.setOnClickListener { primaryAction() }
        messageSecondary.text = secondaryLabel
        messageSecondary.setOnClickListener { secondaryAction() }
        messageView.isVisible = true
        updateBottomNavVisibility()
    }

    private fun hideMessage() {
        if (!messageView.isVisible) return
        messageView.isVisible = false
        updateBottomNavVisibility()
    }

    // ---- QR --------------------------------------------------------------------------------

    private fun launchQr() {
        app.expectingExternalReturn = true
        qrLauncher.launch(ProfilesActivity.scanOptions(this))
    }

    private fun handleScannedText(text: String) {
        val target = UrlTools.parseQr(text)
        if (target == null) {
            toast(getString(R.string.qr_not_server))
            return
        }
        val list = prefs.profiles.toMutableList()
        val existing = list.firstOrNull { UrlTools.origin(it.baseUrl) == UrlTools.origin(target.baseUrl) }
        val profile = existing ?: Profile(
            UUID.randomUUID().toString(),
            UrlTools.parse(target.baseUrl)?.host ?: target.baseUrl,
            target.baseUrl,
        ).also { list.add(it) }
        prefs.profiles = list
        prefs.activeProfileId = profile.id
        loadedProfileId = profile.id
        currentServerBase = profile.baseUrl
        supportActionBar?.title = profile.name
        hideMessage()
        if (isResumedNow()) app.visibleProfileId = profile.id
        refreshPageHooks()
        webView.loadUrl(target.openUrl ?: UrlTools.buildUrl(profile.baseUrl, prefs.startPath()))
    }

    // ---- App lock --------------------------------------------------------------------------

    private fun refreshLock() {
        if (!prefs.appLock) {
            app.locked = false
            lockView.isVisible = false
            return
        }
        if (!app.locked) {
            lockView.isVisible = false
            return
        }
        if (!appLock.canUse()) {
            app.unlocked()
            lockView.isVisible = false
            toast(getString(R.string.lock_unavailable))
            return
        }
        lockView.isVisible = true
        if (app.autoPrompt && !app.authInProgress) {
            app.autoPrompt = false
            promptUnlock()
        }
    }

    private fun promptUnlock() {
        app.authInProgress = true
        appLock.show()
    }

    private fun onUnlocked() {
        app.unlocked()
        lockView.isVisible = false
        updates.autoCheck()
    }

    private fun onUnlockFailed() {
        app.authInProgress = false
    }

    // ---- Back --------------------------------------------------------------------------------

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    lockView.isVisible -> finish()
                    !messageView.isVisible && webView.canGoBack() -> webView.goBack()
                    prefs.doubleBackExit && SystemClock.elapsedRealtime() - lastBackAt > 2000 -> {
                        lastBackAt = SystemClock.elapsedRealtime()
                        toast(getString(R.string.press_back_again))
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    // ---- Links ---------------------------------------------------------------------------------

    /** Returns true when the app handled the URL itself (the WebView should not load it). */
    private fun routeUrl(uri: Uri): Boolean {
        val url = uri.toString()
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val base = currentServerBase
                if (base != null && UrlTools.isSameServer(base, url)) return false
                if (!prefs.externalInBrowser) return false
                openExternal(uri)
                return true
            }
            "kirocrew" -> {
                pathFromIntent(Intent(Intent.ACTION_VIEW, uri))?.let { navigateTo(it) }
                return true
            }
            "intent" -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    startActivity(intent)
                } catch (e: Exception) {
                    toast(getString(R.string.no_app))
                }
                return true
            }
            else -> {
                openExternal(uri)
                return true
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app))
        }
    }

    // ---- Files and permissions -----------------------------------------------------------------

    private fun parseFileResult(code: Int, data: Intent?): Array<Uri>? {
        if (code != RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return data.data?.let { arrayOf(it) }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.isVisible = false
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        applySystemBars()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- WebView clients -----------------------------------------------------------------------

    private inner class DashboardClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return routeUrl(request.url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameError = false
            currentPageUrl = url
            progress.show()
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentPageUrl = url
            swipe.isRefreshing = false
            progress.hide()
            if (!mainFrameError) hideMessage()
            if (currentPageIsTrusted()) {
                injectPageScripts()
                if (!mainFrameError) maybeAskNotifyPermission()
            }
            syncNavSelection(url)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentPageUrl = url
            syncNavSelection(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            mainFrameError = true
            failingUrl = request.url.toString()
            swipe.isRefreshing = false
            progress.hide()
            showError(error.description?.toString() ?: "")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed for memory: rebuild the screen.
            webViewGone = true
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private inner class DashboardChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.setProgressCompat(newProgress, true)
            if (newProgress >= 100) progress.hide()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            supportActionBar?.subtitle = title
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = try {
                fileChooserParams.createIntent()
            } catch (e: Exception) {
                Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                app.expectingExternalReturn = true
                fileChooser.launch(Intent.createChooser(intent, getString(R.string.choose_file)))
                true
            } catch (e: ActivityNotFoundException) {
                app.expectingExternalReturn = false
                fileCallback = null
                toast(getString(R.string.no_app))
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val base = currentServerBase
                if (base == null || !UrlTools.isSameServer(base, request.origin.toString())) {
                    request.deny()
                    return@runOnUiThread
                }
                val needed = request.resources.mapNotNull { androidPermissionFor(it) }.distinct()
                val missing = needed.filterNot { hasPermission(it) }
                if (missing.isEmpty()) {
                    val ok = request.resources.filter { androidPermissionFor(it) != null }
                    if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                } else {
                    pendingPermission?.deny()
                    pendingPermission = request
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermission == request) pendingPermission = null
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullscreenContainer.isVisible = true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() = hideCustomView()
    }

    /** Called by the page through window.KiroCrewAndroid. Runs on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun saveDataUrl(dataUrl: String?, name: String?) {
            if (!currentPageIsTrusted() || dataUrl == null) return
            downloads.saveDataUrl(dataUrl, name ?: "download")
        }

        @JavascriptInterface
        fun downloadFailed(message: String?) {
            runOnUiThread { toast(getString(R.string.download_failed, message ?: "")) }
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        /** Read by the Notification stand-in so `Notification.permission` is always current. */
        @JavascriptInterface
        fun notificationPermission(): String = notifyPermissionState()
    }

    companion object {
        const val EXTRA_PATH = "io.github.rayvader.kirocrew.PATH"
        const val EXTRA_PROFILE = "io.github.rayvader.kirocrew.PROFILE"
        const val EXTRA_SHOW_UPDATE = "io.github.rayvader.kirocrew.SHOW_UPDATE"
        private const val STATE_PROFILE = "loaded_profile"
        private const val NAV_ID_BASE = 100
    }
}
