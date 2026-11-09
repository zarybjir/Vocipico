package cz.prectimito.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Vizuální pomoc pro člověka, který špatně vidí:
 *  - žlutý rámeček kolem toho, co se právě čte,
 *  - velký titulek s právě čteným textem,
 *  - režim „klepni a přečti“ (průhledná vrstva přes celou obrazovku).
 * Vše se volá z hlavního vlákna.
 */
class Overlays(private val ctx: Context, private val wm: WindowManager, private val p: Prefs) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // ------------------------------------------------------------------
    // Rámeček + titulek

    private var frame: FrameView? = null
    private var caption: TextView? = null
    private var captionLp: WindowManager.LayoutParams? = null

    /** Ukáže, co se právě čte (seg == null → skrýt). */
    fun show(seg: Seg?, onCaptionTap: () -> Unit) {
        if (seg == null) { hideReading(); return }
        if (p.highlight && seg.rect != null && !seg.rect.isEmpty) {
            val f = frame ?: FrameView(ctx).also { v ->
                frame = v
                add(v, fullScreen(touchable = false))
            }
            f.setTarget(seg.rect)
        } else frame?.setTarget(null)

        if (p.caption) {
            val c = caption ?: makeCaption(onCaptionTap)
            c.text = seg.text
            applyCaptionStyle(c)
            val lp = captionLp ?: return
            val screenH = ctx.resources.displayMetrics.heightPixels
            val wantTop = when (p.captionPos) {
                "top" -> true
                "bottom" -> false
                // Auto: titulek na opačnou polovinu, než je čtený prvek.
                else -> seg.rect != null && seg.rect.centerY() > screenH / 2
            }
            val g = if (wantTop) Gravity.TOP else Gravity.BOTTOM
            val touchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
            if (lp.gravity != g || !touchable) {
                lp.gravity = g
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(c, lp) } catch (_: Exception) {}
            }
            c.visibility = View.VISIBLE
        }
    }

    fun hideReading() {
        frame?.setTarget(null)
        hideCaption()
    }

    /** Skrytý titulek nesmí chytat dotyky určené aplikaci pod ním. */
    private fun hideCaption() {
        val c = caption ?: return
        c.visibility = View.GONE
        val lp = captionLp ?: return
        if ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm.updateViewLayout(c, lp) } catch (_: Exception) {}
        }
    }

    private fun makeCaption(onTap: () -> Unit): TextView {
        val t = TextView(ctx)
        t.setPadding(dp(16), dp(12), dp(16), dp(12))
        t.maxLines = 8
        t.setLineSpacing(0f, 1.1f)
        t.setOnClickListener { onTap() }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM
        captionLp = lp
        caption = t
        add(t, lp)
        return t
    }

    private fun applyCaptionStyle(t: TextView) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.captionSize.toFloat())
        val (fg, bg) = when (p.captionColors) {
            "white" -> Color.WHITE to Color.BLACK
            "black_on_yellow" -> Color.BLACK to Color.parseColor("#FFD54F")
            else -> Color.parseColor("#FFD54F") to Color.BLACK
        }
        t.setTextColor(fg)
        t.setBackgroundColor(bg)
    }

    /** Ukáže titulek bez čtení (používá režim klepni a přečti, když není co číst). */
    fun refreshStyle() {
        caption?.let { applyCaptionStyle(it) }
        frame?.invalidate()
        if (!p.highlight) frame?.setTarget(null)
        if (!p.caption) hideCaption()
    }

    // ------------------------------------------------------------------
    // Režim „klepni a přečti“

    interface ExploreListener {
        fun onExploreTap(x: Int, y: Int)
        fun onExploreDoubleTap(x: Int, y: Int)
        fun onExploreHover(x: Int, y: Int)
        fun onExploreScroll(forward: Boolean)
        fun onExploreReadText()
        fun onExploreExit()
    }

    private var exploreLayer: View? = null
    private var banner: View? = null

    val exploring get() = exploreLayer != null

    fun startExplore(l: ExploreListener) {
        if (exploreLayer != null) return
        val layer = ExploreLayer(ctx, l)
        add(layer, fullScreen(touchable = true))
        exploreLayer = layer

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun btn(label: String, desc: String, weight: Float, action: () -> Unit) {
            val b = Button(ctx)
            b.text = label
            b.contentDescription = desc
            b.isAllCaps = false
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            b.setTextColor(Color.BLACK)
            b.background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFD54F")); cornerRadius = dp(10).toFloat()
            }
            b.setOnClickListener { action() }
            bar.addView(b, LinearLayout.LayoutParams(0, dp(64), weight).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        btn("▲", "Posunout nahoru", 1f) { l.onExploreScroll(false) }
        btn("▼", "Posunout dolů", 1f) { l.onExploreScroll(true) }
        btn("Čti", "Číst text", 1.3f) { l.onExploreReadText() }
        btn("✕ Konec", "Ukončit režim čtení", 2f) { l.onExploreExit() }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        add(bar, lp)
        banner = bar
    }

    fun stopExplore() {
        exploreLayer?.let { remove(it) }
        banner?.let { remove(it) }
        exploreLayer = null
        banner = null
    }

    fun removeAll() {
        stopExplore()
        frame?.let { remove(it) }
        caption?.let { remove(it) }
        frame = null
        caption = null
        captionLp = null
    }

    // ------------------------------------------------------------------

    private fun fullScreen(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return lp
    }

    private fun add(v: View, lp: WindowManager.LayoutParams) {
        try { wm.addView(v, lp) } catch (_: Exception) {}
    }

    private fun remove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    /** Žlutý rámeček s černým okrajem (je vidět na světlém i tmavém pozadí). */
    private inner class FrameView(c: Context) : View(c) {
        private val target = Rect()
        private var has = false
        private val loc = IntArray(2)
        private val rf = RectF()
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = dp(10).toFloat()
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.parseColor("#FFD54F"); strokeWidth = dp(6).toFloat()
        }

        fun setTarget(r: Rect?) {
            has = r != null
            if (r != null) target.set(r)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!has) return
            getLocationOnScreen(loc)
            val pad = dp(6).toFloat()
            rf.set(
                target.left - loc[0] - pad, target.top - loc[1] - pad,
                target.right - loc[0] + pad, target.bottom - loc[1] + pad
            )
            val r = dp(10).toFloat()
            canvas.drawRoundRect(rf, r, r, outer)
            canvas.drawRoundRect(rf, r, r, inner)
        }
    }

    /** Průhledná vrstva: klepnutí přečte, dvojklepnutí stiskne, tažení prstem čte pod prstem. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class ExploreLayer(c: Context, private val l: ExploreListener) : View(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop * 2
        private val doubleTimeout = 350L
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private var lastHover = 0L

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.rawX
            val y = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = x; downY = y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) moved = true
                    val now = SystemClock.uptimeMillis()
                    if (moved && now - lastHover > 200) {
                        lastHover = now
                        l.onExploreHover(x.toInt(), y.toInt())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        l.onExploreHover(x.toInt(), y.toInt())
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val near = Math.abs(x - lastTapX) < slop * 2 && Math.abs(y - lastTapY) < slop * 2
                        if (now - lastTapTime < doubleTimeout && near) {
                            lastTapTime = 0
                            l.onExploreDoubleTap(x.toInt(), y.toInt())
                        } else {
                            lastTapTime = now; lastTapX = x; lastTapY = y
                            l.onExploreTap(x.toInt(), y.toInt())
                        }
                    }
                }
            }
            return true
        }
    }
}
