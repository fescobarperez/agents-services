package com.erp_maya.agent.context.repository

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.context.domain.ToolGrant
import com.erp_maya.agent.context.domain.ToolMode

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.serde.ObjectMapper
import io.micronaut.transaction.annotation.ReadOnly
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Types

/**
 * Lectura de la configuracion que resuelve un turno.
 *
 * Se usa JdbcOperations y no un repositorio derivado porque esto es una sola
 * consulta de siete tablas con una proyeccion que no corresponde a ninguna
 * entidad. Escribirla a mano deja a la vista el criterio de vigencia y el
 * desempate por prioridad, que es justo lo que hay que poder auditar.
 */
@Singleton
open class ExecutionContextRepository(
    private val jdbc: JdbcOperations,
    private val json: ObjectMapper,
) {

    /**
     * Asignaciones vigentes del canal. Devuelve mas de una fila solo si el
     * mismo canal atiende a varias empresas; quien llama decide si eso es
     * ambiguedad o si ya venia con la empresa resuelta.
     */
    @ReadOnly
    open fun resolve(accountRef: String, tenantId: Long?): List<ExecutionContext> {
        // Las herramientas se cargan DESPUES de agotar este ResultSet: abrir un
        // segundo statement sobre la misma conexion mientras se itera el
        // primero es justo el tipo de detalle que funciona hasta que un dia no.
        val parciales = jdbc.prepareStatement(SQL) { stmt ->
            stmt.setObject(1, tenantId, Types.BIGINT)
            stmt.setObject(2, tenantId, Types.BIGINT)
            stmt.setString(3, accountRef)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<ExecutionContext>()
            while (rs.next()) filas += leer(rs)
            filas
        }
        if (parciales.isEmpty()) return parciales

        val porAgente = parciales.map { it.agent.id }.distinct().associateWith { herramientas(it) }
        return parciales.map { it.copy(tools = porAgente[it.agent.id].orEmpty()) }
    }

    @ReadOnly
    open fun herramientas(agentId: Long): List<ToolGrant> =
        jdbc.prepareStatement(SQL_TOOLS) { stmt ->
            stmt.setLong(1, agentId)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<ToolGrant>()
            while (rs.next()) {
                filas += ToolGrant(
                    pattern = rs.getString("tool_pattern"),
                    mode = ToolMode.valueOf(rs.getString("mode").uppercase()),
                    autoApprove = rs.getBoolean("auto_approve"),
                    maxAmount = rs.getBigDecimal("max_amount"),
                )
            }
            filas
        }

    private fun leer(rs: ResultSet) = ExecutionContext(
        tenantId = rs.getLong("tenant_id"),
        channel = ChannelRef(
            id = rs.getLong("channel_id"),
            kind = rs.getString("channel_kind"),
            accountRef = rs.getString("account_ref"),
            capabilities = capacidades(rs.getString("capabilities")),
        ),
        agent = AgentRef(
            id = rs.getLong("agent_id"),
            code = rs.getString("agent_code"),
            temperature = rs.getBigDecimal("temperature"),
            maxToolLoops = rs.getInt("max_tool_loops"),
            responseTimeoutMs = rs.getInt("response_timeout_ms"),
        ),
        model = ModelRef(
            id = rs.getLong("model_id"),
            providerCode = rs.getString("provider_code"),
            modelName = rs.getString("model_name"),
            contextWindow = rs.getInt("context_window"),
            supportsTools = rs.getBoolean("supports_tools"),
            supportsCache = rs.getBoolean("supports_cache"),
        ),
        fallbackModel = rs.getObject("fallback_model_id")?.let {
            ModelRef(
                id = rs.getLong("fallback_model_id"),
                providerCode = rs.getString("fallback_provider_code"),
                modelName = rs.getString("fallback_model_name"),
                contextWindow = rs.getInt("fallback_context_window"),
                supportsTools = rs.getBoolean("fallback_supports_tools"),
                supportsCache = rs.getBoolean("fallback_supports_cache"),
            )
        },
        prompt = PromptRef(
            id = rs.getLong("prompt_version_id"),
            version = rs.getInt("prompt_version"),
            body = rs.getString("prompt_body"),
        ),
        tools = emptyList(),
        policy = TenantPolicy(
            tenantId = rs.getLong("tenant_id"),
            // Sin fila en agent_tenant_policies el bot queda apagado: dar de
            // alta una empresa es un acto explicito, no una omision.
            enabled = rs.getObject("policy_enabled")?.let { rs.getBoolean("policy_enabled") } ?: false,
            quoteLimit = rs.getBigDecimal("quote_limit"),
            escalationUser = rs.getString("escalation_user"),
            tone = rs.getString("tone"),
            locale = rs.getString("locale") ?: "es-GT",
        ),
    )

    private fun capacidades(raw: String?): Capabilities =
        if (raw.isNullOrBlank()) Capabilities()
        // Un jsonb corrupto o con campos que no conocemos no puede tumbar el
        // turno: se cae a las capacidades minimas, que es lo que todo canal sabe.
        else runCatching { json.readValue(raw, Capabilities::class.java) }.getOrNull() ?: Capabilities()

    private companion object {
        /**
         * La vigencia usa `coalesce(valid_to, 'infinity')` para que una
         * asignacion sin fecha de fin siga viva sin necesitar un OR.
         * El parametro de empresa se compara dos veces: nulo significa "la que
         * sea", y asi la misma consulta sirve para WhatsApp —que no la manda—
         * y para el widget del ERP, que si.
         */
        const val SQL = """
            SELECT ca.tenant_id,
                   c.id            AS channel_id,
                   c.kind          AS channel_kind,
                   c.account_ref,
                   c.capabilities::text AS capabilities,
                   a.id            AS agent_id,
                   a.code          AS agent_code,
                   a.temperature, a.max_tool_loops, a.response_timeout_ms,
                   m.id            AS model_id,
                   p.code          AS provider_code,
                   m.model_name, m.context_window, m.supports_tools, m.supports_cache,
                   fm.id           AS fallback_model_id,
                   fp.code         AS fallback_provider_code,
                   fm.model_name   AS fallback_model_name,
                   fm.context_window AS fallback_context_window,
                   fm.supports_tools AS fallback_supports_tools,
                   fm.supports_cache AS fallback_supports_cache,
                   pv.id           AS prompt_version_id,
                   pv.version      AS prompt_version,
                   pv.body         AS prompt_body,
                   pol.is_enabled  AS policy_enabled,
                   pol.quote_limit, pol.escalation_user, pol.tone, pol.locale
            FROM channels c
            JOIN channel_agents ca
                   ON ca.channel_id = c.id
                  AND now() >= ca.valid_from
                  AND now() < coalesce(ca.valid_to, 'infinity'::timestamptz)
                  AND (?::bigint IS NULL OR ca.tenant_id = ?::bigint)
            JOIN ai_agents a          ON a.id = ca.agent_id AND a.is_active
            JOIN ai_models m          ON m.id = a.model_id AND m.is_active
            JOIN ai_providers p       ON p.id = m.provider_id AND p.is_active
            LEFT JOIN ai_models fm    ON fm.id = a.fallback_model_id
            LEFT JOIN ai_providers fp ON fp.id = fm.provider_id
            JOIN ai_prompt_versions pv ON pv.id = a.prompt_version_id
            LEFT JOIN agent_tenant_policies pol ON pol.tenant_id = ca.tenant_id
            WHERE c.account_ref = ? AND c.is_active
            ORDER BY ca.priority DESC, ca.valid_from DESC
        """

        const val SQL_TOOLS = """
            SELECT tool_pattern, mode, auto_approve, max_amount
            FROM ai_agent_tools WHERE agent_id = ? ORDER BY tool_pattern
        """
    }
}
