package com.erp_maya.agent.channel.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Limite de mensajes por ventana fija de un minuto, en memoria.
 *
 * El canal web es publico: sin limite, cualquiera con la llave del HTML puede
 * gastar el modelo de la empresa. Basta en memoria mientras haya una sola
 * instancia; con varias, cada una cuenta lo suyo (el limite real se
 * multiplica por instancias, que sigue acotado).
 */
class WebRateLimiter(private val reloj: () -> Long = System::currentTimeMillis) {

    private class Ventana(val inicio: Long, var cuenta: Int)

    private val ventanas = ConcurrentHashMap<String, Ventana>()

    /**
     * Cuenta un mensaje para `llave`. Devuelve 0 si pasa, o los segundos que
     * faltan para que se reinicie la ventana si ya se agoto.
     */
    fun intentar(llave: String, maximo: Int): Long {
        if (maximo <= 0) return 0
        val ahora = reloj()
        limpiarSiCrece(ahora)
        var espera = 0L
        ventanas.compute(llave) { _, v ->
            val actual = if (v == null || ahora - v.inicio >= VENTANA_MS) Ventana(ahora, 0) else v
            if (actual.cuenta >= maximo) {
                espera = ((actual.inicio + VENTANA_MS - ahora) / 1000).coerceAtLeast(1)
            } else {
                actual.cuenta++
            }
            actual
        }
        return espera
    }

    private fun limpiarSiCrece(ahora: Long) {
        if (ventanas.size < LIMPIAR_DESDE) return
        ventanas.entries.removeIf { ahora - it.value.inicio >= VENTANA_MS }
    }

    companion object {
        const val VENTANA_MS = 60_000L
        private const val LIMPIAR_DESDE = 10_000
    }
}
