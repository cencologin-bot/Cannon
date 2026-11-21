package com.cannon.autofire

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * Selector a pantalla completa: arrastra el recuadro sobre la luz del faro y
 * redimensiona con las esquinas. El interior del recuadro NO se dibuja (ni borde
 * ni asas), así la captura ve el faro limpio y la vista previa es fiel.
 */
class RoiSelectorView(ctx: Context, initial: RectF) : View(ctx) {
    val roi = RectF(initial)          // normalizado 0..1
    var info = ""

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.CYAN }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private val hr = 16f              // radio de asa
    private val touch = 64f
    private var mode = 0              // 1 mover, 2 TL, 3 TR, 4 BL, 5 BR
    private var lx = 0f
    private var ly = 0f

    private fun px() = RectF(roi.left * width, roi.top * height, roi.right * width, roi.bottom * height)

    override fun onDraw(c: Canvas) {
        val r = px()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, r.top, dim)
        c.drawRect(0f, r.bottom, w, h, dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, w, r.bottom, dim)
        val o = RectF(r).apply { inset(-5f, -5f) }     // borde por fuera del ROI
        c.drawRect(o, line)
        for ((x, y) in corners(r)) c.drawCircle(x, y, hr, dot)
        c.drawText("Coloca el recuadro solo sobre la luz del faro", 24f, 150f, txt)
        val y = if (r.top > 260f) r.top - 40f else r.bottom + 70f
        c.drawText(info, 24f, y, txt)
    }

    private fun corners(r: RectF) = listOf(
        (r.left - hr - 5f) to (r.top - hr - 5f), (r.right + hr + 5f) to (r.top - hr - 5f),
        (r.left - hr - 5f) to (r.bottom + hr + 5f), (r.right + hr + 5f) to (r.bottom + hr + 5f)
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = px()
                mode = 0
                corners(r).forEachIndexed { i, (x, y) ->
                    if (mode == 0 && Math.hypot((e.x - x).toDouble(), (e.y - y).toDouble()) < touch) mode = 2 + i
                }
                if (mode == 0 && RectF(r).apply { inset(-30f, -30f) }.contains(e.x, e.y)) mode = 1
                lx = e.x; ly = e.y
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lx) / width; val dy = (e.y - ly) / height
                val minW = 24f / width; val minH = 24f / height
                when (mode) {
                    1 -> {
                        val w = roi.width(); val h = roi.height()
                        val l = (roi.left + dx).coerceIn(0f, 1f - w)
                        val t = (roi.top + dy).coerceIn(0f, 1f - h)
                        roi.set(l, t, l + w, t + h)
                    }
                    2 -> { roi.left = (roi.left + dx).coerceIn(0f, roi.right - minW); roi.top = (roi.top + dy).coerceIn(0f, roi.bottom - minH) }
                    3 -> { roi.right = (roi.right + dx).coerceIn(roi.left + minW, 1f); roi.top = (roi.top + dy).coerceIn(0f, roi.bottom - minH) }
                    4 -> { roi.left = (roi.left + dx).coerceIn(0f, roi.right - minW); roi.bottom = (roi.bottom + dy).coerceIn(roi.top + minH, 1f) }
                    5 -> { roi.right = (roi.right + dx).coerceIn(roi.left + minW, 1f); roi.bottom = (roi.bottom + dy).coerceIn(roi.top + minH, 1f) }
                }
                lx = e.x; ly = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
        }
        return true
    }
}
