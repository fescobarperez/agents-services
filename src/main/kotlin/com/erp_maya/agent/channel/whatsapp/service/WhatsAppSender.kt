package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.TurnResponse
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.conversation.domain.AgentTurn
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.erp.client.ErpClient
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
    private val erp: ErpClient,
) {

    /**
     * @param cuenta la del canal, con el prefijo `wa:` tal como vive en
     * `channels.account_ref`.
     * @param tenantId la empresa, para bajar del ERP los documentos del turno.
     * @return los wamids de lo enviado, en orden.
     */
    open fun entregar(
        cuenta: String,
        destinatario: String,
        respuesta: TurnResponse,
        capacidades: Capabilities,
        tenantId: Long,
    ): List<String> {
        val phoneNumberId = cuenta.removePrefix(PREFIJO)
        require(phoneNumberId != cuenta) { "La cuenta '$cuenta' no es de WhatsApp" }

        val turnId = AgentTurn.fromPublicId(respuesta.turnId)
        val salientes = renderer.render(respuesta.events, capacidades)

        // El sticker lo decidio el modelo; el saludo va antes del texto y el
        // "listo" despues.
        val momento = stickers.momento(respuesta.events)
        if (momento?.antesDelTexto == true) sticker(phoneNumberId, destinatario, momento)
        val conversationId = respuesta.conversationId.removePrefix("cnv_").toLongOrNull() ?: 0L
        val wamids = salientes.map { saliente ->
            if (saliente is WhatsAppOutbound.Document && saliente.url.startsWith(RUTA_COTIZACIONES)) {
                documentoDelErp(phoneNumberId, destinatario, saliente, tenantId, conversationId)
            } else {
                cliente.send(phoneNumberId, destinatario, saliente)
            }
        }
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

    /**
     * El PDF de la cotizacion: se baja del ERP con el bot, se sube a Meta y se
     * manda como documento. Si algo falla se avisa al cliente con un texto en
     * vez de reventar: un reintento de la cola reenviaria todo el turno.
     */
    private fun documentoDelErp(
        phoneNumberId: String,
        destinatario: String,
        doc: WhatsAppOutbound.Document,
        tenantId: Long,
        conversationId: Long,
    ): String {
        val quoteId = ID_COTIZACION.find(doc.url)?.groupValues?.get(1)?.toLongOrNull()
        return try {
            requireNotNull(quoteId) { "ruta de documento inesperada: ${doc.url}" }
            val pdf = erp.getQuotePdf(tenantId, conversationId, quoteId)
                ?: error("el ERP no encontró la cotización $quoteId")
            val mediaId = cliente.subirMedia(phoneNumberId, pdf, "application/pdf", doc.filename)
            val wamid = cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.DocumentMedia(mediaId, doc.filename, LEYENDA))
            // La bitacora es lo que ve el vendedor al revisarla: que sepa que
            // el cliente ya tiene la version preliminar.
            runCatching { erp.addQuoteNote(tenantId, conversationId, quoteId, "PDF preliminar enviado al cliente por WhatsApp") }
                .onFailure { log.warn("no se anotó el envío en la cotización {}: {}", quoteId, it.message) }
            log.info("PDF de la cotización {} enviado por WhatsApp a {}", quoteId, destinatario)
            wamid
        } catch (e: Exception) {
            log.error("no se pudo enviar el PDF de la cotización {} a {}", quoteId, destinatario, e)
            cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Text(AVISO_SIN_PDF))
        }
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
        const val RUTA_COTIZACIONES = "/api/quotes/"
        val ID_COTIZACION = Regex("^/api/quotes/(\\d+)/pdf$")
        const val LEYENDA = "Cotización preliminar: un agente de ventas la revisará y aprobará antes de confirmarla."
        const val AVISO_SIN_PDF = "No pude adjuntar el PDF de la cotización en este momento. " +
            "Un agente de ventas se lo hará llegar al revisarla."
    }
}
