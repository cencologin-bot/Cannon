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
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GameAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: GameAccessibilityService? = null
        private const val MENU_TIMEOUT_MS = 1500L
        /** Espera entre abrir el menú (mano) y pulsar «¡Fuego!». */
        const val FIRE_DELAY_MS = 4000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: ((Boolean) -> Unit)? = null
    @Volatile var lastMethod = ""
    private var fallbackUsed = false
    private var menuSeen = false
    private val fireRunnable = Runnable { fireNow() }
    private val timeout = Runnable { onMenuTimeout() }
    private val poll = object : Runnable {
        override fun run() {
            if (pending == null || menuSeen || fallbackUsed) return
            checkMenu()
            if (!menuSeen) handler.postDelayed(this, 100)
        }
    }

    // Servicio de captura con firma de menú calibrada (visión), si existe.
    private fun vision(): AutoFireService? = AutoFireService.instance?.takeIf { it.hasMenuVision }

    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    // El menú se detecta por evento; al aparecer «¡Fuego!» se espera FIRE_DELAY_MS y se pulsa.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pending == null || event == null || menuSeen) return
        val t = event.eventType
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) checkMenu()
    }

    fun performFireSequence(done: (Boolean) -> Unit) {
        if (pending != null) { done(false); return }
        pending = done
        fallbackUsed = false
        menuSeen = false
        handler.postDelayed(timeout, MENU_TIMEOUT_MS)
        val (w, h) = screenSize()
        val hand = RoiStore.loadPoint(this, "hand")
        tap(w * hand.x, h * hand.y) { checkMenu(); handler.postDelayed(poll, 100) }
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

    // Menú abierto detectado: se espera FIRE_DELAY_MS y entonces se pulsa «¡Fuego!».
    private fun checkMenu() {
        if (pending == null || menuSeen || fallbackUsed) return
        // El menú se da por abierto si hay nodo «¡Fuego!» o si la visión reconoce el menú.
        if (findFireNode(rootInActiveWindow) == null && vision()?.menuOpen != true) return
        menuSeen = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(fireRunnable, FIRE_DELAY_MS)
    }

    private fun fireNow() {
        val cb = pending ?: return
        if (clickNode(findFireNode(rootInActiveWindow))) {
            pending = null
            lastMethod = "accesibilidad"
            handler.postDelayed({ cb(true) }, 120)
        } else fallback(0L) // el menú cambió durante la espera
    }

    // Menú no detectado a tiempo (o desaparecido): plan B opcional por coordenadas.
    private fun onMenuTimeout() {
        if (pending == null) return
        // Con visión calibrada, si el menú no apareció no se toca nada a ciegas.
        if (vision() != null) { finish(false); return }
        fallback(maxOf(0L, FIRE_DELAY_MS - MENU_TIMEOUT_MS)) // completa los 4 s desde la mano
    }

    private fun fallback(wait: Long) {
        if (!RoiStore.planB(this)) { finish(false); return }
        fallbackUsed = true
        handler.postDelayed({
            if (pending == null) return@postDelayed
            val v = vision()
            if (v != null && !v.menuOpen) { finish(false); return@postDelayed } // menú no visible: no tocar
            val (w, h) = screenSize()
            val p = RoiStore.loadPoint(this, "fire")
            tap(w * p.x, h * p.y) {
                if (v != null) {
                    // Verificación visual: el menú debe cerrarse tras el toque.
                    handler.postDelayed({ waitMenuClosed(v, SystemClock.elapsedRealtime() + 1500L) }, 200)
                } else {
                    handler.postDelayed({ lastMethod = "coordenadas"; finish(true) }, 150)
                }
            }
        }, wait)
    }

    private fun waitMenuClosed(v: AutoFireService, until: Long) {
        if (pending == null) return
        if (!v.menuOpen) { lastMethod = "visión+coordenadas"; finish(true); return }
        if (SystemClock.elapsedRealtime() > until) { finish(false); return }
        handler.postDelayed({ waitMenuClosed(v, until) }, 100)
    }

    private fun finish(ok: Boolean) {
        val cb = pending ?: return
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(fireRunnable)
        handler.removeCallbacks(poll)
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
