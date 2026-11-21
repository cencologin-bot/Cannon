package com.cannon.autofire

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GameAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: GameAccessibilityService? = null
        private const val MENU_TIMEOUT_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: ((Boolean) -> Unit)? = null
    @Volatile var lastMethod = ""
    private var fallbackUsed = false
    private val timeout = Runnable { onMenuTimeout() }

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    // El menú se detecta por evento: en cuanto aparece «¡Fuego!» se pulsa, sin espera fija.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pending == null || event == null) return
        val t = event.eventType
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) tryClickFire()
    }

    fun performFireSequence(done: (Boolean) -> Unit) {
        if (pending != null) { done(false); return }
        pending = done
        fallbackUsed = false
        handler.postDelayed(timeout, MENU_TIMEOUT_MS)
        val (w, h) = screenSize()
        val hand = RoiStore.loadPoint(this, "hand")
        tap(w * hand.x, h * hand.y) { tryClickFire() }
    }

    // Tamaño real de pantalla (incluye barras), igual que la captura y el selector.
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val p = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        return p.x to p.y
    }

    // Plan B (opcional): «¡Fuego!» no apareció en el árbol de accesibilidad -> tocar su punto guardado.
    private fun onMenuTimeout() {
        if (pending == null) return
        if (!RoiStore.planB(this)) { finish(false); return }
        fallbackUsed = true
        val (w, h) = screenSize()
        val p = RoiStore.loadPoint(this, "fire")
        tap(w * p.x, h * p.y) {
            handler.postDelayed({ lastMethod = "coordenadas"; finish(true) }, 150)
        }
    }

    private fun tryClickFire() {
        if (fallbackUsed) return
        val cb = pending ?: return
        val node = findFireNode(rootInActiveWindow) ?: return
        if (clickNode(node)) {
            handler.removeCallbacks(timeout)
            pending = null
            lastMethod = "accesibilidad"
            handler.postDelayed({ cb(true) }, 120)
        }
    }

    private fun finish(ok: Boolean) {
        val cb = pending ?: return
        handler.removeCallbacks(timeout)
        pending = null
        cb(ok)
    }

    private fun findFireNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (n in root.findAccessibilityNodeInfosByText("¡Fuego!")) {
            if (n.text?.toString()?.contains("Fuego", ignoreCase = true) == true) return n
            val parent = n.parent
            if (parent != null && parent.isClickable) return parent
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    private fun tap(x: Float, y: Float, complete: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { complete() }
            override fun onCancelled(gestureDescription: GestureDescription?) { complete() }
        }, null)
    }
}
