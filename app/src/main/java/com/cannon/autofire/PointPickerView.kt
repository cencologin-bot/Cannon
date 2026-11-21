package com.cannon.autofire

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Mira para elegir un punto de la pantalla: toca o arrastra. Coordenadas normalizadas 0..1. */
class PointPickerView(ctx: Context, initial: PointF, private val title: String) : View(ctx) {
    val point = PointF(initial.x, initial.y)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.CYAN }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; setShadowLayer(6f, 0f, 0f, Color.BLACK) }

    override fun onDraw(c: Canvas) {
        val x = point.x * width; val y = point.y * height
        c.drawCircle(x, y, 44f, ring)
        c.drawLine(x - 70f, y, x + 70f, y, ring)
        c.drawLine(x, y - 70f, x, y + 70f, ring)
        c.drawCircle(x, y, 7f, dot)
        c.drawText(title, 24f, 150f, txt)
        c.drawText("Toca o arrastra la mira al centro del objetivo", 24f, 200f, txt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            point.set((e.x / width).coerceIn(0f, 1f), (e.y / height).coerceIn(0f, 1f))
            invalidate()
        }
        return true
    }
}
