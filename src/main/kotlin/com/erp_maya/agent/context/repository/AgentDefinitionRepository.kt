package com.erp_maya.agent.context.repository

import com.erp_maya.agent.context.domain.AgentDefinition
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import io.micronaut.cache.annotation.CacheConfig
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.ReadOnly
import jakarta.inject.Singleton

/** Agentes de sistema, los que se invocan por codigo y no por canal. */
@Singleton
@CacheConfig("agent-definitions")
open class AgentDefinitionRepository(private val jdbc: JdbcOperations) {

    @Cacheable
    @ReadOnly
    open fun byCode(code: String): AgentDefinition? =
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setString(1, code)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                emptyList()
            } else {
                listOf(
                    AgentDefinition(
                        id = rs.getLong("agent_id"),
                        code = rs.getString("agent_code"),
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
                        temperature = rs.getBigDecimal("temperature"),
                        responseTimeoutMs = rs.getInt("response_timeout_ms"),
                    ),
                )
            }
        }.firstOrNull()

    private companion object {
        const val SQL = """
            SELECT a.id AS agent_id, a.code AS agent_code, a.temperature, a.response_timeout_ms,
                   m.id AS model_id, p.code AS provider_code,
                   m.model_name, m.context_window, m.supports_tools, m.supports_cache,
                   fm.id AS fallback_model_id, fp.code AS fallback_provider_code,
                   fm.model_name AS fallback_model_name, fm.context_window AS fallback_context_window,
                   fm.supports_tools AS fallback_supports_tools, fm.supports_cache AS fallback_supports_cache,
                   pv.id AS prompt_version_id, pv.version AS prompt_version, pv.body AS prompt_body
            FROM ai_agents a
            JOIN ai_models m           ON m.id = a.model_id AND m.is_active
            JOIN ai_providers p        ON p.id = m.provider_id AND p.is_active
            LEFT JOIN ai_models fm     ON fm.id = a.fallback_model_id
            LEFT JOIN ai_providers fp  ON fp.id = fm.provider_id
            JOIN ai_prompt_versions pv ON pv.id = a.prompt_version_id
            WHERE a.code = ? AND a.is_active
        """
    }
}
