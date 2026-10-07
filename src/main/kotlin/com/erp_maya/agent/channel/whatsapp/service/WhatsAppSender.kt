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
    private val stickers: WhatsAppStickers,
) {

    /**
     * @param cuenta la del canal, con el prefijo `wa:` tal como vive en
     * `channels.account_ref`.
     * @return los wamids de lo enviado, en orden.
     */
    open fun entregar(cuenta: String, destinatario: String, respuesta: TurnResponse, capacidades: Capabilities): List<String> {
        val phoneNumberId = cuenta.removePrefix(PREFIJO)
        require(phoneNumberId != cuenta) { "La cuenta '$cuenta' no es de WhatsApp" }

        val turnId = AgentTurn.fromPublicId(respuesta.turnId)
        val salientes = renderer.render(respuesta.events, capacidades)

        // El sticker lo decidio el modelo; el saludo va antes del texto y el
        // "listo" despues.
        val momento = stickers.momento(respuesta.events)
        if (momento?.antesDelTexto == true) sticker(phoneNumberId, destinatario, momento)
        val wamids = salientes.map { cliente.send(phoneNumberId, destinatario, it) }
        if (momento != null && !momento.antesDelTexto) sticker(phoneNumberId, destinatario, momento)

        // Se sella el primero: `messages` guarda el saliente del turno como
        // una sola fila aunque el renderer lo haya partido en varios envios.
        // Los stickers no cuentan: son adorno, no la respuesta.
        if (turnId != null && wamids.isNotEmpty()) {
            conversaciones.sellarSaliente(turnId, wamids.first())
        }
        log.info("turno {} entregado a WhatsApp en {} mensaje(s)", respuesta.turnId, wamids.size)
        return wamids
    }

    /** Un sticker que falla no tumba la respuesta: se registra y se sigue. */
    private fun sticker(phoneNumberId: String, destinatario: String, momento: WhatsAppStickers.Momento) {
        try {
            val mediaId = stickers.mediaId(phoneNumberId, momento)
            cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Sticker(mediaId))
        } catch (e: Exception) {
            stickers.olvidar(phoneNumberId, momento)
            log.warn("no se envió el sticker {} a {}: {}", momento, destinatario, e.message)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppSender::class.java)
        const val PREFIJO = "wa:"
    }
}
