package com.erp_maya.agent.cita

/**
 * Botones del recordatorio de control: `ctl:{mejor|igual|peor}:{followUpId}`.
 * Reglas puras, como [CitaRules].
 */
object ControlRules {

    const val PREFIJO = "ctl:"
    const val MEJOR = "mejor"
    const val IGUAL = "igual"
    const val PEOR = "peor"
    val RESPUESTAS = listOf(MEJOR, IGUAL, PEOR)
    private val FORMATO = Regex("^ctl:([a-z]+):(\\d+)$")

    fun boton(respuesta: String, followUpId: Long) = "$PREFIJO$respuesta:$followUpId"

    fun esBoton(texto: String?): Boolean = leerBoton(texto) != null

    /** (respuesta, id del control); null si no es uno de los nuestros. */
    fun leerBoton(texto: String?): Pair<String, Long>? {
        val m = texto?.trim()?.let { FORMATO.matchEntire(it) } ?: return null
        val r = m.groupValues[1].takeIf { it in RESPUESTAS } ?: return null
        return r to m.groupValues[2].toLong()
    }
}
