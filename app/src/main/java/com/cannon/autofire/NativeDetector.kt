package com.cannon.autofire

import java.nio.ByteBuffer

object NativeDetector {
    init { System.loadLibrary("cannon_detector") }

    /**
     * Analiza el recuadro del faro en HSV directamente sobre el buffer de la captura.
     * Devuelve [fracción verde, fracción roja, clase]: 0 = nada, 1 = rojo, 2 = verde. Formato RGBA_8888.
     */
    external fun analyze(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int
    ): FloatArray

    /** Firma del menú abierto: rejilla 12x10 de colores medios de la mitad inferior de la pantalla (360 bytes). */
    external fun menuSignature(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, fullW: Int, fullH: Int
    ): ByteArray

    /** Fracción (0..1) de celdas del menú que coinciden con la firma guardada. */
    external fun menuMatch(
        buffer: ByteBuffer, rowStride: Int, pixelStride: Int, fullW: Int, fullH: Int, signature: ByteArray
    ): Float
}
