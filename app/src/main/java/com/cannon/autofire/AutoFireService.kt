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
            val res = NativeDetector.analyze(buffer, rowStride, pixelStride, left, top, right - left, bottom - top)
            val color = res[2].toInt()
            previewListener?.invoke(res[0], res[1], color)
            greenStreak = if (color == 2) greenStreak + 1 else 0
            val now = SystemClock.elapsedRealtime()
            if (color == 1) {
                // Faro rojo: no se inician disparos nuevos (el que está en curso termina).
                if (running) updateStatus("Faro rojo · en pausa")
            } else if (color == 2 && greenStreak >= GREEN_FRAMES) {
                // Faro verde: dispara y repite mientras siga verde; si pasa a rojo se pausa y
                // al volver a verde continúa solo.
                if (running && !firing && now >= nextShotAt) {
                    firing = true
                    updateStatus("Verde · mano, ¡Fuego! en ${GameAccessibilityService.FIRE_DELAY_MS / 1000} s")
                    GameAccessibilityService.instance?.performFireSequence { ok ->
                        firing = false
                        val t = SystemClock.elapsedRealtime()
                        if (ok) {
                            shots++; failures = 0; nextShotAt = t + SHOT_GAP_MS
                            updateStatus("Disparo enviado (${GameAccessibilityService.instance?.lastMethod}) · verde, continúa")
                        } else {
                            failures++; nextShotAt = t + RETRY_MS
                            if (failures >= MAX_FAILURES) {
                                stopAutomation()
                                updateStatus("Detenido: no se pudo seleccionar ¡Fuego!")
                            } else updateStatus("No se pudo seleccionar ¡Fuego! · reintentando")
                        }
                        updateNotification()
                    } ?: run {
                        firing = false
                        nextShotAt = now + 2000
                        updateStatus("Activa el servicio de accesibilidad")
                    }
                }
            }
        } finally { image.close() }
    }

    private fun showBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.argb(235, 35, 42, 56))
        }
        val header = TextView(this).apply {
            text = "● Cannon AutoFire  ⋮"
            setTextColor(Color.WHITE); textSize = 14f
            setOnClickListener { toggleExpanded() }
        }
        status = TextView(this).apply {
            text = "Captura preparada"
            setTextColor(Color.WHITE); textSize = 12f
            visibility = View.GONE
        }
        val start = Button(this).apply {
            text = "ACTIVAR"
            setOnClickListener {
                if (projection == null) status.text = "Falta autorizar captura"
                else { running = true; failures = 0; nextShotAt = 0L; updateStatus("Activo · dispara mientras el faro esté verde") }
            }
        }
        val stop = Button(this).apply {
            text = "DETENER"
            setOnClickListener { stopAutomation() }
        }
        val sel = Button(this).apply {
            text = "SELECTOR"
            setOnClickListener { showSelector() }
        }
        val hand = Button(this).apply {
            text = "MANO"
            setOnClickListener { showPointPicker("hand", "Posición del icono de la mano") }
        }
        val fire = Button(this).apply {
            text = "FUEGO"
            setOnClickListener { showPointPicker("fire", "Abre el menú y marca la opción ¡Fuego!") }
        }
        val menuCal = Button(this).apply {
            text = "MENÚ"
            setOnClickListener {
                if (projection == null) updateStatus("Falta autorizar captura")
                else { calibrateMenu = true; updateStatus("Calibrando menú... (debe estar abierto)") }
            }
        }
        val planB = Button(this).apply {
            text = planBText()
            setOnClickListener { RoiStore.setPlanB(this@AutoFireService, !RoiStore.planB(this@AutoFireService)); text = planBText() }
        }
        val extras = listOf(sel, hand, fire, menuCal, planB)
        start.visibility = View.GONE
        stop.visibility = View.GONE
        extras.forEach { it.visibility = View.GONE }
        root.addView(header); root.addView(status)
        root.addView(start); root.addView(stop)
        extras.forEach { root.addView(it) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 240 }
        header.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=lp.x; oy=lp.y; return true }
                    MotionEvent.ACTION_MOVE -> { lp.x=ox-(e.rawX-sx).toInt(); lp.y=oy+(e.rawY-sy).toInt(); window?.updateViewLayout(root,lp); return true }
                    MotionEvent.ACTION_UP -> { if (kotlin.math.abs(e.rawX-sx)<8 && kotlin.math.abs(e.rawY-sy)<8) toggleExpanded(); return true }
                }
                return false
            }
        })
        window?.addView(root, lp)
        bubble = root
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

    private fun toggleExpanded() {
        expanded = !expanded
        status.visibility = if (expanded) View.VISIBLE else View.GONE
        for (i in 2 until (bubble as? LinearLayout)?.childCount.orZero()) {
            (bubble as LinearLayout).getChildAt(i).visibility = if (expanded) View.VISIBLE else View.GONE
        }
    }
    private fun Int?.orZero() = this ?: 0

    private fun stopAutomation() {
        running = false
        firing = false
        redSeen = false
        greenStreak = 0
        updateStatus("Detenido")
    }

    private fun updateStatus(s: String) {
        val key = "$s|$shots"
        if (key == lastStatus) return
        lastStatus = key
        if (::status.isInitialized) status.post { status.text = "$s\nDisparos: $shots" }
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
