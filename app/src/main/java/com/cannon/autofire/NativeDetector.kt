package com.cannon.autofire

import java.nio.ByteBuffer

object NativeDetector {
    init { System.loadLibrary("cannon_detector") }

    /**
     * Analiza solo el recuadro del faro directamente sobre el buffer de la captura
     * (sin copiar píxeles a Kotlin). Devuelve [fracción verde, fracción roja, clase]
     * con clase 0 = nada, 1 = rojo, 2 = verde. Formato esperado: RGBA_8888.
     */
    external fun analyze(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int
    ): FloatArray
}
