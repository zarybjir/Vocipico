package cz.prectimito.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Plovoucí kulaté tlačítko. Kreslí se samo (žádné obrázky), takže je lehké.
 * Modré s reproduktorem = připraveno, červené se čtverečkem = čte (klepnutím zastavíš).
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onBubbleTap()
        fun onBubbleDoubleTap()
        fun onBubbleLongPress()
        fun onBubbleDrag(dx: Int, dy: Int)
        fun onBubbleDragPause()
        fun onBubbleDrop()
    }

    /** Když je dvojklepnutí vypnuté, reaguje jedno klepnutí okamžitě (bez čekání). */
    var doubleTapEnabled = true

    /** Velikost symbolu uvnitř bubliny v procentech. */
    var iconScale = 100
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Barevné provedení: amber, red, blue, white. */
    var palette = "amber"
        set(v) { if (field != v) { field = v; invalidate() } }

    var speaking = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false

    private val longPress = Runnable {
        if (!dragging) {
            longFired = true
            listener.onBubbleLongPress()
        }
    }
    private var lastUp = 0L
    private val singleTap = Runnable { listener.onBubbleTap() }
    private val dragPause = Runnable { if (dragging) listener.onBubbleDragPause() }

    init {
        contentDescription = "Přečíst obrazovku"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        ring.strokeWidth = r * 0.08f
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFD50000.toInt()
        val amber = 0xFFFFB300.toInt()
        val fillColor: Int
        val iconColor: Int
        when (palette) {
            "red" -> if (speaking) { fillColor = black; iconColor = amber } else { fillColor = red; iconColor = white }
            "blue" -> if (speaking) { fillColor = red; iconColor = white } else { fillColor = 0xFF0D47A1.toInt(); iconColor = white }
            "white" -> if (speaking) { fillColor = red; iconColor = white } else { fillColor = white; iconColor = black }
            else -> if (speaking) { fillColor = red; iconColor = white } else { fillColor = amber; iconColor = black }
        }
        fill.color = fillColor
        icon.color = iconColor
        arc.color = iconColor
        // Okraj vždy v opačném odstínu než výplň, ať je bublina vidět na čemkoli.
        ring.color = if (fillColor == white || fillColor == amber) black else white
        canvas.drawCircle(cx, cy, r - ring.strokeWidth, fill)
        canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)

        val s = r * 0.42f * iconScale.coerceIn(40, 160) / 100f // velikost symbolu
        if (speaking) {
            canvas.drawRect(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, icon)
        } else {
            // reproduktor
            val left = cx - s * 0.95f
            path.reset()
            path.moveTo(left, cy - s * 0.35f)
            path.lineTo(left + s * 0.45f, cy - s * 0.35f)
            path.lineTo(left + s * 1.0f, cy - s * 0.85f)
            path.lineTo(left + s * 1.0f, cy + s * 0.85f)
            path.lineTo(left + s * 0.45f, cy + s * 0.35f)
            path.lineTo(left, cy + s * 0.35f)
            path.close()
            canvas.drawPath(path, icon)
            arc.strokeWidth = s * 0.16f
            val ax = left + s * 0.95f
            for (k in 1..2) {
                val rr = s * 0.42f * k
                oval.set(ax - rr, cy - rr, ax + rr, cy + rr)
                canvas.drawArc(oval, -50f, 100f, false, arc)
            }
        }
    }

    companion object {
        private const val DOUBLE_MS = 300L
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                dragging = false; longFired = false
                handler.postDelayed(longPress, longTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longFired && (Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop)) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) {
                        lastX += dx; lastY += dy
                        listener.onBubbleDrag(dx, dy)
                        handler.removeCallbacks(dragPause)
                        handler.postDelayed(dragPause, 350)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(dragPause)
                when {
                    dragging -> listener.onBubbleDrop()
                    longFired -> {}
                    !doubleTapEnabled -> listener.onBubbleTap()
                    else -> {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastUp < DOUBLE_MS) {
                            handler.removeCallbacks(singleTap)
                            lastUp = 0
                            listener.onBubbleDoubleTap()
                        } else {
                            lastUp = now
                            handler.postDelayed(singleTap, DOUBLE_MS)
                        }
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(dragPause)
                if (dragging) listener.onBubbleDrop()
                dragging = false
            }
        }
        return true
    }
}
