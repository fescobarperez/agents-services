package com.erp_maya.agent.context.domain

import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Todo lo que un turno necesita saber para ejecutarse, resuelto una sola vez al
 * inicio y sin volver a tocar la base.
 *
 * Deliberadamente NO contiene URLs ni credenciales de ningun servicio: el
 * proveedor del modelo resuelve las suyas en su propia capa, y las del ERP
 * viven en la configuracion. Aqui solo viaja la identidad de lo que hay que
 * usar, no como llegar a ello.
 */
@Serdeable
data class ExecutionContext(
    val tenantId: Long,
    val channel: ChannelRef,
    val agent: AgentRef,
    val model: ModelRef,
    val fallbackModel: ModelRef?,
    val prompt: PromptRef,
    val tools: List<ToolGrant>,
    val policy: TenantPolicy,
    /** Modelo que edita imagenes (renders). Nulo = el agente no genera renders. */
    val imageModel: ModelRef? = null,
    /**
     * El agente de la empresa que atiende el canal (tenant_agents). Nulo solo
     * en asignaciones antiguas sin agente de empresa: entonces todo sale del tipo.
     */
    val tenantAgent: TenantAgentRef? = null,
    /** Identidad global de Tino (ai_identity). Nula mientras no se escriba. */
    val identity: String? = null,
) {
    /**
     * Permiso vigente para una herramienta, o null si el agente no la tiene.
     * El tipo pone el techo (y el modo, auto-aprobacion y tope); el agente de
     * la empresa solo puede quitar herramientas, nunca agregar.
     */
    fun toolFor(name: String): ToolGrant? {
        val permitidas = tenantAgent?.allowedTools
        if (permitidas != null && permitidas.none { cubre(it, name) }) return null
        return tools.firstOrNull { it.matches(name) }
    }

    private fun cubre(patron: String, nombre: String): Boolean =
        if (patron.endsWith(".*")) nombre.startsWith(patron.dropLast(1)) else patron == nombre

    /**
     * Tope efectivo de una escritura: el menor entre el limite de la empresa y
     * el de la herramienta. Si ninguno esta definido, no hay tope y quien
     * llame debe tratarlo como escalamiento, no como via libre.
     */
    fun limitFor(tool: ToolGrant): BigDecimal? =
        listOfNotNull(policy.quoteLimit, tool.maxAmount).minOrNull()
}

@Serdeable
data class ChannelRef(
    val id: Long,
    val kind: String,
    val accountRef: String,
    val capabilities: Capabilities,
)

/** Lo que el canal sabe pintar. El agente adapta la respuesta a esto. */
@Serdeable
data class Capabilities(
    val buttons: Int = 0,
    val markdown: Boolean = false,
    val maxChars: Int = 4096,
    val streaming: Boolean = false,
)

@Serdeable
data class AgentRef(
    val id: Long,
    val code: String,
    val temperature: BigDecimal,
    val maxToolLoops: Int,
    val responseTimeoutMs: Int,
)

/**
 * Identidad del modelo. `providerCode` es la llave con la que el router escoge
 * implementacion; la base_url y el secreto los resuelve esa capa.
 */
@Serdeable
data class ModelRef(
    val id: Long,
    val providerCode: String,
    val modelName: String,
    val contextWindow: Int,
    val supportsTools: Boolean,
    val supportsCache: Boolean,
    /** Acepta imagenes de entrada (lectura de fotos). */
    val supportsVision: Boolean = false,
)

@Serdeable
data class PromptRef(val id: Long, val version: Int, val body: String)

/**
 * El agente de una empresa: instancia de un tipo con su nombre, sus
 * instrucciones y, si las restringe, las herramientas que conserva.
 */
@Serdeable
data class TenantAgentRef(
    val id: Long,
    val name: String,
    /** Instrucciones vigentes de la empresa para este agente; nulo = ninguna. */
    val instructions: String? = null,
    val instructionsVersionId: Long? = null,
    /** Nulo = todas las del tipo; si no, solo estas (dentro del tipo). */
    val allowedTools: List<String>? = null,
)

enum class ToolMode { READ, WRITE }

@Serdeable
data class ToolGrant(
    val pattern: String,
    val mode: ToolMode,
    val autoApprove: Boolean,
    val maxAmount: BigDecimal?,
) {
    /** El patron admite un comodin final: `cotizaciones.*` cubre toda la familia. */
    fun matches(name: String): Boolean =
        if (pattern.endsWith(".*")) name.startsWith(pattern.dropLast(1)) else pattern == name
}

@Serdeable
data class TenantPolicy(
    val tenantId: Long,
    val enabled: Boolean,
    val quoteLimit: BigDecimal?,
    val escalationUser: String?,
    val tone: String?,
    val locale: String,
)
