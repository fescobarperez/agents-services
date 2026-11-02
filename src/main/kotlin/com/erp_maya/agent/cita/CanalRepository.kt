package com.erp_maya.agent.cita

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

/** Por que numero de WhatsApp se le escribe a los pacientes de una empresa. */
@Singleton
open class CanalRepository(private val jdbc: JdbcOperations) {

    /**
     * La cuenta (`wa:{phone_number_id}`) del WhatsApp de la empresa: primero
     * el numero propio; si no tiene, uno compartido que hoy la atienda.
     */
    @Transactional(readOnly = true)
    open fun whatsappDe(tenantId: Long): String? =
        jdbc.prepareStatement(SQL) { st ->
            st.setLong(1, tenantId)
            st.setLong(2, tenantId)
            st.setLong(3, tenantId)
            val rs = st.executeQuery()
            // prepareStatement no admite devolver null (su retorno es @NonNull):
            // se devuelve una lista y se toma el primero, como en ConversationRepository.
            listOfNotNull(if (rs.next()) rs.getString("account_ref") else null)
        }.firstOrNull()

    private companion object {
        const val SQL = """
            SELECT c.account_ref
            FROM channels c
            WHERE c.is_active AND c.kind = 'whatsapp'
              AND (c.tenant_id = ?
                   OR EXISTS (SELECT 1 FROM channel_agents ca
                              WHERE ca.channel_id = c.id AND ca.tenant_id = ?
                                AND now() >= ca.valid_from AND now() < coalesce(ca.valid_to, 'infinity'::timestamptz)))
            ORDER BY (c.tenant_id = ?) DESC NULLS LAST, c.id
            LIMIT 1
        """
    }
}
