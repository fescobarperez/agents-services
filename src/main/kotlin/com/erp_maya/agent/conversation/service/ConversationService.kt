package com.erp_maya.agent.conversation.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.conversation.domain.AgentTurn
import com.erp_maya.agent.conversation.domain.Conversation
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.TurnStatus
import com.erp_maya.agent.conversation.repository.AgentTurnRepository
import com.erp_maya.agent.conversation.repository.ConversationRepository
import jakarta.inject.Singleton

/**
 * Fachada de persistencia del turno.
 *
 * No lleva `@Transactional` propia: cada metodo del repositorio abre la suya.
 * Es deliberado — envolver todo en una sola transaccion haria que un turno
 * fallido desapareciera con el rollback, y el registro de los fallos es justo
 * lo que se quiere auditar.
 */
@Singleton
open class ConversationService(
    private val conversaciones: ConversationRepository,
    private val turnos: AgentTurnRepository,
) {

    open fun abrirConversacion(contexto: ExecutionContext, externalRef: String): Conversation =
        conversaciones.findOrCreate(
            channelId = contexto.channel.id,
            tenantId = contexto.tenantId,
            externalRef = externalRef,
            agentId = contexto.agent.id,
        )

    /** Reclama la llave de idempotencia; devuelve el turno nuevo o el que ya existia. */
    open fun abrirTurno(conversationId: Long, idempotencyKey: String): AgentTurn =
        turnos.claim(conversationId, idempotencyKey)

    open fun registrarEntrante(conversationId: Long, turnId: Long, externalId: String?, texto: String?) {
        conversaciones.appendMessage(conversationId, turnId, externalId, Direction.IN, texto)
    }

    /**
     * El saliente va sin `external_id`: todavia no existe en el canal. Cuando
     * el adaptador lo entregue y WhatsApp devuelva su wamid, ahi se sella.
     */
    open fun registrarSaliente(conversationId: Long, turnId: Long, texto: String?) {
        conversaciones.appendMessage(conversationId, turnId, null, Direction.OUT, texto)
    }

    open fun enEjecucion(turnId: Long) = turnos.markRunning(turnId)

    open fun completar(turnId: Long, responseJson: String) = turnos.complete(turnId, responseJson)

    open fun fallar(turnId: Long, status: TurnStatus, detalle: String) =
        turnos.fail(turnId, status, detalle)

    open fun contarMensajes(conversationId: Long) = conversaciones.countMessages(conversationId)

    /** Los ultimos mensajes del hilo, para la ventana del prompt. */
    open fun recientes(conversationId: Long, limite: Int) =
        conversaciones.recentMessages(conversationId, limite)

    open fun leerEstado(conversationId: Long): String = conversaciones.readState(conversationId)

    open fun guardarEstado(conversationId: Long, estadoJson: String) =
        conversaciones.writeState(conversationId, estadoJson)
}
