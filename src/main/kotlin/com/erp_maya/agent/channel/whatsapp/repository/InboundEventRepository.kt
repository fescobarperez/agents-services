package com.erp_maya.agent.channel.whatsapp.repository

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.Timestamp
import java.time.Instant

/** Un evento de la cola listo para procesarse. */
data class InboundEvent(
    val id: Long,
    val channelId: Long,
    val externalId: String,
    val senderRef: String,
    val payload: String,
    val attempts: Int,
)

/**
 * Cola de entrada en Postgres.
 *
 * Respaldada en base y no en memoria por una razon concreta: al webhook se le
 * responde 200 en menos de cinco segundos, y Meta no reintenta lo que ya
 * acepto. Un reinicio con la cola en memoria pierde esos mensajes para
 * siempre.
 */
@Singleton
open class InboundEventRepository(private val jdbc: JdbcOperations) {

    /**
     * Encola el mensaje. Devuelve false si ya estaba: el unico sobre
     * `external_id` es lo que deduplica los reintentos del webhook antes de
     * tocar nada mas.
     */
    @Transactional
    open fun enqueue(
        channelId: Long,
        externalId: String,
        senderRef: String,
        payload: String,
        disponibleEn: Instant,
    ): Boolean = jdbc.prepareStatement(SQL_ENQUEUE) { stmt ->
        stmt.setLong(1, channelId)
        stmt.setString(2, externalId)
        stmt.setString(3, senderRef)
        stmt.setString(4, payload)
        stmt.setTimestamp(5, Timestamp.from(disponibleEn))
        val rs = stmt.executeQuery()
        if (rs.next()) listOf(rs.getLong(1)) else emptyList()
    }.isNotEmpty()

    /**
     * Toma un lote de eventos vencidos y los marca en proceso.
     *
     * `FOR UPDATE SKIP LOCKED` permite que varias instancias consuman la misma
     * cola sin pisarse ni bloquearse entre ellas.
     */
    @Transactional
    open fun claimBatch(worker: String, limite: Int): List<InboundEvent> =
        jdbc.prepareStatement(SQL_CLAIM) { stmt ->
            stmt.setString(1, worker)
            stmt.setInt(2, limite)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<InboundEvent>()
            while (rs.next()) {
                filas += InboundEvent(
                    id = rs.getLong("id"),
                    channelId = rs.getLong("channel_id"),
                    externalId = rs.getString("external_id"),
                    senderRef = rs.getString("sender_ref"),
                    payload = rs.getString("payload"),
                    attempts = rs.getInt("attempts"),
                )
            }
            filas
        }

    @Transactional
    open fun markDone(id: Long) = actualizar(id, "done", null)

    /**
     * Devuelve el evento a la cola con espera creciente, o lo da por perdido
     * tras [MAX_INTENTOS]: reintentar sin fin un mensaje que siempre falla
     * bloquea la cola detras de el.
     */
    @Transactional
    open fun markFailed(id: Long, intentos: Int, error: String) {
        if (intentos + 1 >= MAX_INTENTOS) {
            actualizar(id, "failed", error)
        } else {
            jdbc.prepareStatement(SQL_RETRY) { stmt ->
                stmt.setInt(1, ESPERA_BASE_SEGUNDOS * (intentos + 1))
                stmt.setString(2, error)
                stmt.setLong(3, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun actualizar(id: Long, estado: String, error: String?) {
        jdbc.prepareStatement(SQL_UPDATE) { stmt ->
            stmt.setString(1, estado)
            stmt.setString(2, error)
            stmt.setLong(3, id)
            stmt.executeUpdate()
        }
    }

    companion object {
        const val MAX_INTENTOS = 5
        const val ESPERA_BASE_SEGUNDOS = 30

        private const val SQL_ENQUEUE = """
            INSERT INTO inbound_events (channel_id, external_id, sender_ref, payload, available_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (external_id) DO NOTHING
            RETURNING id
        """

        private const val SQL_CLAIM = """
            UPDATE inbound_events SET status = 'processing', locked_by = ?, locked_at = now(),
                                      attempts = attempts + 1
            WHERE id IN (
                SELECT id FROM inbound_events
                WHERE status = 'pending' AND available_at <= now()
                ORDER BY available_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, channel_id, external_id, sender_ref, payload::text AS payload, attempts
        """

        private const val SQL_UPDATE = """
            UPDATE inbound_events SET status = ?, last_error = ?, locked_by = NULL WHERE id = ?
        """

        private const val SQL_RETRY = """
            UPDATE inbound_events
               SET status = 'pending', locked_by = NULL, locked_at = NULL,
                   available_at = now() + (? || ' seconds')::interval,
                   last_error = ?
             WHERE id = ?
        """
    }
}
