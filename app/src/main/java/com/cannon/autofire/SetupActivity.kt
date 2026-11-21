package com.cannon.autofire

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    private val captureRequest = 701
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Cannon AutoFire\n\nConfiguración inicial\n\n1. Autoriza la burbuja flotante.\n2. Activa el servicio de accesibilidad.\n3. Autoriza la captura de pantalla. Después, los controles estarán en una burbuja sobre Telegram."
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Autorizar burbuja flotante"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@SetupActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Activar accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Autorizar captura y mostrar burbuja"
            setOnClickListener {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
            }
        })
        setContentView(layout)
    }

    @Deprecated("Activity result retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, AutoFireService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            finish()
        }
    }
}
