package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.TurnResponse
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.conversation.domain.AgentTurn
import com.erp_maya.agent.conversation.service.ConversationService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Entrega a WhatsApp la respuesta de un turno.
 *
 * Renderiza los eventos neutrales, los envia en orden y sella en `messages` el
 * wamid que devuelve Meta. Es el ultimo tramo del circuito: sin el, el agente
 * piensa y contesta pero nadie entrega la respuesta.
 */
@Singleton
open class WhatsAppSender(
    private val renderer: WhatsAppRenderer,
    private val cliente: WhatsAppCloudClient,
    private val conversaciones: ConversationService,
) {

    /**
     * @param cuenta la del canal, con el prefijo `wa:` tal como vive en
     * `channels.account_ref`.
     * @return los wamids de lo enviado, en orden.
     */
    open fun entregar(cuenta: String, destinatario: String, respuesta: TurnResponse, capacidades: Capabilities): List<String> {
        val phoneNumberId = cuenta.removePrefix(PREFIJO)
        require(phoneNumberId != cuenta) { "La cuenta '$cuenta' no es de WhatsApp" }

        val salientes = renderer.render(respuesta.events, capacidades)
        val wamids = salientes.map { cliente.send(phoneNumberId, destinatario, it) }

        // Se sella el primero: `messages` guarda el saliente del turno como
        // una sola fila aunque el renderer lo haya partido en varios envios.
        val turnId = AgentTurn.fromPublicId(respuesta.turnId)
        if (turnId != null && wamids.isNotEmpty()) {
            conversaciones.sellarSaliente(turnId, wamids.first())
        }
        log.info("turno {} entregado a WhatsApp en {} mensaje(s)", respuesta.turnId, wamids.size)
        return wamids
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppSender::class.java)
        const val PREFIJO = "wa:"
    }
}
