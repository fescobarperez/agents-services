package com.erp_maya.agent.admin

import com.erp_maya.agent.tools.domain.ToolDefinition
import com.erp_maya.agent.context.domain.ToolMode
import java.math.BigDecimal

/**
 * Reglas de la consola para modelos y agentes. Las mismas de
 * `src/lib/ai.js` en stack-line-gestion-ui: la UI valida para ayudar, esto
 * valida para que nada invalido llegue a la base. Sin estado ni base: se
 * prueba solo.
 */
object AdminRules {

    val TEMPERATURE_MIN: BigDecimal = BigDecimal.ZERO
    val TEMPERATURE_MAX: BigDecimal = BigDecimal.ONE
    const val LOOPS_MIN = 0
    const val LOOPS_MAX = 10
    const val TIMEOUT_MIN = 5_000
    const val TIMEOUT_MAX = 120_000
    const val CONTEXT_MIN = 1_000
    const val PROMPT_MIN = 20

    /** Codigo de un tipo de agente: minusculas, numeros y guion bajo. */
    val CODE = Regex("^[a-z][a-z0-9_]{1,59}$")
    val KINDS = setOf("conversational", "internal")
    /** Como se manda la llave al proveedor. */
    val AUTH_TYPES = setOf("bearer", "x-api-key")
    /** Nombre de variable de entorno: la llave nunca se escribe aqui. */
    private val VARIABLE = Regex("^[A-Z][A-Z0-9_]{2,119}$")
    private val URL = Regex("^https?://[^\\s]+$")

    data class ProviderDraft(val name: String, val baseUrl: String, val authType: String, val credentialRef: String)

    fun validateProvider(name: String?, baseUrl: String?, authType: String?, credentialRef: String?): Pair<ProviderDraft?, Map<String, String>> {
        val f = linkedMapOf<String, String>()
        if (name.isNullOrBlank() || name.length > 80) f["name"] = "Nombre de 1 a 80 caracteres"
        if (baseUrl == null || baseUrl.length > 300 || !URL.matches(baseUrl)) f["baseUrl"] = "URL http(s) válida"
        if (authType == null || authType !in AUTH_TYPES) f["authType"] = "bearer o x-api-key"
        if (credentialRef == null || !VARIABLE.matches(credentialRef)) {
            f["credentialRef"] = "Nombre de variable en MAYÚSCULAS (p. ej. OPENAI_API_KEY), no la llave"
        }
        if (f.isNotEmpty()) return null to f
        return ProviderDraft(name!!, baseUrl!!.trimEnd('/'), authType!!, credentialRef!!) to f
    }

    /** Lo minimo de un modelo para validar un agente que lo usa. */
    data class ModelInfo(val id: Long, val active: Boolean, val supportsTools: Boolean, val generatesImages: Boolean)

    data class ModelDraft(
        val modelName: String?,
        val contextWindow: Int?,
        val inputCost1k: BigDecimal?,
        val cachedCost1k: BigDecimal?,
        val outputCost1k: BigDecimal?,
        val generatesImages: Boolean,
        val imageCost: BigDecimal?,
    )

    data class AgentDraft(
        val modelId: Long?,
        val fallbackModelId: Long?,
        val imageModelId: Long?,
        val temperature: BigDecimal?,
        val maxToolLoops: Int?,
        val responseTimeoutMs: Int?,
        val tools: List<ToolGrantView>,
    )

    fun validateModel(m: ModelDraft): Map<String, String> {
        val f = linkedMapOf<String, String>()
        if (m.modelName.isNullOrBlank()) f["modelName"] = "El nombre es obligatorio"
        if (m.contextWindow == null || m.contextWindow < CONTEXT_MIN) f["contextWindow"] = "Entero de 1 000 o más"
        if (!noNegativo(m.inputCost1k)) f["inputCost1k"] = NUMERO
        if (m.cachedCost1k != null && !noNegativo(m.cachedCost1k)) f["cachedCost1k"] = NUMERO
        if (!noNegativo(m.outputCost1k)) f["outputCost1k"] = NUMERO
        // Costo por imagen (USD): obligatorio si el modelo edita imagenes.
        if (m.generatesImages && !noNegativo(m.imageCost)) f["imageCost"] = NUMERO
        if (!m.generatesImages && m.imageCost != null && !noNegativo(m.imageCost)) f["imageCost"] = NUMERO
        return f
    }

