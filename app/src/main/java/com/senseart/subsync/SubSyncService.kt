package com.senseart.subsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Сърцето на приложението. Работи като услуга за достъпност, защото само така може:
 *  - да рисува субтитри и панел над картината на друг плейър;
 *  - да получава бутоните на дистанционното, докато ViMu е отпред.
 */
class SubSyncService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** Четене/запис на файлове (може по мрежата) – една нишка, за да вървят по ред. */
    private val io = Executors.newSingleThreadExecutor()

    private var subtitleView: SubtitleView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null
    private var panelView: PanelView? = null

    private var sub: SubtitleFile? = null
    private var model = SyncModel()
    private var clock: PlaybackClock? = null

    private var sessionManager: MediaSessionManager? = null

    private var foregroundPkg: String? = null
    private var subsEnabled = true

    private enum class Page { NONE, SYNC, MENU, FILES }

    private var page = Page.NONE
    private var followCurrent = true
    private var cueSel = 0
    private var markVideoMs: Long? = null
    private var menuSel = 0
    private val browser = FileBrowser()
    private var status = ""
    private var statusUntil = 0L
    /** Бутон, чийто остатък (повторения и пускане) да не стигне до ViMu след затваряне на панела. */
    private var swallowUp = -1
    private var swallowUntil = 0L
    private var lastPanelRender = 0L
    private var lastClockCheck = 0L
    private var browseToken = 0

    /** Последният филм, за който сме търсили .srt, и намерените кандидати. */
    private var lastAutoKey: String? = null
    private var autoCandidates: List<VFile> = emptyList()

    // ---------------------------------------------------------------- жизнен цикъл

    override fun onServiceConnected() {
        running = true
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), handler)
        } catch (e: SecurityException) {
            Log.i(TAG, "Няма „Достъп до известия“ – няма откъде да се вземе позицията на филма")
        }
        addSubtitleWindow()
        connectedAt = SystemClock.uptimeMillis()
        // Кое приложение е отпред в момента на включване (събитията идват само при промяна).
        try {
            rootInActiveWindow?.packageName?.toString()?.let { pkg ->
                if (pkg != packageName && pkg != "com.android.systemui") foregroundPkg = pkg
            }
        } catch (_: Exception) {
        }
        refreshClock()
        val recent = System.currentTimeMillis() - prefs.currentVideoAt < 60_000
        if (recent) {
            // Филмът току-що е отворен през нас – търсим субтитрите му директно.
            onVideoOpened()
        } else {
            prefs.lastFile?.let { SubtitleLocator.fromPath(it, prefs.shares) }?.let { loadFile(it, quiet = true) }
        }
        handler.post(tick)
    }

    // ---------------------------------------------------------------- само докато гледам филм

    private var connectedAt = 0L

    /**
     * В режим „само докато гледам филм“: щом друго приложение (не ViMu) е на екрана,
     * услугата се изключва веднага, за да не пречи на началния екран. Ако още не се знае
     * кое е отпред, се чака до 10 с ViMu да се появи.
     */
    private fun autoDisableCheck(now: Long) {
        if (!prefs.autoA11y || prefs.learning || page != Page.NONE) return
        if (isPlayerForeground()) return
        if (foregroundPkg != null || now - connectedAt > 10_000) {
            Log.i(TAG, "ViMu не е на екрана – изключвам „Достъпност“")
            shutdown()
            disableSelf()
        }
    }

    /** Пакетът на клавиатурата на екрана – прозорците ѝ не означават, че сме излезли от ViMu. */
    private fun imePackage(): String? = try {
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    } catch (_: Exception) {
        null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        (clock as? SessionClock)?.release()
        clock = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        listOfNotNull(subtitleView, panelView).forEach {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        subtitleView = null
        panelView = null
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        if (event.className?.toString()?.contains("Toast") == true) return
        if (pkg == imePackage()) return
        foregroundPkg = pkg
        if (!running) return
        // Излизане от ViMu: затваряме панела (каквото и да е било отворено в него)
        // и изключваме „Достъпност“ веднага, без да чакаме следващия цикъл.
        if (pkg != Prefs.VIMU_PACKAGE && page != Page.NONE) resetPanel()
        autoDisableCheck(SystemClock.uptimeMillis())
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.K_TEXT_SIZE -> subtitleView?.setTextSizeSp(prefs.textSizeSp)
            Prefs.K_BOTTOM -> subtitleParams?.let {
                it.y = dp(prefs.bottomMarginDp)
                try {
                    wm.updateViewLayout(subtitleView, it)
                } catch (_: Exception) {
                }
            }

            Prefs.K_VIDEO_AT -> onVideoOpened()
            // Изчистени са всички запомнени синхронизации – нулираме и текущия филм.
            Prefs.K_SYNCS_CLEARED -> model.reset(prefs.defaultOffsetMs)
        }
    }

    // ---------------------------------------------------------------- прозорци

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(width: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    private fun addSubtitleWindow() {
        val v = SubtitleView(this)
        v.setTextSizeSp(prefs.textSizeSp)
        val w = (resources.displayMetrics.widthPixels * 0.9).toInt()
        val p = overlayParams(w, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        p.y = dp(prefs.bottomMarginDp)
        wm.addView(v, p)
        subtitleView = v
        subtitleParams = p
    }

    private fun ensurePanel(): PanelView {
        panelView?.let { return it }
        val v = PanelView(this)
        val w = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val p = overlayParams(w, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        p.y = dp(20)
        wm.addView(v, p)
        panelView = v
        return v
    }

    // ---------------------------------------------------------------- време на филма

    private fun listenerComponent() = ComponentName(this, MediaListenerService::class.java)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshClock() }

    private fun videoNow(): Long? = clock?.positionMs()

    /** Времето на филма идва само от ViMu (MediaSession). */
    private fun refreshClock() {
        if (!running) return
        val old = clock as? SessionClock
        val controllers: List<MediaController> = try {
            sessionManager?.getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            null
        } ?: emptyList()
        // Само ViMu – никога друго приложение (YouTube, началният екран с трейлъри и т.н.).
        val c = controllers.firstOrNull { it.packageName == Prefs.VIMU_PACKAGE }
        if (c != null && old != null && old.controller.sessionToken == c.sessionToken) return
        old?.release()
        clock = null
        if (c != null) {
            val sc = SessionClock(c, handler) { prefs.calibrationMs }
            clock = sc
            sc.onMetadata = { onPlayerMetadata(it) }
            onPlayerMetadata(sc.metadata)
        }
    }

    // ---------------------------------------------------------------- цикъл на изобразяване

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
            } catch (t: Throwable) {
                Log.e(TAG, "update", t)
            }
            if (running) handler.postDelayed(this, 40)
        }
    }

    private var keyFiltering: Boolean? = null

    /**
     * Бутоните на дистанционното минават през приложението САМО когато е нужно:
     * плейърът е отпред, панелът е отворен или се избира бутон. Във всички други
     * приложения системата изобщо не ги праща тук – все едно приложението го няма.
     */
    private fun updateKeyFilter() {
        val want = prefs.learning || page != Page.NONE || isPlayerForeground() || holdConsumed || holdFired
        if (want == keyFiltering) return
        val info = serviceInfo ?: return
        info.flags = if (want) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        serviceInfo = info
        keyFiltering = want
    }

    private fun update() {
        updateKeyFilter()
        val now = SystemClock.uptimeMillis()
        val s = sub
        val show = s != null && subsEnabled && (page != Page.NONE || isPlayerForeground())
        val v = videoNow()
        if (show && v != null) {
            val t = model.cueTimeAt(v)
            subtitleView?.setSubtitle(s!!.activeAt(t).joinToString("\n") { it.text })
        } else {
            subtitleView?.setSubtitle("")
        }
        if (page != Page.NONE && now - lastPanelRender > 200) renderPanel()
        if (now - lastClockCheck > 3000) {
            lastClockCheck = now
            if (clock == null) refreshClock()
        }
        if (foregroundPkg == null) {
            try {
                rootInActiveWindow?.packageName?.toString()?.let { pkg ->
                    if (pkg != packageName && pkg != "com.android.systemui") foregroundPkg = pkg
                }
            } catch (_: Exception) {
            }
        }
        autoDisableCheck(now)
    }

    private fun flash(msg: String) {
        status = msg
        statusUntil = SystemClock.uptimeMillis() + (7000L + msg.length * 60L).coerceAtMost(30_000L)
        if (page == Page.NONE) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else renderPanel()
    }

    // ---------------------------------------------------------------- бутони

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!::prefs.isInitialized) return false
        val code = e.keyCode
        if (code == swallowUp && SystemClock.uptimeMillis() < swallowUntil) {
            if (e.action == KeyEvent.ACTION_UP) swallowUp = -1
            return true
        }
        // Бутон, който сами „натиснахме“ (кратко натискане при режим „задържане“) – пропускаме го.
        if (code == replayKey && SystemClock.uptimeMillis() < replayUntil) {
            if (e.action == KeyEvent.ACTION_UP) replayKey = -1
            return false
        }
        if (prefs.learning) return learnKey(e)

        val hot = prefs.hotkey
        // Бутонът за панела се прихваща само докато плейърът е на екрана (или панелът е отворен).
        // Навсякъде другаде дистанционното работи без никаква намеса.
        if (hot != 0 && code == hot && (page != Page.NONE || isPlayerForeground() || holdConsumed || holdFired)) {
            if (prefs.holdHotkey) return holdHotkey(e)
            if (e.action == KeyEvent.ACTION_UP) onHotkey()
            return true
        }
        return dispatchToPage(e)
    }

    /** Пакетите на плейъра: ViMu и приложението, чиято позиция четем (MediaSession). */
    /** Дали ViMu е на екрана. Само тогава приложението показва субтитри и слуша дистанционното. */
    private fun isPlayerForeground(): Boolean {
        val fg = foregroundPkg
        if (fg != null) return fg == Prefs.VIMU_PACKAGE
        // Още не знаем кое е отпред (току-що стартирала услуга): само ако ViMu в момента пуска филм.
        val sc = clock as? SessionClock ?: return false
        return sc.controller.packageName == Prefs.VIMU_PACKAGE && sc.isPlaying()
    }

    private fun dispatchToPage(e: KeyEvent): Boolean {
        // Аварийно излизане: задържан BACK (~1 с) затваря панела от всяко място.
        if (page != Page.NONE && e.keyCode == KeyEvent.KEYCODE_BACK &&
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount >= 15
        ) {
            closePanel(swallowKey = KeyEvent.KEYCODE_BACK)
            return true
        }
        return when (page) {
            Page.NONE -> {
                false
            }

            Page.SYNC -> keySync(e)
            Page.MENU -> keyMenu(e)
            Page.FILES -> keyFiles(e)
        }
    }

    // ---------------------------------------------------------------- задържане на бутона

    private var holdConsumed = false
    private var holdFired = false
    private var holdEvent: KeyEvent? = null
    private var replayKey = -1
    private var replayUntil = 0L

    private val holdRunnable = Runnable {
        holdFired = true
        onHotkey()
    }

    /**
     * Режим „задържане“: задържане ~0.7 с отваря панела (или менюто, ако панелът е отворен).
     * Кратко натискане:
     *  - OK/BACK: изяждаме го и веднага го „натискаме“ сами – плейърът получава нормален бутон;
     *  - други бутони (Android не позволява да ги натиснем сами): пропускаме натискането към плейъра,
     *    а при задържане отваряме панела и изяждаме остатъка.
     *  - докато панелът е отворен, краткото натискане действа като в панела.
     */
    private fun holdHotkey(e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return holdConsumed || holdFired
                holdFired = false
                holdEvent = e
                holdConsumed = page != Page.NONE || Keys.replayAction(e.keyCode) != null
                handler.removeCallbacks(holdRunnable)
                handler.postDelayed(holdRunnable, HOLD_MS)
                return holdConsumed
            }

            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (holdFired) {
                    holdFired = false
                    holdConsumed = false
                    return true
                }
                if (!holdConsumed) return false
                holdConsumed = false
                if (page != Page.NONE) {
                    val down = holdEvent ?: e
                    dispatchToPage(KeyEvent.changeAction(down, KeyEvent.ACTION_DOWN))
                    dispatchToPage(e)
                } else {
                    replay(e.keyCode)
                }
                return true
            }
        }
        return false
    }

    private fun replay(code: Int) {
        val action = Keys.replayAction(code) ?: return
        replayKey = if (Keys.isOk(code)) KeyEvent.KEYCODE_DPAD_CENTER else code
        replayUntil = SystemClock.uptimeMillis() + 800
        // Асинхронно: onKeyEvent трябва да върне веднага, иначе системата забавя бутоните.
        handler.post { performGlobalAction(action) }
    }

    private fun learnKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_UP) return true
        if (Keys.isForbiddenHotkey(e.keyCode, prefs.holdHotkey)) {
            Toast.makeText(this, "Този бутон е нужен за управлението – изберете друг", Toast.LENGTH_SHORT).show()
            return true
        }
        prefs.hotkey = e.keyCode
        prefs.learning = false
        Toast.makeText(this, "Бутон за панела: ${Keys.name(e.keyCode)}", Toast.LENGTH_LONG).show()
        return true
    }


    private fun onHotkey() {
        when (page) {
            Page.NONE -> {
                refreshClock()
                ensurePanel().visibility = View.VISIBLE
                if (sub == null || currentFileLooksWrong()) openFiles() else openSync()
            }

            Page.SYNC -> {
                page = Page.MENU; renderPanel()
            }

            Page.MENU -> openSync()
            Page.FILES -> if (sub != null) openSync() else closePanel()
        }
    }

    private fun openSync() {
        saveSnapshot = null
        page = Page.SYNC
        followCurrent = true
        markVideoMs = null
        renderPanel()
    }

    private fun closePanel(swallowKey: Int = -1) {
        saveSnapshot = null
        page = Page.NONE
        followCurrent = true
        markVideoMs = null
        browseToken++ // недовършено зареждане на папка да не отвори панела наново
        panelView?.visibility = View.GONE
        if (swallowKey >= 0) swallowRest(swallowKey)
    }

    private fun swallowRest(code: Int) {
        swallowUp = code
        swallowUntil = SystemClock.uptimeMillis() + 3000
    }

    /** Пълно нулиране на панела – при излизане от ViMu и при нов филм. */
    private fun resetPanel() {
        handler.removeCallbacks(holdRunnable)
        holdConsumed = false
        holdFired = false
        status = ""
        closePanel()
    }

    private fun stepFor(repeat: Int) = when {
        repeat < 6 -> 100L
        repeat < 20 -> 1_000L
        else -> 10_000L
    }

    private fun keySync(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    val step = stepFor(e.repeatCount)
                    nudge(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> if (down) moveCue(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) moveCue(+1)
            KeyEvent.KEYCODE_BACK -> if (!down) {
                if (!followCurrent) {
                    followCurrent = true; markVideoMs = null; renderPanel()
                } else closePanel()
            }

            else -> {
                if (!Keys.isOk(e.keyCode)) return false
                if (down && e.repeatCount == 0) syncOk()
            }
        }
        return true
    }

    private fun nudge(d: Long) {
        model.nudge(d)
        saveModel()
        renderPanel()
    }

    private fun currentCueIndex(): Int {
        val s = sub ?: return 0
        val t = model.cueTimeAt(videoNow() ?: 0L)
        return s.indexAtOrBefore(t).coerceAtLeast(0)
    }

    private fun startMark() {
        markVideoMs = videoNow()
        followCurrent = false
        cueSel = currentCueIndex()
    }

    private fun moveCue(dir: Int) {
        val s = sub ?: return
        if (s.cues.isEmpty()) return
        if (followCurrent) startMark()
        cueSel = (cueSel + dir).coerceIn(0, s.cues.lastIndex)
        renderPanel()
    }

    private fun syncOk() {
        val s = sub ?: return openFiles()
        if (s.cues.isEmpty()) return
        if (followCurrent) {
            startMark()
            flash("Моментът е запомнен. Изберете с ▲▼ репликата, която чухте, и натиснете OK.")
            return
        }
        val mark = markVideoMs ?: videoNow() ?: return
        val cue = s.cues[cueSel.coerceIn(0, s.cues.lastIndex)]
        val r = model.anchor(cue.startMs, mark)
        saveModel()
        followCurrent = true
        markVideoMs = null
        flash(
            if (r == SyncModel.Result.TWO_POINT)
                "Синхрон по 2 точки: отместване ${SyncModel.formatOffset(model.offsetMs)}, скорост ${pct(model.scale)}"
            else
                "Синхронизирано: ${SyncModel.formatOffset(model.offsetMs)}. Ако по-късно пак се разминат, повторете – ще се изчисли и скоростта."
        )
    }

    // ---------------------------------------------------------------- меню

    private class MenuItem(
        val label: () -> String,
        val ok: (() -> Unit)? = null,
        val adjust: ((Int) -> Unit)? = null,
    )

    private fun pct(scale: Double) = "%.2f%%".format(scale * 100)

    private fun setScale(s: Double) {
        model.setScaleKeeping(s, videoNow() ?: 0L)
        saveModel()
        flash("Скорост: ${pct(model.scale)}")
    }

    private val menu: List<MenuItem> by lazy {
        listOf(
            MenuItem({ "Скорост: ${pct(model.scale)}      ◀ ▶  ±0.05%" }, adjust = { d ->
                model.setScaleKeeping(model.scale + d * 0.0005, videoNow() ?: 0L); saveModel()
            }),
            MenuItem({ "Субтитрите са за 25 fps, филмът е 23.976 fps  (×1.0427)" }, ok = { setScale(SyncModel.PAL_TO_FILM) }),
            MenuItem({ "Субтитрите са за 23.976 fps, филмът е 25 fps  (×0.9590)" }, ok = { setScale(SyncModel.FILM_TO_PAL) }),
            MenuItem({ "Скорост 100% (без промяна)" }, ok = { setScale(1.0) }),
            MenuItem({ "Нулирай отместването и скоростта" }, ok = {
                model.reset(prefs.defaultOffsetMs); saveModel(); flash("Нулирано")
            }),
            MenuItem({ "Запиши синхрона в .srt файла  (оригиналът остава като .bak)" }, ok = { saveToFile() }),
            MenuItem({ "Избери друг .srt файл" }, ok = { openFiles() }),
            MenuItem({ "Субтитри: " + if (subsEnabled) "показани" else "скрити" }, ok = { subsEnabled = !subsEnabled }),
            MenuItem({ "Размер на текста: ${prefs.textSizeSp}      ◀ ▶" }, adjust = { d -> prefs.textSizeSp += 2 * d }),
            MenuItem({ "Височина от долу: ${prefs.bottomMarginDp}      ◀ ▶" }, adjust = { d -> prefs.bottomMarginDp += 8 * d }),
            MenuItem({ "Пауза / пусни филма" }, ok = { (clock as? SessionClock)?.togglePlay() }),
            MenuItem({ "Корекция на позицията от плейъра: ${prefs.calibrationMs} ms      ◀ ▶" }, adjust = { d ->
                prefs.calibrationMs += 50L * d
            }),
            MenuItem({ "Затвори панела" }, ok = { closePanel(swallowKey = KeyEvent.KEYCODE_DPAD_CENTER) }),
        )
    }

    private fun keyMenu(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) menuSel = (menuSel - 1 + menu.size) % menu.size
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) menuSel = (menuSel + 1) % menu.size
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) menu[menuSel].adjust?.invoke(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) menu[menuSel].adjust?.invoke(+1)
            KeyEvent.KEYCODE_BACK -> if (!down) openSync()
            else -> {
                if (!Keys.isOk(e.keyCode)) return false
                if (down && e.repeatCount == 0) {
                    val item = menu[menuSel]
                    if (item.ok != null) {
                        item.ok.invoke()
                        if (page == Page.NONE) swallowRest(e.keyCode)
                    }
                }
            }
        }
        if (page != Page.NONE) renderPanel()
        return true
    }

    // ---------------------------------------------------------------- файлове

    private fun currentMetadata(): MediaMetadata? = (clock as? SessionClock)?.metadata

    private fun metadataTitle(md: MediaMetadata?): String? =
        md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }

    private fun metadataPath(md: MediaMetadata?): String? {
        val raw = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: return null
        return when {
            raw.startsWith("/") -> raw
            raw.startsWith("file://") -> Uri.parse(raw).path
            else -> null
        }
    }

    /** Ако плейърът съобщава име на филм, различно от заредения .srt, предлагаме нов файл. */
    private fun currentFileLooksWrong(): Boolean {
        val f = sub?.file ?: return true
        val title = metadataTitle(currentMetadata()) ?: return false
        return SubtitleLocator.normalize(title).length >= 4 && !SubtitleLocator.matches(f.name, title)
    }

    private fun openFiles() {
        page = Page.FILES
        saveSnapshot = null
        browse(null)
        // Допълнителни предложения по името на филма, който плейърът съобщава.
        val md = currentMetadata()
        val title = metadataTitle(md) ?: return
        val uri = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (autoCandidates.isNotEmpty() && SubtitleLocator.normalize(title) == lastAutoKey) return
        val shares = prefs.shares
        val recent = recentDir(shares)
        Thread {
            val found = findFor(uri, null, title, shares, recent)
            handler.post {
                if (found.isNotEmpty() && page == Page.FILES && browser.dir == null) {
                    autoCandidates = found
                    browse(null)
                }
            }
        }.start()
    }

    private fun recentDir(shares: List<SmbShare>): VFile? =
        prefs.lastDir?.let { SubtitleLocator.fromPath(it, shares) }

    /** Показва папка [d] (null = началният екран). Съдържанието се зарежда на заден план. */
    private fun browse(d: VFile?, selectPath: String? = null) {
        val token = ++browseToken
        browser.showLoading(d)
        renderPanel()
        val sugg = autoCandidates
        val shares = prefs.shares
        val recent = recentDir(shares)
        io.execute {
            val snap = saveSnapshot
            val list = try {
                FileBrowser.build(
                    d, sugg, recent, shares,
                    foldersOnly = snap != null, saveName = snap?.sub?.file?.name ?: "",
                )
            } catch (e: Exception) {
                listOf(FileBrowser.Entry(null, "Грешка: ${e.message}", FileBrowser.Kind.INFO))
            }
            handler.post {
                if (token == browseToken && page == Page.FILES) {
                    browser.show(d, list, selectPath)
                    renderPanel()
                }
            }
        }
    }

    private fun browseUp(): Boolean {
        val d = browser.dir ?: return false
        browse(if (FileBrowser.isRoot(d)) null else d.parent, d.path)
        return true
    }

    private fun keyFiles(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val n = browser.entries.size
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down && n > 0) browser.sel = (browser.sel - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && n > 0) browser.sel = (browser.sel + 1).coerceAtMost(n - 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) browseUp()
            KeyEvent.KEYCODE_BACK -> if (!down && !browseUp()) {
                if (sub != null) openSync() else closePanel()
            }

            else -> {
                if (!Keys.isOk(e.keyCode) && e.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
                if (down && e.repeatCount == 0) {
                    val entry = browser.entries.getOrNull(browser.sel)
                    when (entry?.kind) {
                        FileBrowser.Kind.UP -> browseUp()
                        FileBrowser.Kind.DIR -> entry.file?.let { browse(it) }
                        FileBrowser.Kind.FILE, FileBrowser.Kind.SUGGESTION ->
                            if (Keys.isOk(e.keyCode)) entry.file?.let { loadFile(it, quiet = false) }

                        FileBrowser.Kind.SAVE_HERE ->
                            if (Keys.isOk(e.keyCode)) entry.file?.let { saveCopyTo(it) }

                        else -> {}
                    }
                }
            }
        }
        if (page != Page.NONE) renderPanel()
        return true
    }

    // ---------------------------------------------------------------- следване на филма

    /**
     * Търси .srt за филма: първо до самия файл (ако адресът му е разпознат),
     * после по име в последната папка и във всички устройства и мрежови папки.
     */
    private fun findFor(
        uri: String?, dataPath: String?, videoName: String,
        shares: List<SmbShare>, recent: VFile?,
    ): List<VFile> = try {
        val video = SubtitleLocator.resolveVideo(uri, dataPath, shares)
        var found = if (video != null) SubtitleLocator.siblings(video) else emptyList()
        // Мрежова папка, разпозната от адреса (напр. от ES File Explorer), която още не е добавена:
        // ако връзката работи, я запомняме, за да се вижда и в панела.
        if (video is SmbFile && shares.none { it.id == video.share.id && it.user == video.share.user }) {
            val works = found.isNotEmpty() || video.parent?.list() != null
            if (works) handler.post {
                val now = prefs.shares
                if (now.none { it.id == video.share.id }) prefs.shares = now + video.share
            }
        }
        if (found.isEmpty()) {
            found = SubtitleLocator.searchByName(videoName, listOfNotNull(recent?.parent, recent))
        }
        if (found.isEmpty()) {
            found = SubtitleLocator.searchByName(videoName, FileBrowser.allRoots(shares))
        }
        found
    } catch (e: Exception) {
        Log.w(TAG, "findFor", e)
        emptyList()
    }

    /** Файловият мениджър току-що отвори филм през нас (OpenVideoActivity). */
    private fun onVideoOpened() {
        resetPanel() // нов филм – панелът започва на чисто
        val uri = prefs.currentVideoUri ?: return
        val name = prefs.currentVideoName ?: SubtitleLocator.nameFromUri(uri)
        autoLocate(uri, prefs.currentVideoData, name, strict = true)
    }

    /** Плейърът смени филма (напр. пуснат е от собствения му браузър). */
    private fun onPlayerMetadata(md: MediaMetadata?) {
        val title = metadataTitle(md) ?: return
        // Ако филмът е отворен през нас преди малко, вече знаем точния файл.
        if (System.currentTimeMillis() - prefs.currentVideoAt < 30_000) return
        val uri = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        autoLocate(uri, null, title, strict = false)
    }

    /**
     * [strict] = знаем със сигурност, че е пуснат нов филм: ако няма .srt за него,
     * махаме старите субтитри, за да не се показват чужди.
     */
    private fun autoLocate(uri: String?, dataPath: String?, videoName: String, strict: Boolean) {
        val key = SubtitleLocator.normalize(videoName)
        if (key.length < 3) return
        if (key == lastAutoKey && sub != null) return
        lastAutoKey = key
        autoCandidates = emptyList()
        val shares = prefs.shares
        val recent = recentDir(shares)
        Thread {
            val found = findFor(uri, dataPath, videoName, shares, recent)
            handler.post {
                if (lastAutoKey != key) return@post
                autoCandidates = found
                val best = found.firstOrNull()
                when {
                    best != null && sub?.file?.path == best.path -> {}
                    best != null -> loadFile(best, quiet = true, announce = true)
                    strict -> {
                        sub = null
                        flash("Не намерих .srt за „$videoName“. Задръжте [${Keys.name(prefs.hotkey)}], за да изберете файл.")
                    }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- зареждане и запис

    private fun loadFile(f: VFile, quiet: Boolean, announce: Boolean = !quiet) {
        io.execute {
            val res = runCatching { Srt.load(f) }
            handler.post {
                res.onSuccess { applyLoaded(f, it, quiet, announce) }
                    .onFailure { if (announce) flash("Грешка при четене на ${f.name}: " + Errors.describe(it)) }
            }
        }
    }

    private fun applyLoaded(f: VFile, s: SubtitleFile, quiet: Boolean, announce: Boolean) {
        sub = s
        model = prefs.loadModel(f.path) ?: SyncModel(prefs.defaultOffsetMs, 1.0)
        prefs.lastFile = f.path
        prefs.lastDir = f.parent?.path
        followCurrent = true
        markVideoMs = null
        if (!quiet) openSync()
        if (announce) {
            flash(
                if (s.cues.isEmpty()) "Във файла няма разпознати реплики: ${f.name}"
                else "Субтитри: ${f.name} (${s.cues.size} реплики, ${s.charset.name()})"
            )
        }
    }

    private fun saveModel() {
        val path = sub?.file?.path ?: return
        prefs.saveModel(path, model)
    }

    private fun saveToFile() {
        val s = sub ?: return
        val f = s.file ?: return
        val scale = model.scale
        val offset = model.offsetMs
        flash("Записване…")
        io.execute {
            val res = runCatching {
                Srt.saveShifted(s, scale, offset)
                Srt.load(f)
            }
            handler.post {
                res.onSuccess {
                    if (sub === s) {
                        sub = it
                        model = SyncModel(0, 1.0)
                        saveModel()
                    }
                    flash("Записано в ${f.name}. Оригиналът е запазен като ${f.name}.bak")
                }.onFailure {
                    if (Errors.isPermission(it) && page != Page.NONE) {
                        // Няма право да пише до оригинала – предлагаме копие във вътрешната памет.
                        saveSnapshot = SaveSnapshot(s, scale, offset)
                        openSaveFolder()
                        flash(
                            "Не може да се запише до оригинала: " + Errors.describe(it) +
                                "  Изберете папка на приставката за синхронизирано копие."
                        )
                    } else {
                        flash("Неуспешен запис. " + Errors.describe(it))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- копие във вътрешната памет

    private class SaveSnapshot(val sub: SubtitleFile, val scale: Double, val offsetMs: Long)

    /** Синхронът, който чака да се запише като копие (докато се избира папка). */
    private var saveSnapshot: SaveSnapshot? = null

    private fun openSaveFolder() {
        page = Page.FILES
        val internal = java.io.File("/storage/emulated/0")
        browse(if (internal.isDirectory) LocalFile(internal) else null)
    }

    private fun saveCopyTo(dir: VFile) {
        val snap = saveSnapshot ?: return
        flash("Записване…")
        io.execute {
            val res = runCatching { Srt.saveCopy(snap.sub, snap.scale, snap.offsetMs, dir) }
            handler.post {
                res.onSuccess { out ->
                    saveSnapshot = null
                    if (sub != null) openSync() else closePanel()
                    flash("Записано копие: ${out.path}  (оригиналът не е променян)")
                }.onFailure { flash("Неуспешен запис: " + Errors.describe(it)) }
            }
        }
    }

    // ---------------------------------------------------------------- панел

    private fun oneLine(t: String, max: Int = 90): String {
        val s = SubtitleView.toSpanned(t).toString().replace('\n', ' ').trim()
        return if (s.length > max) s.take(max - 1) + "…" else s
    }

    private fun renderPanel() {
        val p = panelView ?: return
        if (page == Page.NONE) return
        lastPanelRender = SystemClock.uptimeMillis()
        if (SystemClock.uptimeMillis() > statusUntil) status = ""
        val hot = Keys.name(prefs.hotkey)
        when (page) {
            Page.SYNC -> renderSync(p, hot)
            Page.MENU -> {
                val rows = window(menu.size, menuSel) { i -> PanelView.Row(menu[i].label(), selected = i == menuSel) }
                p.show(
                    "Меню", sub?.file?.name ?: "",
                    rows,
                    "▲▼ избор   ◀ ▶ промяна   OK изпълни   BACK или [$hot] назад",
                    status,
                )
            }

            Page.FILES -> {
                val rows = window(browser.entries.size, browser.sel) { i ->
                    PanelView.Row(browser.entries[i].label, selected = i == browser.sel)
                }
                if (saveSnapshot != null) {
                    p.show(
                        "Изберете папка за синхронизираното копие",
                        browser.dir?.path ?: "Вътрешна памет и USB устройства",
                        rows,
                        "▲▼ избор   OK отвори / „💾 Запиши тук“   ◀ или BACK нагоре   [$hot] отказ",
                        status,
                    )
                } else {
                    p.show(
                        "Изберете .srt файл",
                        browser.dir?.path ?: "Предложения (★), устройства и мрежови папки",
                        rows,
                        "▲▼ избор   OK отвори   ◀ или BACK нагоре   [$hot] отказ",
                        status,
                    )
                }
            }

            Page.NONE -> {}
        }
    }

    private fun renderSync(p: PanelView, hot: String) {
        val s = sub
        val v = videoNow()
        val c = clock
        val playing = c?.isPlaying() == true
        val header = "Отместване: ${SyncModel.formatOffset(model.offsetMs)}    Скорост: ${pct(model.scale)}"
        val info = buildString {
            append("Филм: ").append(if (v != null) SyncModel.formatClock(v) else "—")
            append(if (playing) " ▶" else " ⏸")
            if (c == null || v == null) append("   ⚠ ViMu не съобщава позицията на филма")
            s?.file?.let { append("   ").append(it.name) }
        }
        val rows = ArrayList<PanelView.Row>()
        if (s != null && s.cues.isNotEmpty()) {
            val t = if (v != null) model.cueTimeAt(v) else 0L
            val activeIdx = s.indexAtOrBefore(t)
            val center = if (followCurrent) activeIdx.coerceAtLeast(0) else cueSel
            val from = (center - 3).coerceAtLeast(0)
            val to = (from + 6).coerceAtMost(s.cues.lastIndex)
            for (i in from..to) {
                val cue = s.cues[i]
                val isActive = cue.startMs <= t && t < cue.endMs
                val time = SyncModel.formatClock(model.videoTimeOf(cue.startMs))
                val prefix = if (isActive) "▶ " else "   "
                rows.add(
                    PanelView.Row(
                        "$prefix$time   ${oneLine(cue.text)}",
                        selected = !followCurrent && i == cueSel,
                        active = isActive,
                    )
                )
            }
        } else {
            rows.add(PanelView.Row("(няма заредени субтитри – натиснете OK, за да изберете файл)"))
        }
        val hint = if (followCurrent) {
            "◀ ▶ по-рано / по-късно (задръжте = по-бързо)   ▲▼ или OK – синхрон по реплика   [$hot] меню   BACK затвори"
        } else {
            val m = markVideoMs?.let { SyncModel.formatClock(it) } ?: "—"
            "Запомнен момент $m → изберете с ▲▼ чутата реплика и натиснете OK   BACK отказ"
        }
        p.show(header, info, rows, hint, status)
    }

    private fun window(size: Int, sel: Int, make: (Int) -> PanelView.Row): List<PanelView.Row> {
        if (size == 0) return emptyList()
        val n = PanelView.MAX_ROWS
        val from = (sel - n / 2).coerceIn(0, (size - n).coerceAtLeast(0))
        val to = (from + n - 1).coerceAtMost(size - 1)
        return (from..to).map(make)
    }

    companion object {
        private const val TAG = "SubSync"
        private const val HOLD_MS = 700L

        @Volatile
        var running = false
            private set
    }
}
