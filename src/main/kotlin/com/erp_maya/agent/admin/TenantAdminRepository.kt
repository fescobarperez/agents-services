package com.erp_maya.agent.admin

import io.micronaut.data.jdbc.runtime.JdbcOperations
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

data class PolicyRow(val enabled: Boolean, val quoteLimit: BigDecimal?, val escalationUser: String?, val tone: String?, val locale: String)

data class TenantAgentRow(
    val id: Long,
    val tenantId: Long,
    val agentId: Long,
    val agentCode: String,
    val agentName: String,
    val kind: String,
    val name: String,
    val modelId: Long?,
    val fallbackModelId: Long?,
    val imageModelId: Long?,
    val temperature: BigDecimal?,
    val toolsOverride: Boolean,
    val instructionsVersionId: Long?,
    val active: Boolean,
    val archived: Boolean,
    val createdAt: Instant,
    // Del tipo, para lo heredado.
    val typeModelId: Long,
    val typeFallbackModelId: Long?,
    val typeImageModelId: Long?,
    val typeTemperature: BigDecimal,
    val typeMaxToolLoops: Int,
    val typeResponseTimeoutMs: Int,
)

data class ChannelRow(val id: Long, val kind: String, val accountRef: String, val tenantAgentId: Long?, val name: String? = null)

data class ChannelOwnerRow(val id: Long, val kind: String, val active: Boolean, val tenantId: Long?, val servedBy: List<Long>)

data class WebChannelRow(
    val id: Long,
    val name: String,
    val publicKey: String,
    val origins: List<String>,
    val title: String?,
    val greeting: String?,
    val color: String?,
    val active: Boolean,
    val tenantAgentId: Long?,
    val createdAt: Instant,
)

data class PlaybookRow(val version: Int, val config: String, val createdBy: String?, val createdAt: Instant)

/** SQL de la configuracion de IA de una empresa. Sin transacciones propias: las abre [TenantAdminService]. */
@Singleton
open class TenantAdminRepository(private val jdbc: JdbcOperations) {

    // ── politica de la empresa ──────────────────────────────────────────────

    fun policy(tenantId: Long): PolicyRow? =
        lista("SELECT is_enabled, quote_limit, escalation_user, tone, locale FROM agent_tenant_policies WHERE tenant_id = ?", { it.setLong(1, tenantId) }) { rs ->
            PolicyRow(rs.getBoolean("is_enabled"), rs.getBigDecimal("quote_limit"), rs.getString("escalation_user"), rs.getString("tone"), rs.getString("locale"))
        }.firstOrNull()

    fun upsertPolicy(tenantId: Long, p: PolicyRow) {
        jdbc.prepareStatement(
            """
            INSERT INTO agent_tenant_policies (tenant_id, is_enabled, quote_limit, escalation_user, tone, locale)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id) DO UPDATE SET is_enabled = EXCLUDED.is_enabled, quote_limit = EXCLUDED.quote_limit,
                escalation_user = EXCLUDED.escalation_user, tone = EXCLUDED.tone, locale = EXCLUDED.locale
            """.trimIndent(),
        ) { st ->
            st.setLong(1, tenantId); st.setBoolean(2, p.enabled)
            if (p.quoteLimit == null) st.setNull(3, Types.NUMERIC) else st.setBigDecimal(3, p.quoteLimit)
            st.setString(4, p.escalationUser); st.setString(5, p.tone); st.setString(6, p.locale)
            st.executeUpdate()
        }
    }

    // ── canales ─────────────────────────────────────────────────────────────

