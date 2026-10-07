package com.erp_maya.agent.conversation.repository

import com.erp_maya.agent.conversation.domain.AgentTurn
import com.erp_maya.agent.conversation.domain.TurnStatus
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

/**
 * Turnos y su idempotencia.
 *
 * Cada metodo abre su propia transaccion **a proposito**. El turno tiene que
 * quedar registrado aunque la ejecucion falle despues: si todo colgara de una
 * sola transaccion, un turno fallido se borraria al hacer rollback y se
 * perderia justo la auditoria que mas importa.
 */
@Singleton
open class AgentTurnRepository(private val jdbc: JdbcOperations) {

    /**
     * Reclama la llave de idempotencia. Devuelve el turno nuevo, o el que ya
     * existia si otra llamada llego primero.
     *
     * La carrera se resuelve en el indice unico, no en un SELECT previo: entre
     * el SELECT y el INSERT cabe perfectamente el otro hilo.
     */
    @Transactional
    open fun claim(conversationId: Long, idempotencyKey: String): AgentTurn {
        // Lista de cero o un elemento porque prepareStatement declara <R : Any>
        // y no admite que el lambda devuelva null.
        val nuevo = jdbc.prepareStatement(SQL_CLAIM) { stmt ->
            stmt.setLong(1, conversationId)
            stmt.setString(2, idempotencyKey)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                listOf(
                    AgentTurn(
                        rs.getLong("id"), conversationId, idempotencyKey,
                        TurnStatus.PENDING, null, claimedHere = true,
                    ),
                )
            } else {
                emptyList()
            }
        }
        return nuevo.firstOrNull() ?: reclamarFallido(idempotencyKey) ?: existente(idempotencyKey)
            ?: error("La llave '$idempotencyKey' ni inserto ni existe")
    }

    /**
     * Un turno que FALLO no respondio nada: reintentarlo con la misma llave
     * debe volver a ejecutarlo, no quedarse en 409 para siempre. El UPDATE
     * condicionado al estado resuelve la carrera: solo un reintento lo gana.
     * Los terminados (done/escalated) y los que siguen vivos no se tocan.
     */
    private fun reclamarFallido(idempotencyKey: String): AgentTurn? =
        jdbc.prepareStatement(SQL_RECLAIM_FAILED) { stmt ->
            stmt.setString(1, idempotencyKey)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                listOf(
                    AgentTurn(
                        rs.getLong("id"), rs.getLong("conversation_id"), idempotencyKey,
                        TurnStatus.PENDING, null, claimedHere = true,
                    ),
                )
            } else {
                emptyList()
            }
        }.firstOrNull()

    @Transactional
    open fun existente(idempotencyKey: String): AgentTurn? =
        jdbc.prepareStatement(SQL_BY_KEY) { stmt ->
            stmt.setString(1, idempotencyKey)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                emptyList()
            } else {
                listOf(
                    AgentTurn(
                        id = rs.getLong("id"),
                        conversationId = rs.getLong("conversation_id"),
                        idempotencyKey = idempotencyKey,
                        status = TurnStatus.valueOf(rs.getString("status").uppercase()),
                        response = rs.getString("response"),
                        claimedHere = false,
                    ),
                )
            }
        }.firstOrNull()

    @Transactional
    open fun markRunning(turnId: Long) = actualizar(turnId, TurnStatus.RUNNING, null)

    @Transactional
    open fun complete(turnId: Long, responseJson: String) =
        actualizar(turnId, TurnStatus.DONE, responseJson)

    @Transactional
    open fun fail(turnId: Long, status: TurnStatus, detalle: String) =
        actualizar(turnId, status, """{"error":${quote(detalle)}}""")

    private fun actualizar(turnId: Long, status: TurnStatus, responseJson: String?) {
        jdbc.prepareStatement(SQL_UPDATE) { stmt ->
            val estado = status.name.lowercase()
            stmt.setString(1, estado)
            stmt.setString(2, responseJson)
            stmt.setString(3, estado)   // el CASE del completed_at lo vuelve a mirar
            stmt.setLong(4, turnId)
            stmt.executeUpdate()
        }
    }

    /** Escapado minimo para incrustar un mensaje en el JSON del error. */
    private fun quote(s: String) =
        '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"'

    private companion object {
        const val SQL_CLAIM = """
            INSERT INTO agent_turns (conversation_id, idempotency_key, status)
            VALUES (?, ?, 'pending')
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
        """

        const val SQL_RECLAIM_FAILED = """
            UPDATE agent_turns
               SET status = 'pending', response = NULL, completed_at = NULL
             WHERE idempotency_key = ? AND status = 'failed'
            RETURNING id, conversation_id
        """

        const val SQL_BY_KEY = """
            SELECT id, conversation_id, status, response::text AS response
            FROM agent_turns WHERE idempotency_key = ?
        """

        /** `completed_at` solo se sella cuando el turno de verdad termino. */
        const val SQL_UPDATE = """
            UPDATE agent_turns
               SET status = ?,
                   response = coalesce(?::jsonb, response),
                   completed_at = CASE WHEN ? IN ('done', 'failed', 'escalated')
                                       THEN now() ELSE completed_at END
             WHERE id = ?
        """
    }
}
