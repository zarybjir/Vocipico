package cz.prectimito.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 * Služba přístupnosti: čte obsah jiných aplikací a ukazuje plovoucí bublinu.
 * Veškeré procházení obrazovky běží ve vlastním vlákně, aby se nic nezasekávalo.
 */
class ReaderService : AccessibilityService(), BubbleView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var scanner: ScreenScanner
    private lateinit var phrases: Phrases
    private lateinit var speaker: Speaker
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        scanner = ScreenScanner(prefs)
        phrases = Phrases(prefs)
        speaker = Speaker(this, prefs) { on -> main.post { bubble?.speaking = on } }
        workerThread = HandlerThread("ctecka").also { it.start() }
        worker = Handler(workerThread.looper)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        applyServiceInfo()
        updateBubble()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        try { prefs.sp.unregisterOnSharedPreferenceChangeListener(this) } catch (_: Exception) {}
        removeBubble()
        if (::speaker.isInitialized) speaker.shutdown()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopReading()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "bubble_x" || key == "bubble_y") return
        speaker.applySettings()
        applyServiceInfo()
        updateBubble()
    }

    // ------------------------------------------------------------------
    // Nastavení služby: odebíráme jen ty události, které opravdu potřebujeme.

    private fun applyServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        var events = 0
        if (prefs.announceFocusedField) events = events or AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (prefs.announceClicked) events = events or AccessibilityEvent.TYPE_VIEW_CLICKED
        if (prefs.announceAppChange || prefs.stopOnAppChange) events = events or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (events == 0) events = AccessibilityEvent.TYPE_ANNOUNCEMENT // skoro nikdy nechodí
        info.eventTypes = events
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
        info.notificationTimeout = 150
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

    private fun bubbleSizePx(): Int {
        val dp = when (prefs.bubbleSize) {
            "small" -> 56
            "medium" -> 72
            "xlarge" -> 120
            else -> 92
        }
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateBubble() {
        main.post {
            if (!prefs.showBubble) { removeBubble(); return@post }
            val size = bubbleSizePx()
            val existing = bubble
            if (existing != null) {
                val p = lp ?: return@post
                p.width = size; p.height = size
                existing.alpha = prefs.bubbleOpacity / 100f
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
        }
    }

    fun stopReading() {
        generation++
        worker.removeCallbacksAndMessages(null)
        speaker.stop()
    }

    fun testVoice(text: String) {
        speaker.applySettings()
        speaker.say(text)
    }

    fun hasCzechVoice() = speaker.hasLanguage()

    private fun readScreen(build: (List<Item>, String) -> List<String>) {
        val gen = ++generation
        speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val root = appRoot()
            if (root == null) { speaker.say("Obrazovku teď nejde přečíst."); return@post }
            val app = appName(root.packageName?.toString())
            val items = scanner.scan(root)
            if (gen != generation) return@post
            speaker.speak(build(items, app))
        }
    }

    private fun readUnderBubble(quiet: Boolean) {
        val p = lp ?: return
        val cx = p.x + p.width / 2
        val cy = p.y + p.height / 2
        val gen = ++generation
        if (!quiet) speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val root = appRoot() ?: return@post
            val item = scanner.itemAt(root, cx, cy)
            if (gen != generation) return@post
            if (item != null) speaker.say(phrases.describe(item))
            else if (!quiet) speaker.say("Tady nic není.")
        }
    }

    /** Souvislé čtení textu; volitelně posouvá stránku a čte dál. */
    private fun readText() {
        val gen = ++generation
        speaker.stop()
        worker.removeCallbacksAndMessages(null)
        spoken.clear()
        pages = 0
        worker.post { readTextPage(gen, first = true) }
    }

    private fun readTextPage(gen: Int, first: Boolean) {
        if (gen != generation) return
        val root = appRoot()
        if (root == null) { speaker.say("Obrazovku teď nejde přečíst."); return }
        val items = scanner.scan(root)
        val parts = phrases.bodyText(items).filter { spoken.add(it) }
        if (gen != generation) return
        if (parts.isEmpty()) {
            speaker.say(if (first) "Na obrazovce není žádný text." else "Konec stránky.")
            return
        }
        speaker.onQueueDone = if (prefs.autoScroll && pages < 40) {
            { worker.post { scrollAndContinue(gen) } }
        } else null
        speaker.speak(parts)
    }

    private fun scrollAndContinue(gen: Int) {
        if (gen != generation) return
        pages++
        val root = appRoot() ?: return
        val target = biggestScrollable(root)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        ScreenScanner.recycle(target)
        if (!ok) { speaker.say("Konec stránky."); return }
        worker.postDelayed({ readTextPage(gen, first = false) }, 700)
    }

    private fun biggestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val r = Rect()
        var visited = 0
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            visited++
            var keep = false
            try {
                if (n.isScrollable && n.isVisibleToUser) {
                    n.getBoundsInScreen(r)
                    val a = r.width().toLong() * r.height()
                    if (a > bestArea) {
                        ScreenScanner.recycle(best)
                        best = n; bestArea = a; keep = true
                    }
                }
                if (visited < prefs.maxNodes) {
                    for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
            } finally {
                if (!keep) ScreenScanner.recycle(n)
            }
        }
        while (stack.isNotEmpty()) ScreenScanner.recycle(stack.removeLast())
        return best
    }

    // ------------------------------------------------------------------
    // Okna a události

    /** Kořen okna aplikace, se kterou člověk právě pracuje (ne bublina, ne klávesnice). */
    private fun appRoot(): AccessibilityNodeInfo? {
        try {
            val list = windows
            var pick: AccessibilityWindowInfo? = null
            for (w in list) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isActive || w.isFocused) { pick = w; break }
                if (pick == null) pick = w
            }
            val root = pick?.root
            if (root != null && root.packageName != packageName) return root
            ScreenScanner.recycle(root)
        } catch (_: Exception) {
        }
        return rootInActiveWindow
    }

    private fun appName(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return appNames.getOrPut(pkg) {
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) { "" }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isEmpty() || pkg == lastPackage || pkg == "com.android.systemui" ||
                    pkg.contains("inputmethod") || pkg.contains("keyboard")
                ) return
                lastPackage = pkg
                if (prefs.stopOnAppChange && speaker.speaking) stopReading()
                if (prefs.announceAppChange) {
                    val name = appName(pkg)
                    if (name.isNotEmpty()) speaker.say("$name.")
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (!prefs.announceFocusedField) return
                val src = event.source ?: return
                try {
                    if (src.isEditable) {
                        scanner.toItem(src)?.let { speakNow(phrases.describe(it, true)) }
                    }
                } finally { ScreenScanner.recycle(src) }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!prefs.announceClicked) return
                val src = event.source ?: return
                try {
                    scanner.toItem(src)?.let { speakNow(phrases.describe(it)) }
                } finally { ScreenScanner.recycle(src) }
            }
        }
    }

    private fun speakNow(text: String) {
        generation++
        worker.removeCallbacksAndMessages(null)
        speaker.stop()
        speaker.say(text)
    }

    companion object {
        @Volatile var instance: ReaderService? = null
            private set
    }
}
