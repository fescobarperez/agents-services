package com.erp_maya.agent.admin

import com.erp_maya.agent.channel.web.WebOrigins
import java.security.SecureRandom

/** Reglas de los widgets web en la consola. Sin base de datos: se prueban solas. */
object WebChannelRules {

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val azar = SecureRandom()

    /** Lo ya validado y en forma canonica, listo para guardar. */
    data class Draft(
        val name: String,
        val origins: List<String>,
        val title: String?,
        val greeting: String?,
        val color: String?,
    )

    /**
     * Valida {name, allowedOrigins, title, greeting, color}. `actual` son los
     * valores guardados: en una edicion, lo que no venga se conserva.
     */
    fun validar(body: Map<String, Any?>, actual: Draft?): Pair<Draft?, MutableMap<String, String>> {
        val f = linkedMapOf<String, String>()
        fun texto(campo: String, previo: String?) = if (body.containsKey(campo)) Parse.text(body[campo]) else previo

        val nombre = texto("name", actual?.name)
        if (nombre == null || nombre.length > 80) f["name"] = "Nombre de 1 a 80 caracteres"

        val origenes: List<String> = if (body.containsKey("allowedOrigins")) {
            val crudos = body["allowedOrigins"] as? List<*>
            if (crudos == null) {
                f["allowedOrigins"] = "Lista inválida"; emptyList()
            } else {
                val (buenos, malos) = WebOrigins.validar(crudos.mapNotNull { it?.toString() })
                when {
                    malos.isNotEmpty() -> f["allowedOrigins"] = "Dominio inválido: ${malos.first()} (usa https://www.ejemplo.com)"
                    buenos.isEmpty() -> f["allowedOrigins"] = "Agrega al menos un dominio"
                    buenos.size > WebOrigins.MAX -> f["allowedOrigins"] = "Máximo ${WebOrigins.MAX} dominios"
                }
                buenos
            }
        } else actual?.origins ?: run { f["allowedOrigins"] = "Agrega al menos un dominio"; emptyList() }

        val titulo = texto("title", actual?.title)
        if (titulo != null && titulo.length > 60) f["title"] = "Máximo 60 caracteres"
        val saludo = texto("greeting", actual?.greeting)
        if (saludo != null && saludo.length > 300) f["greeting"] = "Máximo 300 caracteres"
        val color = texto("color", actual?.color)
        if (color != null && !COLOR.matches(color)) f["color"] = "Color en formato #RRGGBB"

        return if (f.isEmpty()) Draft(nombre!!, origenes, titulo, saludo, color?.uppercase()) to f else null to f
    }

    /** Llave publica del widget: `wk_` y 24 caracteres al azar (≈143 bits). */
    fun nuevaLlave(): String = "wk_" + (1..24).map { ABC[azar.nextInt(ABC.length)] }.joinToString("")
}