    fun validateAgent(a: AgentDraft, models: List<ModelInfo>): Map<String, String> {
        val f = linkedMapOf<String, String>()
        val porId = models.associateBy { it.id }
        val modelo = a.modelId?.let { porId[it] }
        if (modelo == null || !modelo.active) {
            f["modelId"] = "Elige un modelo activo"
        } else if (a.tools.isNotEmpty() && !modelo.supportsTools) {
            f["modelId"] = "Ese modelo no usa herramientas"
        }
        if (a.fallbackModelId != null) {
            val resp = porId[a.fallbackModelId]
            if (resp == null || !resp.active) f["fallbackModelId"] = "Elige un modelo activo"
            else if (a.fallbackModelId == a.modelId) f["fallbackModelId"] = "El respaldo debe ser otro modelo"
        }
        if (a.imageModelId != null) {
            val img = porId[a.imageModelId]
            if (img == null || !img.active || !img.generatesImages) f["imageModelId"] = "Elige un modelo activo que edite imágenes"
        }
        val t = a.temperature
        if (t == null || t < TEMPERATURE_MIN || t > TEMPERATURE_MAX) f["temperature"] = "Entre 0 y 1"
        val loops = a.maxToolLoops
        if (loops == null || loops < LOOPS_MIN || loops > LOOPS_MAX) f["maxToolLoops"] = "Entero entre 0 y 10"
        val ms = a.responseTimeoutMs
        if (ms == null || ms < TIMEOUT_MIN || ms > TIMEOUT_MAX) f["responseTimeoutMs"] = "Entre 5 y 120 segundos"
        a.tools.forEach { g ->
            if (g.maxAmount != null && g.maxAmount.signum() <= 0) f["tools.${g.pattern}"] = "Monto mayor a 0"
        }
        return f
    }

    /**
     * Las herramientas que pide la consola, contra el catalogo del codigo. El
     * `mode` lo dicta el codigo (no el cuerpo): un patron con comodin es de
     * escritura si cubre alguna de escritura. Las de lectura no llevan tope.
     */
    fun resolveTools(pedidas: List<Map<String, Any?>>, catalogo: List<ToolDefinition>): Pair<List<ToolGrantView>, Map<String, String>> {
        val f = linkedMapOf<String, String>()
        val vistos = mutableSetOf<String>()
        val grants = pedidas.mapNotNull { p ->
            val patron = (p["pattern"] as? String)?.trim().orEmpty()
            val cubiertas = catalogo.filter { cubre(patron, it.name) }
            when {
                patron.isEmpty() || cubiertas.isEmpty() -> { f["tools"] = "Herramienta desconocida: ${patron.ifEmpty { "(vacía)" }}"; null }
                !vistos.add(patron) -> { f["tools"] = "Herramienta repetida: $patron"; null }
                else -> {
                    val escribe = cubiertas.any { it.mode == ToolMode.WRITE }
                    val tope = p["maxAmount"]?.let { v ->
                        Parse.decimal(v) ?: run { f["tools.$patron"] = "Monto mayor a 0"; null }
                    }
                    ToolGrantView(
                        pattern = patron,
                        mode = if (escribe) "write" else "read",
                        // Leer nunca pide confirmacion; escribir, salvo que se apruebe solo.
                        autoApprove = if (escribe) Parse.bool(p["autoApprove"]) ?: false else true,
                        maxAmount = if (escribe) tope else null,
                    )
                }
            }
        }
        return grants to f
    }

    fun cubre(patron: String, nombre: String): Boolean =
        if (patron.endsWith(".*")) nombre.startsWith(patron.dropLast(1)) else patron == nombre

    /** `productos.search` → `productos`. */
    fun groupOf(nombre: String): String = nombre.substringBefore('.')

    private const val NUMERO = "Número mayor o igual a 0"

    private fun noNegativo(v: BigDecimal?): Boolean = v != null && v.signum() >= 0
}

/** Lectura tolerante de un cuerpo JSON ya convertido a mapa. */
object Parse {
    fun decimal(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> v.toString().toBigDecimalOrNull()
        is String -> v.trim().toBigDecimalOrNull()
        else -> null
    }

    /** Entero exacto: 2.5 no es un entero aunque se pueda truncar. */
    fun int(v: Any?): Int? = decimal(v)?.let { d ->
        runCatching { d.stripTrailingZeros().intValueExact() }.getOrNull()
    }

    fun long(v: Any?): Long? = decimal(v)?.let { d ->
        runCatching { d.stripTrailingZeros().longValueExact() }.getOrNull()
    }

    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

    fun text(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
