package com.erp_maya.agent.admin

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

// Todas las vistas llevan @JsonInclude(ALWAYS): por defecto Micronaut Serde
// omite listas vacias y nulos, y la consola espera `tools: []`, `usedBy: []`
// y `fallbackModelId: null` presentes.
//
// Contrato de /v1/admin/ai (lo consume stack-line-services para la consola de
// plataforma; ver API.md de stack-line-gestion-ui, seccion «IA»). Las fechas
// viajan como texto ISO-8601. Ninguna respuesta lleva secretos: solo el nombre
// de la variable (credentialRef) y si esta puesta (credentialSet).

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProviderView(
    val id: Long,
    val code: String,
    val name: String,
    val baseUrl: String,
    val authType: String,
    val credentialRef: String,
    val credentialSet: Boolean,
    val active: Boolean,
    /** Modelos del proveedor (activos o no). */
    val modelsCount: Int,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ProviderRef(val id: Long, val code: String, val name: String, val credentialSet: Boolean)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModelView(
    val id: Long,
    val providerId: Long,
    val provider: ProviderRef,
    val modelName: String,
    val contextWindow: Int,
    val inputCost1k: BigDecimal,
    val cachedCost1k: BigDecimal?,
    val outputCost1k: BigDecimal,
    val supportsTools: Boolean,
    val supportsCache: Boolean,
    val supportsVision: Boolean,
    val generatesImages: Boolean,
    val imageCost: BigDecimal,
    val active: Boolean,
    /** Codigos de los agentes que lo usan (principal, respaldo o imagenes). */
    val usedBy: List<String>,
    /** Empresas que lo eligieron como modelo propio (llega con la IA por empresa). */
    val companiesCount: Int,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ToolView(val name: String, val mode: String, val group: String, val description: String)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class Catalog(
    val providers: List<ProviderView>,
    val models: List<ModelView>,
    val tools: List<ToolView>,
    /** Codigos de proveedor que el codigo sabe llamar: solo estos se dan de alta. */
    val providerCodes: List<String>,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PromptRef(val id: Long, val version: Int, val createdBy: String?, val createdAt: String)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ToolGrantView(val pattern: String, val mode: String, val autoApprove: Boolean, val maxAmount: BigDecimal?)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AgentView(
    val id: Long,
    val code: String,
    val name: String,
    val kind: String,
    val description: String?,
    val modelId: Long,
    val fallbackModelId: Long?,
    val imageModelId: Long?,
    val temperature: BigDecimal,
    val maxToolLoops: Int,
    val responseTimeoutMs: Int,
    val active: Boolean,
    val archived: Boolean,
    val prompt: PromptRef,
    val tools: List<ToolGrantView>,
    /** Empresas con un canal asignado hoy a este agente. */
    val companiesCount: Int,
    /** Agentes de empresa (no archivados) de este tipo. */
    val tenantAgentsCount: Int,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PromptVersionView(
    val id: Long,
    val version: Int,
    val body: String,
    val createdBy: String?,
    val createdAt: String,
    val active: Boolean,
)

/** Identidad global de Tino: la version activa (o nula) y su historial. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class IdentityView(val active: PromptVersionView?, val versions: List<PromptVersionView>)

// ── Empresa ─────────────────────────────────────────────────────────────────

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PolicyView(
    val enabled: Boolean,
    val quoteLimit: BigDecimal?,
    val escalationUser: String?,
    val tone: String?,
    val locale: String,
)

/** Un canal de la empresa y el agente que lo atiende hoy (nulo = nadie). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ChannelView(
    /** Nulo = el canal del ERP aun no existe; se crea al asignarlo. */
    val id: Long?,
    val kind: String,
    val accountRef: String,
    val tenantAgentId: Long?,
    /** Nombre visible (los widgets web lo tienen; los demas, nulo). */
    val name: String? = null,
)

/**
 * Un numero de WhatsApp visto en Meta, antes de conectarlo. `status`:
 * `available` (libre), `connected` (ya es de esta empresa) o `in_use` (lo
 * atiende otra empresa).
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WhatsAppNumberView(
    val phoneNumberId: String,
    val displayPhoneNumber: String?,
    val verifiedName: String?,
    val qualityRating: String?,
    val codeVerificationStatus: String?,
    val status: String,
)

/** Un widget de chat web de la empresa (canal `web`). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebChannelView(
    val id: Long,
    val name: String,
    val publicKey: String,
    val allowedOrigins: List<String>,
    val title: String?,
    val greeting: String?,
    val color: String?,
    val active: Boolean,
    val tenantAgentId: Long?,
    val createdAt: String,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlaybookView(
    val version: Int,
    val updatedAt: String?,
    val updatedBy: String?,
    /** El JSON guardado tal cual; la consola lo completa con los valores por defecto. */
    val config: Map<String, Any?>,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TenantAgentView(
    val id: Long,
    val tenantId: Long,
    /** El tipo. */
    val agentId: Long,
    val agentCode: String,
    val agentName: String,
    val kind: String,
    val name: String,
    /** Lo propio de este agente; nulo = el del tipo. */
    val modelId: Long?,
    val fallbackModelId: Long?,
    val imageModelId: Long?,
    val temperature: BigDecimal?,
    /** Lo que de verdad usa (propio o heredado). */
    val effective: Effective,
    /** Nulo = todas las del tipo; si no, solo estas. */
    val tools: List<String>?,
    val instructions: PromptVersionView?,
    val playbook: PlaybookView?,
    val channelIds: List<Long>,
    val active: Boolean,
    val archived: Boolean,
    val createdAt: String,
) {
    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    data class Effective(
        val modelId: Long,
        val fallbackModelId: Long?,
        val imageModelId: Long?,
        val temperature: BigDecimal,
        val tools: List<ToolGrantView>,
    )
}

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TenantAiView(
    val tenantId: Long,
    val policy: PolicyView,
    val channels: List<ChannelView>,
    val agents: List<TenantAgentView>,
    val webChannels: List<WebChannelView> = emptyList(),
    /** URL del script del widget; nula si falta `AGENT_PUBLIC_URL` en el servicio. */
    val widgetScriptUrl: String? = null,
)

/** Error con `code` estable para la UI y `message` para la persona. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AdminError(val code: String, val message: String, val fields: Map<String, String>? = null)

class AdminException(
    val status: Int,
    val code: String,
    override val message: String,
    val fields: Map<String, String>? = null,
) : RuntimeException(message) {
    companion object {
        fun validation(fields: Map<String, String>) = AdminException(400, "VALIDATION", "Revisa los campos marcados", fields)
        fun notFound(what: String) = AdminException(404, "NOT_FOUND", what)
        fun conflict(code: String, message: String, fields: Map<String, String>? = null) = AdminException(409, code, message, fields)
    }
}
