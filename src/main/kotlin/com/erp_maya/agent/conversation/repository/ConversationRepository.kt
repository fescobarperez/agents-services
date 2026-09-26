package com.erp_maya.agent.conversation.repository

import com.erp_maya.agent.conversation.domain.Conversation
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.StoredMessage
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet

/**
 * Conversaciones y mensajes.
 *
 * El alta usa `ON CONFLICT ... DO UPDATE` para que dos mensajes simultaneos del
 * mismo remitente no creen dos hilos: la carrera la resuelve el indice unico
 * `(channel_id, external_ref)`, no un `SELECT` previo que siempre llega tarde.
 */
@Singleton
open class ConversationRepository(private val jdbc: JdbcOperations) {

    @Transactional
    open fun findOrCreate(channelId: Long, tenantId: Long, externalRef: String, agentId: Long): Conversation =
        jdbc.prepareStatement(SQL_UPSERT) { stmt ->
            stmt.setLong(1, channelId)
            stmt.setLong(2, tenantId)
            stmt.setString(3, externalRef)
            stmt.setLong(4, agentId)
            val rs = stmt.executeQuery()
            check(rs.next()) { "El upsert de conversacion no devolvio fila" }
            leer(rs)
        }

    @Transactional
    open fun appendMessage(
        conversationId: Long,
        turnId: Long?,
        externalId: String?,
        direction: Direction,
        body: String?,
    ): Long? = jdbc.prepareStatement(SQL_MESSAGE) { stmt ->
        stmt.setLong(1, conversationId)
        stmt.setObject(2, turnId, java.sql.Types.BIGINT)
        stmt.setString(3, externalId)
        stmt.setString(4, direction.sql)
        stmt.setString(5, body)
        val rs = stmt.executeQuery()
        // Sin fila significa que ese external_id ya estaba: el webhook
        // reintento y el unico hizo su trabajo.
        if (rs.next()) listOf(rs.getLong(1)) else emptyList()
    }.firstOrNull()

    /**
     * Los ultimos mensajes del hilo, del mas viejo al mas nuevo.
     *
     * La ventana se lee de `messages` y no de una copia en el jsonb del
     * estado: duplicarla obligaria a dos escrituras por turno y abriria la
     * puerta a que las dos versiones dejen de coincidir.
     */
    @Transactional
    open fun recentMessages(conversationId: Long, limite: Int): List<StoredMessage> =
        jdbc.prepareStatement(SQL_RECENT) { stmt ->
            stmt.setLong(1, conversationId)
            stmt.setInt(2, limite)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<StoredMessage>()
            while (rs.next()) {
                filas += StoredMessage(
                    id = rs.getLong("id"),
                    direction = if (rs.getString("direction") == "in") Direction.IN else Direction.OUT,
                    body = rs.getString("body"),
                )
            }
            filas.reversed()
        }

    @Transactional
    open fun readState(conversationId: Long): String =
        jdbc.prepareStatement("SELECT state::text FROM conversations WHERE id = ?") { stmt ->
            stmt.setLong(1, conversationId)
            val rs = stmt.executeQuery()
            if (rs.next()) listOf(rs.getString(1) ?: "{}") else listOf("{}")
        }.first()

    @Transactional
    open fun writeState(conversationId: Long, stateJson: String) {
        jdbc.prepareStatement("UPDATE conversations SET state = ?::jsonb, updated_at = now() WHERE id = ?") { stmt ->
            stmt.setString(1, stateJson)
            stmt.setLong(2, conversationId)
            stmt.executeUpdate()
        }
    }

    @Transactional
    open fun countMessages(conversationId: Long): Int =
        jdbc.prepareStatement("SELECT count(*) FROM messages WHERE conversation_id = ?") { stmt ->
            stmt.setLong(1, conversationId)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }

    private fun leer(rs: ResultSet) = Conversation(
        id = rs.getLong("id"),
        channelId = rs.getLong("channel_id"),
        tenantId = rs.getLong("tenant_id"),
        externalRef = rs.getString("external_ref"),
        agentId = rs.getLong("agent_id"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private companion object {
        /**
         * El DO UPDATE toca `updated_at` para que el RETURNING devuelva fila
         * tanto si inserto como si ya existia; un DO NOTHING no devuelve nada
         * y obligaria a una segunda consulta.
         */
        const val SQL_UPSERT = """
            INSERT INTO conversations (channel_id, tenant_id, external_ref, agent_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (channel_id, external_ref)
            DO UPDATE SET updated_at = now()
            RETURNING id, channel_id, tenant_id, external_ref, agent_id, created_at
        """

        /** Descendente para tomar los N ultimos; se invierte al devolver. */
        const val SQL_RECENT = """
            SELECT id, direction, body FROM messages
            WHERE conversation_id = ? AND body IS NOT NULL
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """

        const val SQL_MESSAGE = """
            INSERT INTO messages (conversation_id, turn_id, external_id, direction, body)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (external_id) DO NOTHING
            RETURNING id
        """
    }
}
