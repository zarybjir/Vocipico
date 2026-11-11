package cz.prectimito.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Služba přístupnosti: čte obsah jiných aplikací, ukazuje plovoucí bublinu,
 * zvýrazňuje čtené místo, hlásí změny na obrazovce a nabízí režim
 * „klepni a přečti“. Procházení obrazovky běží ve vlastním vlákně.
 */
class ReaderService : AccessibilityService(), BubbleView.Listener, Overlays.ExploreListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var scanner: ScreenScanner
    private lateinit var phrases: Phrases
    private lateinit var speaker: Speaker
    private lateinit var overlays: Overlays
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Každá nová akce zvýší číslo — starší rozběhnutá práce se pak zahodí. */
    @Volatile private var generation = 0
    private var lastPackage = ""
    private val appNames = HashMap<String, String>()
    private var a11yButtonCallback: Any? = null

    // Čtení textu se posouváním stránky
    private val spoken = HashSet<String>()
    private var pages = 0

    // Hlášení změn
    @Volatile private var changeGen = 0
    private var baseline: Snapshot? = null

    // Režim klepni a přečti
    @Volatile private var lastHoverLabel = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        scanner = ScreenScanner(prefs)
        phrases = Phrases(prefs)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlays = Overlays(this, wm!!, prefs)
        speaker = Speaker(
            this, prefs,
            onSpeakingChanged = { on -> main.post { bubble?.speaking = on } },
            onSegment = { seg -> main.post { overlays.show(seg) { stopReading() } } },
        )
        workerThread = HandlerThread("ctecka").also { it.start() }
        worker = Handler(workerThread.looper)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
        applyServiceInfo()
        updateBubble()
        instance = this
    }

    /**
     * Při zhasnutí obrazovky skončí režim „klepni a přečti“: na zamčeném
     * telefonu nesmí zůstat vrstva, která chytá všechny dotyky.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (overlays.exploring) {
                overlays.stopExplore()
                updateBubble()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        try { prefs.sp.unregisterOnSharedPreferenceChangeListener(this) } catch (_: Exception) {}
        removeBubble()
        if (::overlays.isInitialized) overlays.removeAll()
        if (::speaker.isInitialized) speaker.shutdown()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopReading()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "bubble_x" || key == "bubble_y") return
        if (key == Prefs.K_ENGINE) speaker.checkEngine() else speaker.applySettings()
        applyServiceInfo()
        main.post { overlays.refreshStyle() }
        if (!overlays.exploring) updateBubble()
    }

    // ------------------------------------------------------------------
    // Nastavení služby: odebíráme jen ty události, které opravdu potřebujeme.

    private fun applyServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        var events = 0
        if (prefs.announceFocusedField) events = events or AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (prefs.announceClicked || prefs.chPage || prefs.chErrors || prefs.chOther) {
            events = events or AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        if (prefs.announceAppChange || prefs.stopOnAppChange || prefs.chDialogs) {
            events = events or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        if (prefs.chToasts) events = events or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        if (events == 0) events = AccessibilityEvent.TYPE_ANNOUNCEMENT // skoro nikdy nechodí
        info.eventTypes = events
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
        info.notificationTimeout = 100
        var flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        if (prefs.thorough) flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        if (Build.VERSION.SDK_INT >= 26 && prefs.a11yButton) {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        info.flags = flags
        serviceInfo = info
        if (Build.VERSION.SDK_INT >= 26) setupA11yButton()
    }

    private fun setupA11yButton() {
        if (Build.VERSION.SDK_INT < 26) return
        val ctrl = accessibilityButtonController
        if (prefs.a11yButton && a11yButtonCallback == null) {
            val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    onBubbleTap()
                }
            }
            ctrl.registerAccessibilityButtonCallback(cb)
            a11yButtonCallback = cb
        } else if (!prefs.a11yButton && a11yButtonCallback != null) {
            ctrl.unregisterAccessibilityButtonCallback(
                a11yButtonCallback as AccessibilityButtonController.AccessibilityButtonCallback
            )
            a11yButtonCallback = null
        }
    }

    // ------------------------------------------------------------------
    // Bublina

    private fun bubbleSizePx(): Int = (prefs.bubbleDp * resources.displayMetrics.density).toInt()

    private fun updateBubble() {
        main.post {
            if (!prefs.showBubble || overlays.exploring) { removeBubble(); return@post }
            val size = bubbleSizePx()
            val existing = bubble
            if (existing != null) {
                val p = lp ?: return@post
                p.width = size; p.height = size
                existing.alpha = prefs.bubbleOpacity / 100f
                existing.doubleTapEnabled = prefs.doubleAction != Prefs.A_NONE
                existing.iconScale = prefs.iconScale
                existing.palette = prefs.bubbleColor
                try { wm?.updateViewLayout(existing, p) } catch (_: Exception) {}
                return@post
            }
            val dm = resources.displayMetrics
            val p = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = prefs.bubbleX.takeIf { it >= 0 } ?: (dm.widthPixels - size - size / 4)
            p.y = prefs.bubbleY.takeIf { it >= 0 } ?: (dm.heightPixels / 2)
            clamp(p)
            val v = BubbleView(this, this)
            v.alpha = prefs.bubbleOpacity / 100f
            v.speaking = speaker.speaking
            v.doubleTapEnabled = prefs.doubleAction != Prefs.A_NONE
            v.iconScale = prefs.iconScale
            v.palette = prefs.bubbleColor
            try {
                wm?.addView(v, p)
                bubble = v; lp = p
            } catch (_: Exception) {
            }
        }
    }

    private fun removeBubble() {
        val v = bubble ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        bubble = null
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        p.x = p.x.coerceIn(0, maxOf(0, dm.widthPixels - p.width))
        p.y = p.y.coerceIn(0, maxOf(0, dm.heightPixels - p.height))
    }

    private fun buzz() {
        if (prefs.vibrate) bubble?.performHapticFeedback(
            HapticFeedbackConstants.VIRTUAL_KEY,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    override fun onBubbleTap() {
        buzz()
        if (speaker.speaking) { stopReading(); return }
        run(prefs.tapAction)
    }

    override fun onBubbleDoubleTap() {
        buzz()
        run(prefs.doubleAction)
    }

    override fun onBubbleLongPress() {
        bubble?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        run(prefs.longAction)
    }

    override fun onBubbleDrag(dx: Int, dy: Int) {
        val p = lp ?: return
        val v = bubble ?: return
        p.x += dx; p.y += dy
        clamp(p)
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    override fun onBubbleDragPause() {
        if (prefs.readWhileDragging) readUnderBubble(quiet = true)
    }

    override fun onBubbleDrop() {
        lp?.let { prefs.bubbleX = it.x; prefs.bubbleY = it.y }
        if (prefs.dropAction != Prefs.A_NONE) run(prefs.dropAction)
    }

    // ------------------------------------------------------------------
    // Akce

    fun run(action: String) {
        when (action) {
            Prefs.A_SUMMARY -> readScreen { items, app -> phrases.summary(items, app) }
            Prefs.A_FORM -> readScreen { items, _ -> phrases.formOnly(items) }
            Prefs.A_TEXT -> readText()
            Prefs.A_UNDER -> readUnderBubble(quiet = false)
            Prefs.A_NEXT -> readNextNearBubble()
            Prefs.A_
