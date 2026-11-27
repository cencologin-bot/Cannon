package com.cannon.autofire

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.max
import kotlin.math.min

class AutoFireService : Service() {
    companion object {
        const val ACTION_STOP = "com.cannon.autofire.STOP"
        const val CHANNEL = "cannon_autofire"
        const val GREEN_FRAMES = 2       // fotogramas verdes seguidos para confirmar
        const val SHOT_GAP_MS = 250L     // pausa mínima entre disparos mientras sigue verde
        const val RETRY_MS = 1500L       // espera tras un fallo
        const val MAX_FAILURES = 3       // fallos seguidos antes de detener
        const val MENU_MATCH = 0.85f     // fracción de celdas que debe coincidir para dar el menú por abierto
        @Volatile var instance: AutoFireService? = null
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var window: WindowManager? = null
    private var bubble: View? = null
    private var expanded = false
    private var running = false
    private var redSeen = false
    private var firing = false
    private var shots = 0
    private var lastFrame = 0L
    private var roi = RoiStore.defaults()
    private var greenStreak = 0
    private var redStreak = 0
    private var armed = true   // true = puede disparar en el verde actual
    private var nextShotAt = 0L
    private var failures = 0
    private var menuSig: ByteArray? = null
    private var calibrateMenu = false
    /** Visión: true mientras la captura coincide con la firma del menú calibrada. */
    @Volatile var menuOpen = false
    val hasMenuVision: Boolean get() = menuSig != null
    private var lastStatus = ""
    private var selector: View? = null
    private var previewListener: ((Float, Float, Int) -> Unit)? = null
    private lateinit var status: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        roi = RoiStore.load(this)
        menuSig = RoiStore.loadMenuSig(this)
        createChannel()
        startForeground(41, notification("Preparado"))
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAutomation(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projectionData")
        }
        if (code != 0 && data != null && projection == null) startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cannon AutoFire", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 2,
            Intent(this, AutoFireService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("Cannon AutoFire")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Detener", stop).build()).build()
    }

    private fun startCapture(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        // Android 14+: es obligatorio registrar el callback antes de crear el VirtualDisplay.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopAutomation(); updateStatus("Captura detenida por el sistema") }
        }, Handler(Looper.getMainLooper()))
        val metrics = resources.displayMetrics
        val real = Point().also { @Suppress("DEPRECATION") window?.defaultDisplay?.getRealSize(it) }
        val w = if (real.x > 0) real.x else metrics.widthPixels
        val h = if (real.y > 0) real.y else metrics.heightPixels
        val dpi = metrics.densityDpi
        reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("CannonCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
        reader?.setOnImageAvailableListener({ r ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame >= 80) {
                lastFrame = now
                processFrame(r.acquireLatestImage())
            } else r.acquireLatestImage()?.close()
        }, Handler(Looper.getMainLooper()))
    }

    private fun processFrame(image: Image?) {
        image ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val fullW = image.width
            val fullH = image.height
            // Recuadro del faro (normalizado, elegido con el SELECTOR).
            val left = (fullW * roi.left).toInt().coerceIn(0, fullW - 1)
            val right = (fullW * roi.right).toInt().coerceIn(left + 1, fullW)
            val top = (fullH * roi.top).toInt().coerceIn(0, fullH - 1)
            val bottom = (fullH * roi.bottom).toInt().coerceIn(top + 1, fullH)
            if (calibrateMenu) {
                calibrateMenu = false
                val sig = NativeDetector.menuSignature(buffer, rowStride, pixelStride, fullW, fullH)
                menuSig = sig
                RoiStore.saveMenuSig(this, sig)
                updateStatus("Menú calibrado ✓ (visión activa)")
            }
            menuSig?.let {
                menuOpen = NativeDetector.menuMatch(buffer, rowStride, pixelStride, fullW, fullH, it) >= MENU_MATCH
            }
            val res = NativeDetector.analyze(buffer, rowStride, pixelStride, left, top, right - left, bottom - top, RoiStore.lite(this))
            val color = res[2].toInt()
            previewListener?.invoke(res[0], res[1], color)
            greenStreak = if (color == 2) greenStreak + 1 else 0
            redStreak = if (color == 1) redStreak + 1 else 0
            val now = SystemClock.elapsedRealtime()
            if (color == 1) {
                // Faro rojo: no se inician disparos nuevos (el que está en curso termina).
                if (redStreak >= GREEN_FRAMES) armed = true // rojo confirmado: rearma para el próximo verde
                if (running) updateStatus("Faro rojo · en pausa")
            } else if (color == 2 && greenStreak >= GREEN_FRAMES) {
                if (running && !firing && !armed && RoiStore.oneShot(this)) updateStatus("Verde ya usado · esperando rojo")
                // Faro verde: dispara y repite mientras siga verde; si pasa a rojo se pausa y
                // al volver a verde continúa solo.
                if (running && !firing && now >= nextShotAt && (armed || !RoiStore.oneShot(this))) {
                    firing = true
                    armed = false
                    updateStatus("Verde · mano, ¡Fuego! en ${"%.1f".format(RoiStore.fireDelayMs(this) / 1000.0)} s")
                    GameAccessibilityService.instance?.performFireSequence { ok ->
                        firing = false
                        val t = SystemClock.elapsedRealtime()
                        if (ok) {
                            shots++; failures = 0; nextShotAt = t + SHOT_GAP_MS
                            updateStatus("Disparo enviado (${GameAccessibilityService.instance?.lastMethod}) · ${if (RoiStore.oneShot(this)) "esperando rojo" else "verde, continúa"}")
                        } else {
                            failures++; nextShotAt = t + RETRY_MS; armed = true // no hubo disparo: permite reintentar
                            if (failures >= MAX_FAILURES) {
                                stopAutomation()
                                updateStatus("Detenido: no se pudo seleccionar ¡Fuego!")
                            } else updateStatus("No se pudo seleccionar ¡Fuego! · reintentando")
                        }
                        updateNotification()
                    } ?: run {
                        firing = false
                        armed = true
                        nextShotAt = now + 2000
                        updateStatus("Activa el servicio de accesibilidad")
                    }
                }
            }
        } finally { image.close() }
    }

    // ---------- Burbuja compacta ----------
    private val ui = Handler(Looper.getMainLooper())
    private var dotView: TextView? = null
    private var panelView: LinearLayout? = null
    private var settingsView: View? = null
    private var toggleBtn: TextView? = null
    private var planBBtn: TextView? = null
    private var oneShotBtn: TextView? = null
    private var liteBtn: TextView? = null
    private fun liteText() = if (RoiStore.lite(this)) "Lite: ON" else "Lite: OFF"
    private fun oneShotText() = if (RoiStore.oneShot(this)) "1 por verde: ON" else "1 por verde: OFF"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun chip(label: String, color: Int = Color.rgb(62, 72, 98), onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 12f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = pill(color, 12)
        setOnClickListener { onClick() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    }

    /** Cierra el panel (para que no salga en la captura) y ejecuta la acción un instante después. */
    private fun collapseThen(action: () -> Unit) { setExpanded(false); ui.postDelayed({ action() }, 300) }

    private fun startAutomation() {
        if (projection == null) { updateStatus("Falta autorizar captura"); return }
        running = true; failures = 0; nextShotAt = 0L; armed = true; redStreak = 0
        updateStatus("Activo · dispara mientras el faro esté verde")
        setExpanded(false)
    }

    private fun showBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val green = Color.rgb(47, 158, 68); val red = Color.rgb(192, 57, 43)
        val root = FrameLayout(this)

        val dot = TextView(this).apply {
            text = "●"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY)
            background = pill(Color.argb(210, 35, 42, 56), 18)
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
        }
        status = TextView(this).apply {
            text = "Captura preparada"; setTextColor(Color.WHITE); textSize = 11f; maxWidth = dp(190)
        }

        // Ajustes (ocultos hasta pulsar ⚙)
        val delayLbl = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; minWidth = dp(44) }
        fun showDelay() { delayLbl.text = "%.1f s".format(RoiStore.fireDelayMs(this) / 1000.0) }
        fun step(d: Long) {
            RoiStore.setFireDelayMs(this, (RoiStore.fireDelayMs(this) + d).coerceIn(500L, 15000L)); showDelay()
        }
        showDelay()
        val planB = chip(planBText()) {
            RoiStore.setPlanB(this, !RoiStore.planB(this)); refreshUiNow()
        }
        planBBtn = planB
        val oneShot = chip(oneShotText()) {
            RoiStore.setOneShot(this, !RoiStore.oneShot(this)); refreshUiNow()
        }
        oneShotBtn = oneShot
        val lite = chip(liteText()) {
            RoiStore.setLite(this, !RoiStore.lite(this)); refreshUiNow()
        }
        liteBtn = lite
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(row(
                chip("Faro") { collapseThen { showSelector() } },
                chip("Mano") { collapseThen { showPointPicker("hand", "Posición del icono de la mano") } },
                chip("Fuego") { collapseThen { showPointPicker("fire", "Abre el menú y marca la opción ¡Fuego!") } }))
            addView(row(
                chip("Menú") { collapseThen {
                    if (projection == null) updateStatus("Falta autorizar captura")
                    else { calibrateMenu = true; updateStatus("Calibrando menú... (debe estar abierto)") }
                } }, planB))
            addView(row(oneShot, lite))
            addView(row(
                TextView(this@AutoFireService).apply { text = "Espera ¡Fuego!"; setTextColor(Color.WHITE); textSize = 12f },
                chip("−") { step(-500L) }, delayLbl, chip("+") { step(500L) }))
        }
        settingsView = settings

        val toggle = chip("▶ Activar", green) { if (running) stopAutomation() else startAutomation() }
        toggleBtn = toggle
        val gear = chip("⚙") { settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val minimize = chip("▾") { setExpanded(false) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = pill(Color.argb(235, 35, 42, 56), 14)
            visibility = View.GONE
            addView(status)
            addView(row(toggle, gear, minimize))
            addView(settings)
        }
        panelView = panel; dotView = dot
        root.addView(dot); root.addView(panel)
        root.alpha = 0.8f

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 240 }

        // Arrastrar desde el punto o desde el texto de estado; un toque corto en el punto abre el panel.
        fun drag(onTap: (() -> Unit)?) = object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; return true }
                    MotionEvent.ACTION_MOVE -> { lp.x = ox - (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); window?.updateViewLayout(root, lp); return true }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(e.rawX - sx) < 8 && kotlin.math.abs(e.rawY - sy) < 8) onTap?.invoke()
                        return true
                    }
                }
                return false
            }
        }
        dot.setOnTouchListener(drag { setExpanded(true) })
        status.setOnTouchListener(drag(null))
        window?.addView(root, lp)
        bubble = root
        refreshUiNow()
    }

    private fun setExpanded(e: Boolean) {
        expanded = e
        dotView?.visibility = if (e) View.GONE else View.VISIBLE
        panelView?.visibility = if (e) View.VISIBLE else View.GONE
        if (!e) settingsView?.visibility = View.GONE
        bubble?.alpha = if (e) 1f else 0.8f
    }

    private fun refreshUiNow() {
        dotView?.setTextColor(when {
            running && firing -> Color.rgb(255, 193, 7)
            running -> Color.rgb(77, 255, 110)
            else -> Color.LTGRAY
        })
        toggleBtn?.apply {
            text = if (running) "■ Detener" else "▶ Activar"
            background = pill(if (running) Color.rgb(192, 57, 43) else Color.rgb(47, 158, 68), 12)
        }
        planBBtn?.text = planBText()
        oneShotBtn?.text = oneShotText()
        liteBtn?.text = liteText()
    }

    private fun showSelector() {
        if (selector != null || !android.provider.Settings.canDrawOverlays(this)) return
        stopAutomation() // nunca dispara mientras se ajusta el recuadro
        val view = RoiSelectorView(this, RoiStore.load(this))
        previewListener = { g, r, c ->
            val n = when (c) { 1 -> "ROJO"; 2 -> "VERDE"; else -> "sin color" }
            view.info = "Verde %.0f%%  Rojo %.0f%%  -> %s".format(g * 100, r * 100, n)
            view.invalidate()
        }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 24)
            setBackgroundColor(Color.argb(235, 35, 42, 56))
            addView(btn("GUARDAR") {
                roi = RectF(view.roi); RoiStore.save(this@AutoFireService, roi)
                closeSelector(); updateStatus("Recuadro guardado")
            })
            addView(btn("RESTABLECER") { view.roi.set(RoiStore.defaults()); view.invalidate() })
            addView(btn("CANCELAR") { closeSelector() })
        }
        openOverlay(view, bar)
    }

    private fun openOverlay(content: View, bar: LinearLayout) {
        val frame = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        window?.addView(frame, lp)
        selector = frame
    }

    /** key = "hand" o "fire". Para "fire": abre antes el menú del cañón y luego pulsa FUEGO en la burbuja. */
    private fun showPointPicker(key: String, title: String) {
        if (selector != null || !android.provider.Settings.canDrawOverlays(this)) return
        stopAutomation()
        val view = PointPickerView(this, RoiStore.loadPoint(this, key), title)
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 24)
            setBackgroundColor(Color.argb(235, 35, 42, 56))
            addView(btn("GUARDAR") {
                RoiStore.savePoint(this@AutoFireService, key, PointF(view.point.x, view.point.y))
                closeSelector(); updateStatus("Punto guardado: $key")
            })
            addView(btn("RESTABLECER") { val d = RoiStore.defaultPoint(key); view.point.set(d.x, d.y); view.invalidate() })
            addView(btn("CANCELAR") { closeSelector() })
        }
        openOverlay(view, bar)
    }

    private fun planBText() = if (RoiStore.planB(this)) "PLAN B: ON" else "PLAN B: OFF"

    private fun closeSelector() {
        previewListener = null
        selector?.let { try { window?.removeView(it) } catch (_: Exception) {} }
        selector = null
    }

    private fun stopAutomation() {
        running = false
        firing = false
        redSeen = false
        greenStreak = 0
        updateStatus("Detenido")
        ui.post { refreshUiNow() }
    }

    private fun updateStatus(s: String) {
        val key = "$s|$shots"
        if (key == lastStatus) return
        lastStatus = key
        if (::status.isInitialized) status.post { status.text = "$s\nDisparos: $shots" }
        ui.post { refreshUiNow() }
        updateNotification(s)
    }
    private fun updateNotification(s: String = if (running) "Automatización activa" else "Detenido") {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(41, notification(s))
    }

    override fun onDestroy() {
        stopAutomation()
        closeSelector()
        display?.release(); reader?.close(); projection?.stop()
        bubble?.let { try { window?.removeView(it) } catch (_: Exception) {} }
        instance = null
        super.onDestroy()
    }
}