    /**
     * Los canales de la empresa: los que alguna vez le fueron asignados y el
     * del ERP (`erp:{tenant}`) si existe, con el agente que los atiende hoy.
     */
    fun channels(tenantId: Long): List<ChannelRow> =
        lista(
            """
            SELECT c.id, c.kind, c.account_ref, coalesce(w.name, c.label) AS web_name,
                   (SELECT ca.tenant_agent_id FROM channel_agents ca
                     WHERE ca.channel_id = c.id AND ca.tenant_id = ?
                       AND now() >= ca.valid_from AND now() < coalesce(ca.valid_to, 'infinity'::timestamptz)
                     ORDER BY ca.priority DESC, ca.valid_from DESC LIMIT 1) AS tenant_agent_id
            FROM channels c
            LEFT JOIN web_widgets w ON w.channel_id = c.id
            WHERE c.is_active
              AND (c.account_ref = ? OR w.tenant_id = ? OR c.tenant_id = ?
                   OR EXISTS (SELECT 1 FROM channel_agents x WHERE x.channel_id = c.id AND x.tenant_id = ?))
            ORDER BY c.kind, c.account_ref
            """.trimIndent(),
            { st -> st.setLong(1, tenantId); st.setString(2, erpRef(tenantId)); st.setLong(3, tenantId); st.setLong(4, tenantId); st.setLong(5, tenantId) },
        ) { rs ->
            ChannelRow(rs.getLong("id"), rs.getString("kind"), rs.getString("account_ref"),
                rs.getObject("tenant_agent_id")?.let { rs.getLong("tenant_agent_id") }, rs.getString("web_name"))
        }

    // ── numeros de WhatsApp ─────────────────────────────────────────────────

    /** Un canal por su cuenta (`wa:{id}`), con las empresas que lo atienden hoy. */
    fun channelByRef(ref: String): ChannelOwnerRow? =
        lista(
            """
            SELECT c.id, c.kind, c.is_active, c.tenant_id,
                   ARRAY(SELECT DISTINCT ca.tenant_id FROM channel_agents ca
                         WHERE ca.channel_id = c.id AND now() >= ca.valid_from
                           AND now() < coalesce(ca.valid_to, 'infinity'::timestamptz)) AS atendido_por
            FROM channels c WHERE c.account_ref = ?
            """.trimIndent(),
            { it.setString(1, ref) },
        ) { rs ->
            ChannelOwnerRow(
                id = rs.getLong("id"),
                kind = rs.getString("kind"),
                active = rs.getBoolean("is_active"),
                tenantId = largoONulo(rs, "tenant_id"),
                servedBy = (rs.getArray("atendido_por")?.array as? Array<*>)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty(),
            )
        }.firstOrNull()

    fun insertWhatsAppChannel(tenantId: Long, ref: String, label: String?, capabilitiesJson: String): Long =
        lista(
            "INSERT INTO channels (kind, account_ref, capabilities, tenant_id, label) VALUES ('whatsapp', ?, ?::jsonb, ?, ?) RETURNING id",
            { st -> st.setString(1, ref); st.setString(2, capabilitiesJson); st.setLong(3, tenantId); st.setString(4, label) },
        ) { it.getLong(1) }.first()

    /** El canal pasa a ser de la empresa (y se reactiva si estaba desconectado). */
    fun claimChannel(channelId: Long, tenantId: Long, label: String?) {
        jdbc.prepareStatement("UPDATE channels SET tenant_id = ?, label = coalesce(?, label), is_active = TRUE WHERE id = ?") { st ->
            st.setLong(1, tenantId); st.setString(2, label); st.setLong(3, channelId); st.executeUpdate()
        }
    }

    // ── widgets web ─────────────────────────────────────────────────────────

