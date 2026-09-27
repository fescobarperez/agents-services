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
                filas += leerMensaje(rs)
            }
            filas.reversed()
        }

    /**
     * Enlaza al turno los entrantes que ya se registraron al llegar.
     *
     * Solo toca los que aun no tienen turno: un reintento del mismo lote no
     * los mueve a otro turno.
     */
    @Transactional
    open fun linkToTurn(conversationId: Long, turnId: Long, externalIds: List<String>): Int {
        if (externalIds.isEmpty()) return 0
        return jdbc.prepareStatement(SQL_LINK) { stmt ->
            stmt.setLong(1, turnId)
            stmt.setLong(2, conversationId)
            stmt.setArray(3, stmt.connection.createArrayOf("varchar", externalIds.toTypedArray()))
            stmt.executeUpdate()
        }
    }

    /**
     * Sella en el saliente del turno el id que le dio el canal al entregarlo.
     * Hasta entonces el saliente no existe alla y va sin `external_id`.
     */
    @Transactional
    open fun sealOutbound(turnId: Long, externalId: String): Int =
        jdbc.prepareStatement(SQL_SEAL) { stmt ->
            stmt.setString(1, externalId)
            stmt.setLong(2, turnId)
            stmt.executeUpdate()
        }

    /** Mensajes con id mayor a [afterId], del mas viejo al mas nuevo. */
    @Transactional
    open fun messagesAfter(conversationId: Long, afterId: Long, limite: Int): List<StoredMessage> =
        jdbc.prepareStatement(SQL_AFTER) { stmt ->
            stmt.setLong(1, conversationId)
            stmt.setLong(2, afterId)
            stmt.setInt(3, limite)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<StoredMessage>()
            while (rs.next()) filas += leerMensaje(rs)
            filas
        }

    /**
     * Mezcla claves en el estado sin reescribirlo entero.
     *
     * Lo usa el resumen por inactividad, que corre fuera de un turno: si
     * escribiera el estado completo podria pisar la escritura pendiente que un
     * turno concurrente acaba de guardar.
     */
    @Transactional
    open fun mergeState(conversationId: Long, parcialJson: String) {
        jdbc.prepareStatement("UPDATE conversations SET state = state || ?::jsonb WHERE id = ?") { stmt ->
            stmt.setString(1, parcialJson)
            stmt.setLong(2, conversationId)
            stmt.executeUpdate()
        }
    }

    /**
     * Conversaciones sin actividad desde hace [minutos] que tienen mensajes
     * posteriores a lo ultimo resumido.
     */
    @Transactional
    open fun inactiveWithUnsummarized(minutos: Int, limite: Int): List<Long> =
        jdbc.prepareStatement(SQL_INACTIVAS) { stmt ->
            stmt.setInt(1, minutos)
            stmt.setInt(2, limite)
            val rs = stmt.executeQuery()
            val ids = mutableListOf<Long>()
            while (rs.next()) ids += rs.getLong(1)
            ids
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

    private fun leerMensaje(rs: ResultSet) = StoredMessage(
        id = rs.getLong("id"),
        direction = if (rs.getString("direction") == "in") Direction.IN else Direction.OUT,
        body = rs.getString("body"),
        turnId = rs.getLong("turn_id").takeUnless { rs.wasNull() },
    )

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
            SELECT id, direction, body, turn_id FROM messages
            WHERE conversation_id = ? AND body IS NOT NULL
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """

        const val SQL_AFTER = """
            SELECT id, direction, body, turn_id FROM messages
            WHERE conversation_id = ? AND id > ? AND body IS NOT NULL
            ORDER BY id
            LIMIT ?
        """

        const val SQL_LINK = """
            UPDATE messages SET turn_id = ?
            WHERE conversation_id = ? AND external_id = ANY(?) AND turn_id IS NULL
        """

        /** El primer saliente del turno que todavia no tiene id del canal. */
        const val SQL_SEAL = """
            UPDATE messages SET external_id = ?
            WHERE id = (
                SELECT id FROM messages
                WHERE turn_id = ? AND direction = 'out' AND external_id IS NULL
                ORDER BY id
                LIMIT 1
            )
        """

        /**
         * `updated_at` lo toca cada entrante (al abrir la conversacion en la
         * ingesta) y cada turno; el resumen se compara contra el ultimo id
         * resumido que guarda el estado.
         */
        const val SQL_INACTIVAS = """
            SELECT c.id FROM conversations c
            WHERE c.updated_at < now() - make_interval(mins => ?)
              AND EXISTS (
                  SELECT 1 FROM messages m
                  WHERE m.conversation_id = c.id AND m.body IS NOT NULL
                    AND m.id > COALESCE((c.state ->> 'summarizedThrough')::bigint, 0)
              )
            ORDER BY c.updated_at
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
