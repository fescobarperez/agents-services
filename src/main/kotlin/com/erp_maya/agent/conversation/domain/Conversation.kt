package com.erp_maya.agent.conversation.domain

import java.time.Instant

/** Un hilo con un remitente en un canal. */
data class Conversation(
    val id: Long,
    val channelId: Long,
    val tenantId: Long,
    val externalRef: String,
    val agentId: Long,
    val createdAt: Instant,
)

/** A quien y por donde se le escribe a una conversacion. */
data class ConversationDestination(
    val conversationId: Long,
    val tenantId: Long,
    val channelKind: String,
    val accountRef: String,
    val externalRef: String,
    /** Ultimo mensaje del cliente: de aqui sale la ventana de 24 h de WhatsApp. */
    val lastInboundAt: Instant?,
)

/** Estado de un turno. Refleja el CHECK de `agent_turns.status`. */
enum class TurnStatus { PENDING, RUNNING, DONE, FAILED, ESCALATED }

/**
 * Un turno: una llamada al endpoint.
 *
 * `response` guarda lo que se contesto, y es lo que se reenvia cuando llega
 * otra vez la misma `idempotencyKey`.
 */
data class AgentTurn(
    val id: Long,
    val conversationId: Long,
    val idempotencyKey: String,
    val status: TurnStatus,
    val response: String?,
    /**
     * Cierto solo si fue ESTA llamada la que inserto la fila.
     *
     * No se puede deducir del estado. Si se mirara `status == PENDING`, entre
     * el INSERT del ganador y su paso a RUNNING hay una ventana en la que el
     * perdedor tambien ve PENDING, y entonces los dos ejecutan el turno: dos
     * cobros de tokens y, si el turno emite, dos cotizaciones. Lo unico que
     * distingue de verdad es quien gano el INSERT.
     */
    val claimedHere: Boolean = false,
) {

    companion object {
        /** El id publico que ve el canal. */
        fun publicId(id: Long): String = "trn_%08d".format(id)

        /** Inverso de [publicId]; null si no tiene ese formato. */
        fun fromPublicId(publico: String): Long? =
            publico.removePrefix("trn_").takeIf { it != publico }?.toLongOrNull()
    }
}

/** Un mensaje ya persistido, tal como se relee para armar el prompt. */
data class StoredMessage(
    val id: Long,
    val direction: Direction,
    val body: String?,
    /**
     * Turno al que pertenece. Nulo mientras el entrante espera en la cola: se
     * registra al llegar y se enlaza cuando el consumidor abre su turno.
     */
    val turnId: Long? = null,
)

data class Message(
    val id: Long,
    val conversationId: Long,
    val turnId: Long?,
    val externalId: String?,
    val direction: Direction,
    val body: String?,
)

enum class Direction(val sql: String) { IN("in"), OUT("out") }

/**
 * La misma `idempotency_key` llego mientras el primer turno sigue corriendo.
 *
 * No se espera ni se ejecuta de nuevo: se avisa. Reintentar seria cobrar dos
 * veces los tokens y, si el turno emite una cotizacion, emitirla dos veces.
 */
class TurnInProgressException(val turnId: String) :
    RuntimeException("El turno $turnId todavia se esta procesando")