    /** Los widgets de la empresa, activos o no, con el agente que los atiende hoy. */
    fun webChannels(tenantId: Long): List<WebChannelRow> =
        lista(
            """
            SELECT w.channel_id, w.name, w.public_key, ARRAY(SELECT jsonb_array_elements_text(w.allowed_origins)) AS origins,
                   w.title, w.greeting, w.color, c.is_active, w.created_at,
                   (SELECT ca.tenant_agent_id FROM channel_agents ca
                     WHERE ca.channel_id = c.id AND ca.tenant_id = w.tenant_id
                       AND now() >= ca.valid_from AND now() < coalesce(ca.valid_to, 'infinity'::timestamptz)
                     ORDER BY ca.priority DESC, ca.valid_from DESC LIMIT 1) AS tenant_agent_id
            FROM web_widgets w JOIN channels c ON c.id = w.channel_id
            WHERE w.tenant_id = ?
            ORDER BY w.created_at
            """.trimIndent(),
            { it.setLong(1, tenantId) },
        ) { rs ->
            WebChannelRow(
                id = rs.getLong("channel_id"),
                name = rs.getString("name"),
                publicKey = rs.getString("public_key"),
                origins = (rs.getArray("origins")?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                title = rs.getString("title"),
                greeting = rs.getString("greeting"),
                color = rs.getString("color"),
                active = rs.getBoolean("is_active"),
                tenantAgentId = largoONulo(rs, "tenant_agent_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

    /** Crea el canal `web:{llave}` y su widget. Devuelve el id del canal. */
    fun insertWebChannel(tenantId: Long, llave: String, capabilitiesJson: String, d: WebChannelRules.Draft, originsJson: String): Long {
        val id = lista(
            "INSERT INTO channels (kind, account_ref, capabilities) VALUES ('web', ?, ?::jsonb) RETURNING id",
            { st -> st.setString(1, "web:$llave"); st.setString(2, capabilitiesJson) },
        ) { it.getLong(1) }.first()
        jdbc.prepareStatement(
            """
            INSERT INTO web_widgets (channel_id, tenant_id, name, public_key, allowed_origins, title, greeting, color)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
        ) { st ->
            st.setLong(1, id); st.setLong(2, tenantId); st.setString(3, d.name); st.setString(4, llave)
            st.setString(5, originsJson); st.setString(6, d.title); st.setString(7, d.greeting); st.setString(8, d.color)
            st.executeUpdate()
        }
        return id
    }

    fun updateWebChannel(channelId: Long, d: WebChannelRules.Draft, originsJson: String) {
        jdbc.prepareStatement(
            """
            UPDATE web_widgets SET name = ?, allowed_origins = ?::jsonb, title = ?, greeting = ?, color = ?, updated_at = now()
            WHERE channel_id = ?
            """.trimIndent(),
        ) { st ->
            st.setString(1, d.name); st.setString(2, originsJson); st.setString(3, d.title); st.setString(4, d.greeting)
            st.setString(5, d.color); st.setLong(6, channelId)
            st.executeUpdate()
        }
    }

    fun setChannelActive(channelId: Long, active: Boolean) {
        jdbc.prepareStatement("UPDATE channels SET is_active = ? WHERE id = ?") { st ->
            st.setBoolean(1, active); st.setLong(2, channelId); st.executeUpdate()
        }
    }

    /** Crea el canal del asistente del ERP de la empresa (lo usa el widget con `erp:{tenant}`). */
    fun createErpChannel(tenantId: Long): Long =
        lista(
            "INSERT INTO channels (kind, account_ref, capabilities) VALUES ('erp', ?, ?::jsonb) RETURNING id",
            { st -> st.setString(1, erpRef(tenantId)); st.setString(2, """{"buttons":0,"markdown":true,"maxChars":4000,"streaming":false}""") },
        ) { it.getLong(1) }.first()

    /** Termina hoy las asignaciones vigentes del canal para la empresa. */
    fun endAssignments(channelId: Long, tenantId: Long) {
        jdbc.prepareStatement(
            """
            UPDATE channel_agents SET valid_to = now()
            WHERE channel_id = ? AND tenant_id = ? AND now() < coalesce(valid_to, 'infinity'::timestamptz)
            """.trimIndent(),
        ) { st -> st.setLong(1, channelId); st.setLong(2, tenantId); st.executeUpdate() }
    }

    fun assign(channelId: Long, tenantId: Long, agentId: Long, tenantAgentId: Long) {
        jdbc.prepareStatement(
            "INSERT INTO channel_agents (channel_id, tenant_id, agent_id, tenant_agent_id, priority) VALUES (?, ?, ?, ?, 10)",
        ) { st -> st.setLong(1, channelId); st.setLong(2, tenantId); st.setLong(3, agentId); st.setLong(4, tenantAgentId); st.executeUpdate() }
    }

    // ── agentes de la empresa ───────────────────────────────────────────────

    fun agents(tenantId: Long): List<TenantAgentRow> =
        lista("$SELECT_AGENTS WHERE ta.tenant_id = ? ORDER BY ta.archived_at NULLS FIRST, ta.created_at", { it.setLong(1, tenantId) }) { fila(it) }

    fun agent(tenantId: Long, id: Long): TenantAgentRow? =
        lista("$SELECT_AGENTS WHERE ta.tenant_id = ? AND ta.id = ?", { it.setLong(1, tenantId); it.setLong(2, id) }) { fila(it) }.firstOrNull()

    fun insertAgent(tenantId: Long, agentId: Long, name: String): Long =
        lista(
            "INSERT INTO tenant_agents (tenant_id, agent_id, name) VALUES (?, ?, ?) RETURNING id",
            { st -> st.setLong(1, tenantId); st.setLong(2, agentId); st.setString(3, name) },
        ) { it.getLong(1) }.first()

    fun updateAgent(id: Long, cambios: Map<String, Any?>) {
        if (cambios.isEmpty()) return
        require(COLUMNAS.containsAll(cambios.keys)) { "Columna no permitida: ${cambios.keys - COLUMNAS}" }
        val columnas = cambios.keys.toList()
        jdbc.prepareStatement("UPDATE tenant_agents SET ${columnas.joinToString { "$it = ?" }}, updated_at = now() WHERE id = ?") { st ->
            columnas.forEachIndexed { i, c ->
                when (val v = cambios[c]) {
                    null -> st.setNull(i + 1, if (c.endsWith("_id")) Types.BIGINT else if (c == "temperature") Types.NUMERIC else Types.VARCHAR)
                    is BigDecimal -> st.setBigDecimal(i + 1, v)
                    is Boolean -> st.setBoolean(i + 1, v)
                    is Long -> st.setLong(i + 1, v)
                    is String -> st.setString(i + 1, v)
                    else -> error("Tipo no soportado para $c")
                }
            }
            st.setLong(columnas.size + 1, id)
            st.executeUpdate()
        }
    }

    fun archive(id: Long) {
        jdbc.prepareStatement("UPDATE tenant_agents SET archived_at = now(), is_active = FALSE, updated_at = now() WHERE id = ?") {
            it.setLong(1, id); it.executeUpdate()
        }
    }

    fun tools(tenantAgentId: Long): List<String> =
        lista("SELECT tool_pattern FROM tenant_agent_tools WHERE tenant_agent_id = ? ORDER BY tool_pattern", { it.setLong(1, tenantAgentId) }) { it.getString(1) }

    fun replaceTools(tenantAgentId: Long, patrones: List<String>) {
        jdbc.prepareStatement("DELETE FROM tenant_agent_tools WHERE tenant_agent_id = ?") { it.setLong(1, tenantAgentId); it.executeUpdate() }
        patrones.forEach { p ->
            jdbc.prepareStatement("INSERT INTO tenant_agent_tools (tenant_agent_id, tool_pattern) VALUES (?, ?)") { st ->
                st.setLong(1, tenantAgentId); st.setString(2, p); st.executeUpdate()
            }
        }
    }

    // ── instrucciones y playbook ────────────────────────────────────────────

    fun instructions(tenantAgentId: Long): List<PromptRow> =
        lista(
            "SELECT id, version, body, created_by, created_at FROM tenant_agent_instructions WHERE tenant_agent_id = ? ORDER BY version DESC",
            { it.setLong(1, tenantAgentId) },
        ) { PromptRow(it.getLong("id"), it.getInt("version"), it.getString("body"), it.getString("created_by"), it.getTimestamp("created_at").toInstant()) }

    fun insertInstructions(tenantAgentId: Long, body: String, createdBy: String?): Long =
        lista(
            """
            INSERT INTO tenant_agent_instructions (tenant_agent_id, version, body, created_by)
            VALUES (?, (SELECT COALESCE(MAX(version), 0) + 1 FROM tenant_agent_instructions WHERE tenant_agent_id = ?), ?, ?)
            RETURNING id
            """.trimIndent(),
            { st -> st.setLong(1, tenantAgentId); st.setLong(2, tenantAgentId); st.setString(3, body); st.setString(4, createdBy) },
        ) { it.getLong(1) }.first()

    /** El playbook vigente del agente de empresa (solo el suyo, sin heredar). */
    fun playbook(tenantAgentId: Long): PlaybookRow? =
        lista(
            """
            SELECT version, config::text AS config, created_by, created_at FROM agent_playbooks
            WHERE tenant_agent_id = ? AND is_active ORDER BY version DESC LIMIT 1
            """.trimIndent(),
            { it.setLong(1, tenantAgentId) },
        ) { PlaybookRow(it.getInt("version"), it.getString("config"), it.getString("created_by"), it.getTimestamp("created_at").toInstant()) }.firstOrNull()

    /** El que usaria si no tuviera uno propio: el general del tipo (tenant_id nulo). */
    fun defaultPlaybook(agentCode: String): String? =
        lista(
            "SELECT config::text FROM agent_playbooks WHERE agent_code = ? AND tenant_id IS NULL AND is_active ORDER BY version DESC LIMIT 1",
            { it.setString(1, agentCode) },
        ) { it.getString(1) }.firstOrNull()

    /** Version nueva activa; las anteriores del mismo agente quedan inactivas (historial). */
    fun savePlaybook(tenantAgentId: Long, tenantId: Long, agentCode: String, configJson: String, createdBy: String?) {
        jdbc.prepareStatement("UPDATE agent_playbooks SET is_active = FALSE WHERE tenant_agent_id = ? AND is_active") {
            it.setLong(1, tenantAgentId); it.executeUpdate()
        }
        jdbc.prepareStatement(
            """
            INSERT INTO agent_playbooks (agent_code, tenant_id, tenant_agent_id, version, is_active, config, created_by)
            VALUES (?, ?, ?, (SELECT COALESCE(MAX(version), 0) + 1 FROM agent_playbooks WHERE tenant_agent_id = ?), TRUE, ?::jsonb, ?)
            """.trimIndent(),
        ) { st ->
            st.setString(1, agentCode); st.setLong(2, tenantId); st.setLong(3, tenantAgentId); st.setLong(4, tenantAgentId)
            st.setString(5, configJson); st.setString(6, createdBy)
            st.executeUpdate()
        }
    }

    // ── utilidades ──────────────────────────────────────────────────────────

    private fun <T : Any> lista(sql: String, bind: (PreparedStatement) -> Unit = {}, fila: (ResultSet) -> T): List<T> =
        jdbc.prepareStatement(sql) { st ->
            bind(st)
            val rs = st.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += fila(rs)
            out
        }

    private fun largoONulo(rs: ResultSet, col: String): Long? = rs.getObject(col)?.let { rs.getLong(col) }

    private fun fila(rs: ResultSet) = TenantAgentRow(
        id = rs.getLong("id"),
        tenantId = rs.getLong("tenant_id"),
        agentId = rs.getLong("agent_id"),
        agentCode = rs.getString("agent_code"),
        agentName = rs.getString("agent_name"),
        kind = rs.getString("kind"),
        name = rs.getString("name"),
        modelId = largoONulo(rs, "model_id"),
        fallbackModelId = largoONulo(rs, "fallback_model_id"),
        imageModelId = largoONulo(rs, "image_model_id"),
        temperature = rs.getBigDecimal("temperature"),
        toolsOverride = rs.getBoolean("tools_override"),
        instructionsVersionId = largoONulo(rs, "instructions_version_id"),
        active = rs.getBoolean("is_active"),
        archived = rs.getTimestamp("archived_at") != null,
        createdAt = rs.getTimestamp("created_at").toInstant(),
        typeModelId = rs.getLong("type_model_id"),
        typeFallbackModelId = largoONulo(rs, "type_fallback_model_id"),
        typeImageModelId = largoONulo(rs, "type_image_model_id"),
        typeTemperature = rs.getBigDecimal("type_temperature"),
        typeMaxToolLoops = rs.getInt("type_max_tool_loops"),
        typeResponseTimeoutMs = rs.getInt("type_response_timeout_ms"),
    )

    companion object {
        fun erpRef(tenantId: Long) = "erp:$tenantId"
        fun waRef(phoneNumberId: String) = "wa:$phoneNumberId"

        val COLUMNAS = setOf(
            "name", "model_id", "fallback_model_id", "image_model_id", "temperature", "tools_override",
            "instructions_version_id", "is_active",
        )

        private const val SELECT_AGENTS = """
            SELECT ta.id, ta.tenant_id, ta.agent_id, a.code AS agent_code, a.name AS agent_name, a.kind, ta.name,
                   ta.model_id, ta.fallback_model_id, ta.image_model_id, ta.temperature, ta.tools_override,
                   ta.instructions_version_id, ta.is_active, ta.archived_at, ta.created_at,
                   a.model_id AS type_model_id, a.fallback_model_id AS type_fallback_model_id,
                   a.image_model_id AS type_image_model_id, a.temperature AS type_temperature,
                   a.max_tool_loops AS type_max_tool_loops, a.response_timeout_ms AS type_response_timeout_ms
            FROM tenant_agents ta
            JOIN ai_agents a ON a.id = ta.agent_id"""
    }
}
