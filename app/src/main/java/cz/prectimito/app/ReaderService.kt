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
            Prefs.A_EXPLORE -> main.post { if (overlays.exploring) onExploreExit() else startExplore() }
        }
    }

    fun stopReading() {
        generation++
        changeGen++
        worker.removeCallbacksAndMessages(null)
        speaker.stop()
    }

    fun testVoice(text: String) {
        speaker.applySettings()
        speaker.say(text)
    }

    fun hasCzechVoice() = speaker.hasLanguage()

    fun voiceOptions() = speaker.voiceOptions()

    fun engineOptions() = speaker.engineOptions()

    private fun readScreen(build: (List<Item>, String) -> List<Seg>) {
        val gen = ++generation
        changeGen++
        speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val root = appRoot()
            if (root == null) { speaker.say("Obrazovku teď nejde přečíst."); return@post }
            val app = appName(root.packageName?.toString())
            val items = scanner.scan(root)
            if (gen != generation) return@post
            speaker.speakSegs(build(items, app))
        }
    }

    /** Plocha bubliny v souřadnicích obrazovky (stejných, jaké mají prvky aplikací). */
    private fun bubbleRect(): Rect? {
        val v = bubble ?: return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // Kandidáti u bubliny pro dvojklepnutí „další vedle“.
    @Volatile private var nearItems: List<Item> = emptyList()
    @Volatile private var nearIndex = 0
    @Volatile private var nearRect: Rect? = null

    private fun readUnderBubble(quiet: Boolean) {
        val area = bubbleRect() ?: return
        val gen = ++generation
        changeGen++
        if (!quiet) speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val list = scanner.candidatesNear(appRoot(), area, screenArea())
            if (gen != generation) return@post
            nearItems = list; nearIndex = 0; nearRect = area
            val first = list.firstOrNull()
            if (first == null) {
                if (!quiet) speaker.say("Tady nic není.")
                return@post
            }
            val seg = phrases.seg(first)
            if (quiet && seg.text == lastHoverLabel) return@post
            lastHoverLabel = seg.text
            val more = if (!quiet && list.size > 1 && prefs.doubleAction == Prefs.A_NEXT) " Dvojklepnutím další." else ""
            speaker.speakSegs(listOf(Seg(seg.text + more, seg.rect)))
        }
    }

    /** Dvojklepnutí: přečte další prvek v okolí bubliny (dokola). */
    private fun readNextNearBubble() {
        val area = bubbleRect() ?: return
        val gen = ++generation
        changeGen++
        speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            var list = nearItems
            if (list.isEmpty() || nearRect != area) {
                // Bublina se mezitím posunula — najít znovu, první už asi slyšel.
                list = scanner.candidatesNear(appRoot(), area, screenArea())
                nearItems = list; nearRect = area; nearIndex = 0
            }
            if (gen != generation) return@post
            if (list.isEmpty()) { speaker.say("Tady nic není."); return@post }
            if (list.size == 1) {
                speaker.speakSegs(listOf(Seg("Jen tohle: " + phrases.describe(list[0]), list[0].bounds)))
                return@post
            }
            nearIndex = (nearIndex + 1) % list.size
            val it = list[nearIndex]
            val prefix = if (nearIndex == 0) "Znovu první. " else ""
            speaker.speakSegs(listOf(Seg(prefix + phrases.describe(it), it.bounds)))
        }
    }

    private fun screenArea(): Long {
        val dm = resources.displayMetrics
        return dm.widthPixels.toLong() * dm.heightPixels
    }

    private fun readAt(x: Int, y: Int, quiet: Boolean, onlyIfNew: Boolean = false) {
        val gen = ++generation
        changeGen++
        if (!quiet) speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val root = appRoot() ?: return@post
            val item = scanner.itemAt(root, x, y)
            if (gen != generation) return@post
            if (item != null) {
                val s = phrases.seg(item)
                if (onlyIfNew && s.text == lastHoverLabel) return@post
                lastHoverLabel = s.text
                speaker.speakSegs(listOf(s))
            } else if (!quiet) speaker.say("Tady nic není.")
        }
    }

    /** Souvislé čtení textu; volitelně posouvá stránku a čte dál. */
    private fun readText() {
        val gen = ++generation
        changeGen++
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
        val parts = phrases.bodyText(items).filter { spoken.add(it.text) }
        if (gen != generation) return
        if (parts.isEmpty()) {
            speaker.say(if (first) "Na obrazovce není žádný text." else "Konec stránky.")
            return
        }
        speaker.onQueueDone = if (prefs.autoScroll && pages < 40) {
            { worker.post { scrollAndContinue(gen) } }
        } else null
        speaker.speakSegs(parts)
    }

    private fun scrollAndContinue(gen: Int) {
        if (gen != generation) return
        pages++
        if (!scroll(true)) { speaker.say("Konec stránky."); return }
        worker.postDelayed({ readTextPage(gen, first = false) }, 700)
    }

    private fun scroll(forward: Boolean): Boolean {
        val root = appRoot() ?: return false
        val target = biggestScrollable(root)
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = target?.performAction(action) == true
        ScreenScanner.recycle(target)
        return ok
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
    // Režim „klepni a přečti“

    private fun startExplore() {
        stopReading()
        removeBubble()
        if (!overlays.startExplore(this)) {
            updateBubble()
            speaker.say("Režim čtení se nepodařilo zapnout.")
            return
        }
        lastHoverLabel = ""
        speaker.say(
            "Režim čtení. Klepni na cokoli a přečtu to." +
                (if (prefs.exploreDoubleClick) " Dvojím klepnutím to stiskneš." else "") +
                " Nahoře jsou tlačítka pro posun a konec."
        )
    }

    override fun onExploreTap(x: Int, y: Int) = readAt(x, y, quiet = false)

    override fun onExploreHover(x: Int, y: Int) = readAt(x, y, quiet = true, onlyIfNew = true)

    override fun onExploreDoubleTap(x: Int, y: Int) {
        if (!prefs.exploreDoubleClick) { readAt(x, y, quiet = false); return }
        generation++
        speaker.stop()
        worker.removeCallbacksAndMessages(null)
        worker.post {
            val ok = scanner.clickAt(appRoot(), x, y)
            if (!ok) speaker.say("Tady nejde nic stisknout.")
        }
    }

    override fun onExploreScroll(forward: Boolean) {
        stopReading()
        worker.post {
            if (!scroll(forward)) speaker.say(if (forward) "Níž už to nejde." else "Výš už to nejde.")
        }
    }

    override fun onExploreReadText() = readText()

    override fun onExploreExit() {
        stopReading()
        overlays.stopExplore()
        updateBubble()
        speaker.say("Konec režimu čtení.")
    }

    // ------------------------------------------------------------------
    // Hlášení změn po klepnutí: nová stránka, chybové hlášky, nové věci.

    private class Snapshot(val address: String, val keys: Set<String>, val items: List<Item>)

    private fun snapshot(): Snapshot? {
        val root = appRoot() ?: return null
        val items = scanner.scan(root)
        val address = items.firstOrNull { it.kind == Item.Kind.ADDRESS }?.label.orEmpty()
        val keys = HashSet<String>()
        for (it in items) if (it.kind != Item.Kind.ADDRESS) keys.add(it.kind.name + "|" + it.label)
        return Snapshot(address, keys, items)
    }

    private fun watchChanges() {
        val g = ++changeGen
        worker.post {
            if (g != changeGen) return@post
            baseline = snapshot()
            worker.postDelayed({ checkChanges(g) }, 1000)
            worker.postDelayed({ checkChanges(g) }, 2600)
        }
    }

    private fun checkChanges(g: Int) {
        if (g != changeGen) return
        val base = baseline ?: return
        val now = snapshot() ?: return
        if (g != changeGen) return
        val out = ArrayList<Seg>()

        val newItems = now.items.filter { it.kind != Item.Kind.ADDRESS && (it.kind.name + "|" + it.label) !in base.keys }
        val overlap = if (now.keys.isEmpty()) 1.0 else (now.keys.count { it in base.keys }.toDouble() / now.keys.size)
        val pageChanged = (now.address.isNotEmpty() && now.address != base.address) ||
            (now.keys.size >= 5 && overlap < 0.3)

        if (pageChanged && prefs.chPage) {
            val head = now.items.firstOrNull { it.kind == Item.Kind.HEADING }
                ?: now.items.firstOrNull { it.kind == Item.Kind.TEXT }
            val where = if (now.address.isNotEmpty() && now.address != base.address)
                "Nová stránka ${Phrases.spellDomain(now.address)}." else "Nová obrazovka."
            out.add(Seg(where))
            if (head != null) out.add(Seg(Phrases.clip(phrases.describe(head), 160), head.bounds))
            val form = now.items.filter { it.isFormPart }
            if (form.isNotEmpty()) out.add(Seg("Je tu formulář, ${Phrases.count(form.size, "pole", "pole", "polí")}.", Phrases.union(form)))
        } else {
            if (prefs.chErrors) {
                newItems.filter { it.kind == Item.Kind.TEXT && isError(it.label) }.take(2).forEach {
                    out.add(Seg("Pozor: " + Phrases.sentence(Phrases.clip(it.label, 200)), it.bounds))
                }
            }
            if (prefs.chOther && out.isEmpty() && newItems.isNotEmpty()) {
                out.add(Seg("Nově na obrazovce:"))
                newItems.take(3).forEach { out.add(phrases.seg(it)) }
            }
        }
        if (out.isNotEmpty()) {
            baseline = now
            if (g != changeGen) return
            speaker.speakSegs(out, queue = speaker.speaking)
        }
    }

    private fun isError(t: String): Boolean {
        if (t.length > 300) return false
        val l = t.lowercase()
        return ERROR_WORDS.any { it in l }
    }

    private fun announceDialog() {
        val g = ++changeGen
        worker.postDelayed({
            if (g != changeGen) return@postDelayed
            val root = appRoot() ?: return@postDelayed
            val items = scanner.scan(root)
            if (g != changeGen || items.isEmpty()) return@postDelayed
            val segs = ArrayList<Seg>()
            segs.add(Seg("Vyskočilo okno."))
            segs.addAll(phrases.summary(items, "", intro = false))
            speaker.speakSegs(segs)
        }, 400)
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
                val cls = event.className?.toString().orEmpty()
                if (prefs.chDialogs && cls.contains("Dialog") && !overlays.exploring) {
                    announceDialog()
                }
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
                        scanner.toItem(src)?.let { speakNow(phrases.seg(it, true)) }
                    }
                } finally { ScreenScanner.recycle(src) }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (prefs.announceClicked) {
                    val src = event.source
                    if (src != null) {
                        try {
                            scanner.toItem(src)?.let { speakNow(phrases.seg(it)) }
                        } finally { ScreenScanner.recycle(src) }
                    }
                }
                if (prefs.chPage || prefs.chErrors || prefs.chOther) watchChanges()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (!prefs.chToasts) return
                if (event.parcelableData is Notification) return // oznámení v liště nečteme
                val text = event.text.joinToString(" ") { it.toString() }.trim()
                if (text.isEmpty() || text.length > 300) return
                // Vždy říct, čí hlášení to je — cizí aplikace tak nemůže hlasem
                // čtečky předstírat, že mluví telefon nebo banka.
                val from = appName(pkg)
                val intro = if (from.isNotEmpty()) "Hlášení z aplikace $from: " else "Hlášení: "
                speaker.say(intro + Phrases.sentence(text), queue = speaker.speaking)
            }
        }
    }

    private fun speakNow(seg: Seg) {
        generation++
        worker.removeCallbacksAndMessages(null)
        speaker.stop()
        speaker.speakSegs(listOf(seg))
    }

    companion object {
        @Volatile var instance: ReaderService? = null
            private set

        private val ERROR_WORDS = listOf(
            "chyba", "chybn", "neplatn", "nesprávn", "špatn", "povinn", "musíte", "nepodařilo",
            "zkuste to znovu", "zkuste znovu", "nelze", "selhal", "vyplňte", "zadejte platn",
            "error", "invalid", "required", "incorrect", "failed", "try again",
        )
    }
}
