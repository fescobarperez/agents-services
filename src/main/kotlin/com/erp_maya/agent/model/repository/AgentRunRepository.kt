package com.erp_maya.agent.model.repository

import com.erp_maya.agent.model.domain.ModelUsage
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

/**
 * Auditoria de cada llamada al modelo.
 *
 * Se escribe SIEMPRE, tambien cuando el turno fallo: saber que un modelo se
 * cayo, cuanto tardo y si hubo que recurrir al de respaldo es justo el dato
 * que se busca cuando algo va mal, y es lo primero que se pierde si solo se
 * registran los exitos.
 */
@Singleton
open class AgentRunRepository(private val jdbc: JdbcOperations) {

    @Transactional
    open fun record(
        turnId: Long,
        conversationId: Long,
        agentId: Long,
        modelId: Long,
        promptVersionId: Long,
        usage: ModelUsage,
        toolsCalledJson: String?,
        latencyMs: Int,
        fallbackUsed: Boolean,
    ) {
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setLong(1, turnId)
            stmt.setLong(2, conversationId)
            stmt.setLong(3, agentId)
            stmt.setLong(4, modelId)
            stmt.setLong(5, promptVersionId)
            stmt.setInt(6, usage.input)
            stmt.setInt(7, usage.cached)
            stmt.setInt(8, usage.output)
            stmt.setString(9, toolsCalledJson)
            stmt.setInt(10, latencyMs)
            stmt.setBoolean(11, fallbackUsed)
            stmt.executeUpdate()
        }
    }

    private companion object {
        const val SQL = """
            INSERT INTO agent_runs (turn_id, conversation_id, agent_id, model_id, prompt_version_id,
                                    tokens_in, tokens_cached, tokens_out, tools_called,
                                    latency_ms, fallback_used)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """
    }
}
