package com.erp_maya.agent.admin

import com.erp_maya.agent.channel.planner.PlannerBudget
import com.erp_maya.agent.channel.planner.PlannerSpecs
import com.erp_maya.agent.channel.planner.ProjectType
import com.erp_maya.agent.channel.web.WebOrigins
import java.math.BigDecimal
import java.security.SecureRandom

/** Validacion de un planificador por presupuesto en la consola, sin base de datos. */
object PlannerWidgetRules {

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val IDIOMAS = setOf("es", "en")
    private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val azar = SecureRandom()

    const val MAX_TIPOS = 12
    const val MAX_CATEGORIAS = 12
    val UMBRAL_MIN = BigDecimal("50")
    val UMBRAL_MAX = BigDecimal("100")

    /** Lo ya validado y en forma canonica, listo para guardar. */
    data class Draft(
        val name: String,
        val origins: List<String>,
        val title: String?,
        val accentColor: String?,
        val lang: String = "es",
        val projectTypes: List<ProjectType>,
        val fitThresholdPct: BigDecimal = PlannerBudget.UMBRAL_DEFECTO,
        val retentionDays: Int = 7,
        val maxOptions: Int = 10,
        /** WhatsApp del asesor: solo digitos con codigo de pais. Null = sin boton de contacto. */
        val contactWhatsapp: String? = null,
        /** Categorias del ERP para elegir producto de una lista (cada una con sus subcategorias). */
        val categoryIds: List<Long> = emptyList(),
    )

    /**
     * Valida {name, allowedOrigins, title, accentColor, lang, projectTypes,
     * categoryIds, fitThresholdPct, retentionDays, maxOptions}. En una
     * edicion (`actual`), lo que no venga se conserva. Hace falta al menos una
     * categoria o un tipo de proyecto.
     */
    fun validar(body: Map<String, Any?>, actual: Draft?): Pair<Draft?, MutableMap<String, String>> {
        val f = linkedMapOf<String, String>()
        fun texto(campo: String, previo: String?) = if (body.containsKey(campo)) Parse.text(body[campo]) else previo
        fun entero(campo: String, previo: Int, rango: IntRange, mensaje: String): Int {
            if (!body.containsKey(campo)) return previo
            val v = Parse.int(body[campo])
            if (v == null || v !in rango) { f[campo] = mensaje; return previo }
            return v
        }

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
        val color = texto("accentColor", actual?.accentColor)
        if (color != null && !COLOR.matches(color)) f["accentColor"] = "Color en formato #RRGGBB"
        val idioma = texto("lang", actual?.lang) ?: "es"
        if (idioma !in IDIOMAS) f["lang"] = "Idioma es o en"

        val tipos = if (body.containsKey("projectTypes")) tipos(body["projectTypes"], f) else actual?.projectTypes.orEmpty()
        val categorias = if (body.containsKey("categoryIds")) categorias(body["categoryIds"], f) else actual?.categoryIds.orEmpty()
        if (tipos.isEmpty() && categorias.isEmpty() && !f.containsKey("projectTypes") && !f.containsKey("categoryIds")) {
            f["categoryIds"] = "Elige al menos una categoría"
        }

        val umbral = if (body.containsKey("fitThresholdPct")) {
            val v = Parse.decimal(body["fitThresholdPct"])
            if (v == null || v < UMBRAL_MIN || v > UMBRAL_MAX || v.scale() > 2) {
                f["fitThresholdPct"] = "Entre 50 y 100 (%)"; actual?.fitThresholdPct ?: PlannerBudget.UMBRAL_DEFECTO
            } else v
        } else actual?.fitThresholdPct ?: PlannerBudget.UMBRAL_DEFECTO
        val retencion = entero("retentionDays", actual?.retentionDays ?: 7, 1..90, "Entre 1 y 90 días")
        val opciones = entero("maxOptions", actual?.maxOptions ?: 10, 1..26, "Entre 1 y 26 opciones")
        val whatsapp = if (body.containsKey("contactWhatsapp")) {
            Parse.text(body["contactWhatsapp"])?.let { crudo ->
                whatsapp(crudo) ?: run { f["contactWhatsapp"] = "Número con código de país, solo dígitos (p. ej. 50212345678)"; null }
            }
        } else actual?.contactWhatsapp

        return if (f.isEmpty()) {
            Draft(nombre!!, origenes, titulo, color?.uppercase(), idioma, tipos, umbral, retencion, opciones, whatsapp, categorias) to f
        } else {
            null to f
        }
    }

    /** [{code, label, productId}]: codigos unicos (a-z, 0-9, _), etiqueta corta y producto del ERP. */
    private fun tipos(crudo: Any?, f: MutableMap<String, String>): List<ProjectType> {
        val lista = crudo as? List<*> ?: run { f["projectTypes"] = "Lista inválida"; return emptyList() }
        if (lista.size > MAX_TIPOS) { f["projectTypes"] = "Máximo $MAX_TIPOS tipos de proyecto"; return emptyList() }
        val out = mutableListOf<ProjectType>()
        lista.forEachIndexed { i, x ->
            val m = x as? Map<*, *> ?: run { f["projectTypes"] = "Tipo ${i + 1} inválido"; return emptyList() }
            val code = Parse.text(m["code"])
            val label = Parse.text(m["label"])
            val producto = Parse.long(m["productId"])
            when {
                code == null || !PlannerSpecs.CODIGO.matches(code) -> f["projectTypes"] = "Tipo ${i + 1}: código en minúsculas, sin espacios (p. ej. pergola)"
                label == null || label.length > 40 -> f["projectTypes"] = "Tipo ${i + 1}: nombre de 1 a 40 caracteres"
                producto == null || producto <= 0 -> f["projectTypes"] = "Tipo ${i + 1}: falta el producto del ERP"
                out.any { it.code == code } -> f["projectTypes"] = "El código «$code» está repetido"
                else -> out += ProjectType(code, label, producto)
            }
            if (f.containsKey("projectTypes")) return emptyList()
        }
        return out
    }

    /** [categoryId, ...]: ids del ERP, sin repetir, en el orden en que se muestran. Que existan lo verifica el servicio. */
    private fun categorias(crudo: Any?, f: MutableMap<String, String>): List<Long> {
        val lista = crudo as? List<*> ?: run { f["categoryIds"] = "Lista inválida"; return emptyList() }
        if (lista.size > MAX_CATEGORIAS) { f["categoryIds"] = "Máximo $MAX_CATEGORIAS categorías"; return emptyList() }
        val out = mutableListOf<Long>()
        for (x in lista) {
            val id = Parse.long(x)
            if (id == null || id <= 0) { f["categoryIds"] = "Categoría inválida"; return emptyList() }
            if (id in out) { f["categoryIds"] = "La categoría $id está repetida"; return emptyList() }
            out += id
        }
        return out
    }

    /** «+502 1234-5678» → «50212345678». Null si no son de 8 a 15 digitos (E.164 sin el +). */
    fun whatsapp(crudo: String): String? =
        crudo.filter { it.isDigit() }.takeIf { crudo.all { c -> c.isDigit() || c in " +-()." } && it.length in 8..15 }

    /** Llave publica del planificador: `pk_` y 24 caracteres al azar (≈143 bits). */
    fun nuevaLlave(): String = "pk_" + (1..24).map { ABC[azar.nextInt(ABC.length)] }.joinToString("")
}
