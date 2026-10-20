package com.erp_maya.agent.model.repository

import com.erp_maya.agent.model.domain.CostoLlamada
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.PreciosModelo
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.sql.Types

/**
 * Auditoria de cada llamada al modelo. Se escribe SIEMPRE, tambien cuando el
 * turno fallo: es la base de uso y costos de la consola.
 *
 * Empresa y canal salen de la conversacion; el costo se congela aqui con los
 * precios vigentes del modelo (si el precio cambia despues, esto no cambia).
 */
@Singleton
open class AgentRunRepository(private val jdbc: JdbcOperations) {

    @Transactional
    open fun record(
        /** Nulo solo en un resumen por inactividad, que corre fuera de un turno. */
        turnId: Long?,
        conversationId: Long,
        agentId: Long,
        modelId: Long,
        promptVersionId: Long,
        usage: ModelUsage,
        toolsCalledJson: String?,
        latencyMs: Int,
        fallbackUsed: Boolean,
        /** El agente de la empresa que atendio (uso y costos por agente); nulo en asignaciones antiguas. */
        tenantAgentId: Long? = null,
        /** `turn`, `summary` o `vision`. */
        kind: String = KIND_TURN,
    ) {
        val costo = precios(modelId)?.let { CostoLlamada.usd(usage, it) } ?: BigDecimal.ZERO
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setObject(1, turnId, Types.BIGINT)
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
            stmt.setObject(12, tenantAgentId, Types.BIGINT)
            stmt.setString(13, kind)
            stmt.setBigDecimal(14, costo)
            stmt.setLong(15, conversationId)
            stmt.executeUpdate()
        }
    }

    private fun precios(modelId: Long): PreciosModelo? =
        jdbc.prepareStatement("SELECT input_cost_1k, cached_cost_1k, output_cost_1k FROM ai_models WHERE id = ?") { st ->
            st.setLong(1, modelId)
            val rs = st.executeQuery()
            if (rs.next()) listOf(PreciosModelo(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3))) else emptyList()
        }.firstOrNull()

    companion object {
        const val KIND_TURN = "turn"
        const val KIND_SUMMARY = "summary"
        const val KIND_VISION = "vision"

        private const val SQL = """
            INSERT INTO agent_runs (turn_id, conversation_id, agent_id, model_id, prompt_version_id,
                                    tokens_in, tokens_cached, tokens_out, tools_called,
                                    latency_ms, fallback_used, tenant_agent_id, kind, cost_usd,
                                    tenant_id, channel)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, cv.tenant_id, ch.kind
            FROM conversations cv JOIN channels ch ON ch.id = cv.channel_id
            WHERE cv.id = ?
        """
    }
}
