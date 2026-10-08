package com.erp_maya.agent.playbook.repository

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.ReadOnly
import jakarta.inject.Singleton
import java.sql.Types

/** Lectura de `agent_playbooks`. */
@Singleton
open class PlaybookRepository(private val jdbc: JdbcOperations) {

    /**
     * El JSON del playbook vigente: el de la empresa si tiene uno, si no el
     * general del agente (tenant_id nulo). Dentro de cada nivel, la version
     * mas alta activa.
     */
    @ReadOnly
    open fun vigente(agentCode: String, tenantId: Long): String? =
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setString(1, agentCode)
            stmt.setObject(2, tenantId, Types.BIGINT)
            val rs = stmt.executeQuery()
            // prepareStatement no admite resultado nulo: se envuelve en lista.
            listOfNotNull(if (rs.next()) rs.getString(1) else null)
        }.firstOrNull()

    private companion object {
        const val SQL = """
            SELECT config::text FROM agent_playbooks
            WHERE agent_code = ? AND is_active
              AND (tenant_id = ? OR tenant_id IS NULL)
            ORDER BY (tenant_id IS NULL), version DESC
            LIMIT 1
        """
    }
}
