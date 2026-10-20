package com.erp_maya.agent.admin

import io.micronaut.data.jdbc.runtime.JdbcOperations
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** Filas tal como estan en la base; la vista de la consola la arma [AdminService]. */
data class ProviderRow(
    val id: Long,
    val code: String,
    val name: String?,
    val baseUrl: String,
    val authType: String,
    val credentialRef: String,
    val active: Boolean,
)

data class ModelRow(
    val id: Long,
    val providerId: Long,
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
)

data class AgentRow(
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
    val promptId: Long,
    val promptVersion: Int,
    val promptCreatedBy: String?,
    val promptCreatedAt: java.time.Instant,
)

data class PromptRow(val id: Long, val version: Int, val body: String, val createdBy: String?, val createdAt: java.time.Instant)

/**
 * SQL de la API de administracion. Las escrituras corren dentro de la
 * transaccion de [AdminService]; aqui no se abren transacciones.
 */
@Singleton
open class AdminRepository(private val jdbc: JdbcOperations) {

    fun providers(): List<ProviderRow> =
        lista("SELECT id, code, name, base_url, auth_type, credential_ref, is_active FROM ai_providers ORDER BY code") { rs ->
            ProviderRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("auth_type"), rs.getString("credential_ref"), rs.getBoolean("is_active"),
            )
        }

    fun insertProvider(code: String, name: String, baseUrl: String, authType: String, credentialRef: String): Long =
        lista(
            "INSERT INTO ai_providers (code, name, base_url, auth_type, credential_ref, is_active) VALUES (?, ?, ?, ?, ?, TRUE) RETURNING id",
            { st -> st.setString(1, code); st.setString(2, name); st.setString(3, baseUrl); st.setString(4, authType); st.setString(5, credentialRef) },
        ) { it.getLong(1) }.first()

    fun updateProvider(id: Long, cambios: Map<String, Any?>) = actualizar("ai_providers", id, cambios, PROVIDER_COLUMNS)

    // ── identidad de Tino ───────────────────────────────────────────────────

    fun identityPromptId(): Long? =
        lista("SELECT prompt_version_id FROM ai_identity WHERE id = 1") { rs ->
            listOfNotNull(rs.getObject("prompt_version_id")?.let { rs.getLong("prompt_version_id") })
        }.flatten().firstOrNull()

    fun setIdentity(promptVersionId: Long) {
        jdbc.prepareStatement("UPDATE ai_identity SET prompt_version_id = ?, updated_at = now() WHERE id = 1") {
            it.setLong(1, promptVersionId); it.executeUpdate()
        }
    }

    // ── tipos de agente ─────────────────────────────────────────────────────

    fun agentCodeTaken(code: String): Boolean =
        lista("SELECT 1 FROM ai_agents WHERE lower(code) = lower(?)", { it.setString(1, code) }) { true }.isNotEmpty()

    /** El prompt v1 del tipo y el tipo, en ese orden (ai_agents exige prompt). */
    fun insertAgentType(
        code: String, name: String, kind: String, description: String?, modelId: Long, fallbackModelId: Long?,
        imageModelId: Long?, temperature: BigDecimal, maxToolLoops: Int, responseTimeoutMs: Int,
        promptBody: String, createdBy: String?,
    ): Long {
        val promptId = insertPrompt(code, promptBody, createdBy)
        return lista(
            """
            INSERT INTO ai_agents (code, name, kind, description, model_id, fallback_model_id, image_model_id,
                                   prompt_version_id, temperature, max_tool_loops, response_timeout_ms, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE) RETURNING id
            """.trimIndent(),
            { st ->
                st.setString(1, code); st.setString(2, name); st.setString(3, kind); st.setString(4, description)
                st.setLong(5, modelId); st.setObject(6, fallbackModelId, Types.BIGINT); st.setObject(7, imageModelId, Types.BIGINT)
                st.setLong(8, promptId); st.setBigDecimal(9, temperature); st.setInt(10, maxToolLoops); st.setInt(11, responseTimeoutMs)
            },
        ) { it.getLong(1) }.first()
    }

    fun setArchived(tabla: String, id: Long, archivado: Boolean) {
        require(tabla == "ai_agents" || tabla == "tenant_agents")
        jdbc.prepareStatement("UPDATE $tabla SET archived_at = ${if (archivado) "now()" else "NULL"} WHERE id = ?") {
            it.setLong(1, id); it.executeUpdate()
        }
    }

    /** Agentes de empresa no archivados por tipo. */
    fun tenantAgentsByType(): Map<Long, Int> =
        lista("SELECT agent_id, COUNT(*) AS n FROM tenant_agents WHERE archived_at IS NULL GROUP BY agent_id") {
            it.getLong("agent_id") to it.getInt("n")
        }.toMap()

    fun models(): List<ModelRow> = lista("$SELECT_MODELS ORDER BY m.model_name") { modelo(it) }

    fun model(id: Long): ModelRow? = lista("$SELECT_MODELS WHERE m.id = ?", { it.setLong(1, id) }) { modelo(it) }.firstOrNull()

    fun modelNameTaken(providerId: Long, modelName: String): Boolean =
        lista("SELECT 1 FROM ai_models WHERE provider_id = ? AND lower(model_name) = lower(?)", {
            it.setLong(1, providerId); it.setString(2, modelName)
        }) { true }.isNotEmpty()

    fun insertModel(providerId: Long, modelName: String, contextWindow: Int, input: BigDecimal, cached: BigDecimal?,
                    output: BigDecimal, tools: Boolean, cache: Boolean, vision: Boolean, images: Boolean, imageCost: BigDecimal): Long =
        lista(
            """
            INSERT INTO ai_models (provider_id, model_name, context_window, input_cost_1k, cached_cost_1k, output_cost_1k,
                                   supports_tools, supports_cache, supports_vision, generates_images, image_cost, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE) RETURNING id
            """.trimIndent(),
            { st ->
                st.setLong(1, providerId); st.setString(2, modelName); st.setInt(3, contextWindow)
                st.setBigDecimal(4, input); decimalONulo(st, 5, cached); st.setBigDecimal(6, output)
                st.setBoolean(7, tools); st.setBoolean(8, cache); st.setBoolean(9, vision); st.setBoolean(10, images)
                st.setBigDecimal(11, imageCost)
            },
        ) { it.getLong(1) }.first()

    /** Actualiza solo las columnas dadas (nombres de [MODEL_COLUMNS], nunca del cuerpo). */
    fun updateModel(id: Long, cambios: Map<String, Any?>) = actualizar("ai_models", id, cambios, MODEL_COLUMNS)

    /** Agentes que usan cada modelo (principal, respaldo o imagenes). */
    fun modelUsage(): List<Triple<String, Long, String>> =
        lista(
            """
            SELECT code, model_id AS mid, 'model' AS rol FROM ai_agents
            UNION ALL SELECT code, fallback_model_id, 'fallback' FROM ai_agents WHERE fallback_model_id IS NOT NULL
            UNION ALL SELECT code, image_model_id, 'image' FROM ai_agents WHERE image_model_id IS NOT NULL
            """.trimIndent(),
        ) { Triple(it.getString("code"), it.getLong("mid"), it.getString("rol")) }

    /** Empresas (vigentes, no archivadas) cuyo agente eligio cada modelo en lugar del de su tipo. */
    fun modelTenantUsage(): Map<Long, Int> =
        lista(
            """
            SELECT mid, COUNT(DISTINCT tenant_id) AS n FROM (
                SELECT tenant_id, model_id AS mid FROM tenant_agents WHERE archived_at IS NULL AND model_id IS NOT NULL
                UNION ALL SELECT tenant_id, fallback_model_id FROM tenant_agents WHERE archived_at IS NULL AND fallback_model_id IS NOT NULL
                UNION ALL SELECT tenant_id, image_model_id FROM tenant_agents WHERE archived_at IS NULL AND image_model_id IS NOT NULL
            ) u GROUP BY mid
            """.trimIndent(),
        ) { it.getLong("mid") to it.getInt("n") }.toMap()

    fun agents(): List<AgentRow> = lista("$SELECT_AGENTS ORDER BY a.kind, a.name") { agente(it) }

    fun agent(id: Long): AgentRow? = lista("$SELECT_AGENTS WHERE a.id = ?", { it.setLong(1, id) }) { agente(it) }.firstOrNull()

    fun tools(): Map<Long, List<ToolGrantView>> =
        lista("SELECT agent_id, tool_pattern, mode, auto_approve, max_amount FROM ai_agent_tools ORDER BY tool_pattern") { rs ->
            rs.getLong("agent_id") to ToolGrantView(
                pattern = rs.getString("tool_pattern"),
                mode = rs.getString("mode").lowercase(),
                autoApprove = rs.getBoolean("auto_approve"),
                maxAmount = rs.getBigDecimal("max_amount"),
            )
        }.groupBy({ it.first }, { it.second })

    /** Empresas con un canal asignado hoy a cada agente. */
    fun companiesByAgent(): Map<Long, Int> =
        lista(
            """
            SELECT agent_id, COUNT(DISTINCT tenant_id) AS n FROM channel_agents
            WHERE valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
            GROUP BY agent_id
            """.trimIndent(),
        ) { it.getLong("agent_id") to it.getInt("n") }.toMap()

    fun updateAgent(id: Long, cambios: Map<String, Any?>) = actualizar("ai_agents", id, cambios, AGENT_COLUMNS)

    fun replaceTools(agentId: Long, grants: List<ToolGrantView>) {
        jdbc.prepareStatement("DELETE FROM ai_agent_tools WHERE agent_id = ?") { it.setLong(1, agentId); it.executeUpdate() }
        grants.forEach { g ->
            jdbc.prepareStatement(
                "INSERT INTO ai_agent_tools (agent_id, tool_pattern, mode, auto_approve, max_amount) VALUES (?, ?, ?, ?, ?)",
            ) { st ->
                st.setLong(1, agentId); st.setString(2, g.pattern); st.setString(3, g.mode)
                st.setBoolean(4, g.autoApprove); decimalONulo(st, 5, g.maxAmount)
                st.executeUpdate()
            }
        }
    }

    fun prompts(agentCode: String): List<PromptRow> =
        lista(
            "SELECT id, version, body, created_by, created_at FROM ai_prompt_versions WHERE agent_code = ? ORDER BY version DESC",
            { it.setString(1, agentCode) },
        ) { PromptRow(it.getLong("id"), it.getInt("version"), it.getString("body"), it.getString("created_by"), it.getTimestamp("created_at").toInstant()) }

    fun insertPrompt(agentCode: String, body: String, createdBy: String?): Long =
        lista(
            """
            INSERT INTO ai_prompt_versions (agent_code, version, body, created_by)
            VALUES (?, (SELECT COALESCE(MAX(version), 0) + 1 FROM ai_prompt_versions WHERE agent_code = ?), ?, ?)
            RETURNING id
            """.trimIndent(),
            { st -> st.setString(1, agentCode); st.setString(2, agentCode); st.setString(3, body); st.setString(4, createdBy) },
        ) { it.getLong(1) }.first()

    // ── utilidades ──────────────────────────────────────────────────────────

    private fun <T : Any> lista(sql: String, bind: (PreparedStatement) -> Unit = {}, fila: (ResultSet) -> T): List<T> =
        jdbc.prepareStatement(sql) { st ->
            bind(st)
            val rs = st.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += fila(rs)
            out
        }

    private fun actualizar(tabla: String, id: Long, cambios: Map<String, Any?>, permitidas: Set<String>) {
        if (cambios.isEmpty()) return
        require(permitidas.containsAll(cambios.keys)) { "Columna no permitida en $tabla: ${cambios.keys - permitidas}" }
        val columnas = cambios.keys.toList()
        val sql = "UPDATE $tabla SET ${columnas.joinToString { "$it = ?" }} WHERE id = ?"
        jdbc.prepareStatement(sql) { st ->
            columnas.forEachIndexed { i, c ->
                when (val v = cambios[c]) {
                    null -> st.setNull(i + 1, tipoNulo(c))
                    is BigDecimal -> st.setBigDecimal(i + 1, v)
                    is Boolean -> st.setBoolean(i + 1, v)
                    is Int -> st.setInt(i + 1, v)
                    is Long -> st.setLong(i + 1, v)
                    is String -> st.setString(i + 1, v)
                    else -> error("Tipo no soportado para $c: ${v::class.simpleName}")
                }
            }
            st.setLong(columnas.size + 1, id)
            st.executeUpdate()
        }
    }

    /** El tipo SQL de un nulo: Postgres no convierte un NUMERIC nulo a texto. */
    private fun tipoNulo(columna: String): Int = when {
        columna.endsWith("_id") -> Types.BIGINT
        columna == "temperature" || columna.endsWith("_1k") || columna.endsWith("_cost") -> Types.NUMERIC
        columna.endsWith("_at") -> Types.TIMESTAMP_WITH_TIMEZONE
        else -> Types.VARCHAR
    }

    private fun decimalONulo(st: PreparedStatement, i: Int, v: BigDecimal?) =
        if (v == null) st.setNull(i, Types.NUMERIC) else st.setBigDecimal(i, v)

    private fun modelo(rs: ResultSet) = ModelRow(
        id = rs.getLong("id"),
        providerId = rs.getLong("provider_id"),
        modelName = rs.getString("model_name"),
        contextWindow = rs.getInt("context_window"),
        inputCost1k = rs.getBigDecimal("input_cost_1k"),
        cachedCost1k = rs.getBigDecimal("cached_cost_1k"),
        outputCost1k = rs.getBigDecimal("output_cost_1k"),
        supportsTools = rs.getBoolean("supports_tools"),
        supportsCache = rs.getBoolean("supports_cache"),
        supportsVision = rs.getBoolean("supports_vision"),
        generatesImages = rs.getBoolean("generates_images"),
        imageCost = rs.getBigDecimal("image_cost"),
        active = rs.getBoolean("is_active"),
    )

    private fun agente(rs: ResultSet) = AgentRow(
        id = rs.getLong("id"),
        code = rs.getString("code"),
        name = rs.getString("name"),
        kind = rs.getString("kind"),
        description = rs.getString("description"),
        modelId = rs.getLong("model_id"),
        fallbackModelId = rs.getObject("fallback_model_id")?.let { rs.getLong("fallback_model_id") },
        imageModelId = rs.getObject("image_model_id")?.let { rs.getLong("image_model_id") },
        temperature = rs.getBigDecimal("temperature"),
        maxToolLoops = rs.getInt("max_tool_loops"),
        responseTimeoutMs = rs.getInt("response_timeout_ms"),
        active = rs.getBoolean("is_active"),
        archived = rs.getTimestamp("archived_at") != null,
        promptId = rs.getLong("prompt_id"),
        promptVersion = rs.getInt("prompt_version"),
        promptCreatedBy = rs.getString("prompt_created_by"),
        promptCreatedAt = rs.getTimestamp("prompt_created_at").toInstant(),
    )

    companion object {
        /** Columnas que la consola puede cambiar; cualquier otra es un error de programacion. */
        val MODEL_COLUMNS = setOf(
            "context_window", "input_cost_1k", "cached_cost_1k", "output_cost_1k", "supports_tools",
            "supports_cache", "supports_vision", "generates_images", "image_cost", "is_active",
        )
        val AGENT_COLUMNS = setOf(
            "model_id", "fallback_model_id", "image_model_id", "temperature", "max_tool_loops",
            "response_timeout_ms", "is_active", "prompt_version_id", "name", "description",
        )
        val PROVIDER_COLUMNS = setOf("name", "base_url", "auth_type", "credential_ref", "is_active")

        private const val SELECT_MODELS = """
            SELECT m.id, m.provider_id, m.model_name, m.context_window, m.input_cost_1k, m.cached_cost_1k,
                   m.output_cost_1k, m.supports_tools, m.supports_cache, m.supports_vision, m.generates_images,
                   m.image_cost, m.is_active
            FROM ai_models m"""

        private const val SELECT_AGENTS = """
            SELECT a.id, a.code, a.name, a.kind, a.description, a.model_id, a.fallback_model_id, a.image_model_id,
                   a.temperature, a.max_tool_loops, a.response_timeout_ms, a.is_active, a.archived_at,
                   pv.id AS prompt_id, pv.version AS prompt_version, pv.created_by AS prompt_created_by,
                   pv.created_at AS prompt_created_at
            FROM ai_agents a
            JOIN ai_prompt_versions pv ON pv.id = a.prompt_version_id"""
    }
}
