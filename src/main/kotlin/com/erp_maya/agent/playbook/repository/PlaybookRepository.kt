package com.erp_maya.agent.playbook.repository

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.ReadOnly
import jakarta.inject.Singleton
import java.sql.Types

/** Lectura de `agent_playbooks`. */
@Singleton
open class PlaybookRepository(private val jdbc: JdbcOperations) {

    /**
     * El JSON del playbook vigente, del mas particular al mas general: el del
     * agente de la empresa; si no tiene, uno de la empresa para el tipo que
     * aun no sea de ningun agente (asignaciones antiguas); si no, el general
     * del tipo (tenant_id nulo). Dentro de cada nivel, la version mas alta activa.
     */
    @ReadOnly
    open fun vigente(agentCode: String, tenantId: Long, tenantAgentId: Long? = null): String? =
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setObject(1, tenantAgentId, Types.BIGINT)
            stmt.setString(2, agentCode)
            stmt.setObject(3, tenantId, Types.BIGINT)
            val rs = stmt.executeQuery()
            // prepareStatement no admite resultado nulo: se envuelve en lista.
            listOfNotNull(if (rs.next()) rs.getString(1) else null)
        }.firstOrNull()

    private companion object {
        const val SQL = """
            SELECT config::text FROM agent_playbooks
            WHERE is_active
              AND (tenant_agent_id = ?::bigint
                   OR (tenant_agent_id IS NULL AND agent_code = ? AND (tenant_id = ? OR tenant_id IS NULL)))
            ORDER BY (tenant_agent_id IS NULL), (tenant_id IS NULL), version DESC
            LIMIT 1
        """
    }
}
