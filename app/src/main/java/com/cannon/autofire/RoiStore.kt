package com.cannon.autofire

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Base64

/** Guarda el área del faro (coordenadas normalizadas 0..1) elegida con el selector. */
object RoiStore {
    fun defaults() = RectF(0.775f, 0.635f, 0.835f, 0.695f)

    fun load(c: Context): RectF {
        val sp = c.getSharedPreferences("roi", Context.MODE_PRIVATE)
        val d = defaults()
        return RectF(
            sp.getFloat("l", d.left), sp.getFloat("t", d.top),
            sp.getFloat("r", d.right), sp.getFloat("b", d.bottom)
        )
    }

    fun save(c: Context, r: RectF) {
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).edit()
            .putFloat("l", r.left).putFloat("t", r.top)
            .putFloat("r", r.right).putFloat("b", r.bottom).apply()
    }

    /** Puntos normalizados: "hand" = icono de la mano, "fire" = opción ¡Fuego! del menú. */
    fun defaultPoint(key: String) = if (key == "hand") PointF(0.943f, 0.843f) else PointF(0.22f, 0.5375f)

    fun loadPoint(c: Context, key: String): PointF {
        val sp = c.getSharedPreferences("roi", Context.MODE_PRIVATE)
        val d = defaultPoint(key)
        return PointF(sp.getFloat("${key}_x", d.x), sp.getFloat("${key}_y", d.y))
    }

    fun savePoint(c: Context, key: String, p: PointF) {
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).edit()
            .putFloat("${key}_x", p.x).putFloat("${key}_y", p.y).apply()
    }

    /** Plan B: si no se encuentra «¡Fuego!» por accesibilidad, tocar el punto "fire". Desactivado por defecto. */
    fun planB(c: Context) = c.getSharedPreferences("roi", Context.MODE_PRIVATE).getBoolean("planb", false)
    fun setPlanB(c: Context, on: Boolean) {
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).edit().putBoolean("planb", on).apply()
    }

    fun saveMenuSig(c: Context, sig: ByteArray) {
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).edit()
            .putString("menusig", Base64.encodeToString(sig, Base64.NO_WRAP)).apply()
    }

    fun loadMenuSig(c: Context): ByteArray? =
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).getString("menusig", null)
            ?.let { try { Base64.decode(it, Base64.NO_WRAP) } catch (_: Exception) { null } }

    /** Espera entre abrir el menú (mano) y pulsar «¡Fuego!», en ms. Ajustable desde la burbuja. */
    fun fireDelayMs(c: Context): Long = c.getSharedPreferences("roi", Context.MODE_PRIVATE).getLong("fire_delay", 4000L)
    fun setFireDelayMs(c: Context, ms: Long) {
        c.getSharedPreferences("roi", Context.MODE_PRIVATE).edit().putLong("fire_delay", ms).apply()
    }
}
