package com.erp_maya.agent.api.service

/**
 * Los argumentos de una herramienta tal como van al log: sin datos personales.
 *
 * El log sirve para seguir lo que hizo el agente (que herramienta, con que
 * medidas, tipo o producto), no para guardar quien es el cliente. Los campos
 * que son de la persona se ocultan enteros y, en cualquier otro texto, se
 * tacha lo que parezca un telefono, un NIT o un correo.
 */
internal object ArgumentosEnLog {

    const val OCULTO = "***"

    /** Claves cuyo valor es de la persona o lo que dijo con sus palabras. */
    private val PERSONALES = setOf(
        "nombre", "name", "nit", "cf", "dpi", "telefono", "phone", "celular", "whatsapp",
        "correo", "email", "direccion", "address", "valor", "texto", "mensaje", "motivo",
    )

    // Siete o mas digitos seguidos (con espacios o guiones entre medio): telefonos y NIT.
    private val NUMERO_LARGO = Regex("""\+?\d(?:[\s-]?\d){6,}""")
    private val CORREO = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

    fun de(argumentos: Map<String, Any?>): Map<String, Any?> =
        argumentos.mapValues { (clave, valor) ->
            if (clave.lowercase() in PERSONALES && valor != null) OCULTO else limpiar(valor)
        }

    private fun limpiar(valor: Any?): Any? = when (valor) {
        is String -> CORREO.replace(NUMERO_LARGO.replace(valor, OCULTO), OCULTO)
        is Map<*, *> -> valor.entries.associate { (k, v) ->
            k to if (k is String && k.lowercase() in PERSONALES && v != null) OCULTO else limpiar(v)
        }
        is List<*> -> valor.map { limpiar(it) }
        else -> valor
    }
}
