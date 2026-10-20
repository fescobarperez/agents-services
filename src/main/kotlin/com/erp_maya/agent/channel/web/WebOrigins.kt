package com.erp_maya.agent.channel.web

/**
 * Dominios desde donde un widget acepta mensajes. El navegador manda la
 * cabecera `Origin` en toda peticion cruzada y la pagina no la puede falsear:
 * es lo que impide que otro sitio use la llave publica de la empresa.
 *
 * Formato de cada entrada: `https://www.ejemplo.com`, con puerto opcional, o
 * `https://` + `*.ejemplo.com` (comodin) para cualquier subdominio; no incluye ejemplo.com.
 * `http` solo para localhost, para probar el widget en desarrollo.
 */
object WebOrigins {

    const val MAX = 10

    private val FORMA = Regex("""^(https?)://(\*\.)?([a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*)(:(\d{1,5}))?$""")
    private val LOCALES = setOf("localhost", "127.0.0.1")

    /** La entrada en su forma canonica, o null si no es valida. */
    fun normalizar(entrada: String?): String? {
        val s = entrada?.trim()?.lowercase()?.trimEnd('/') ?: return null
        val m = FORMA.matchEntire(s) ?: return null
        val esquema = m.groupValues[1]
        val comodin = m.groupValues[2].isNotEmpty()
        val host = m.groupValues[3]
        val puerto = m.groupValues[8]
        if (puerto.isNotEmpty() && puerto.toInt() !in 1..65535) return null
        val local = host in LOCALES
        if (esquema == "http" && !local) return null
        if (comodin && (local || !host.contains('.'))) return null
        // Un dominio publico necesita al menos un punto (ejemplo.com).
        if (!local && !host.contains('.')) return null
        return s
    }

    /** Valida la lista de la consola: canonicas, sin repetidos. Devuelve (validas, invalidas). */
    fun validar(entradas: List<String>): Pair<List<String>, List<String>> {
        val limpias = entradas.map { it.trim() }.filter { it.isNotEmpty() }
        val malas = limpias.filter { normalizar(it) == null }
        val buenas = limpias.mapNotNull { normalizar(it) }.distinct()
        return buenas to malas
    }

    /** ¿El `Origin` que mando el navegador esta permitido? */
    fun permitido(origin: String?, permitidos: List<String>): Boolean {
        val o = origin?.trim()?.lowercase()?.trimEnd('/') ?: return false
        val m = FORMA.matchEntire(o) ?: return false
        if (m.groupValues[2].isNotEmpty()) return false // el navegador nunca manda comodines
        val esquema = m.groupValues[1]
        val host = m.groupValues[3]
        val puerto = m.groupValues[7]
        return permitidos.any { p ->
            if (p == o) return@any true
            val pm = FORMA.matchEntire(p) ?: return@any false
            pm.groupValues[2].isNotEmpty() &&
                pm.groupValues[1] == esquema &&
                pm.groupValues[7] == puerto &&
                host.endsWith("." + pm.groupValues[3])
        }
    }
}
