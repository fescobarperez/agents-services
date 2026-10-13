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
     * Aviso fuera de un turno (lo dispara el ERP): un texto y, si viene, el
     * PDF de la cotizacion. Devuelve el wamid del texto.
     */
    open fun avisar(
        cuenta: String,
        destinatario: String,
        texto: String,
        documento: WhatsAppOutbound.Document?,
        tenantId: Long,
        conversationId: Long,
        leyenda: String = "Cotización actualizada por tu asesor.",
        nota: String = "PDF actualizado enviado al cliente por WhatsApp",
        botones: WhatsAppOutbound.Buttons? = null,
    ): String {
        val phoneNumberId = cuenta.removePrefix(PREFIJO)
        require(phoneNumberId != cuenta) { "La cuenta '$cuenta' no es de WhatsApp" }
        val wamid = cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Text(texto))
        documento?.let {
            documentoDelErp(
                phoneNumberId, destinatario, it, tenantId, conversationId,
                leyenda = leyenda,
                nota = nota,
            )
        }
        // Los botones van al final, despues del PDF: es lo ultimo que ve.
        // Si Meta los rechaza no se repite el aviso entero (texto y PDF ya
        // salieron): se registra el motivo para corregirlo.
        botones?.let { b ->
            runCatching { cliente.send(phoneNumberId, destinatario, b) }
                .onSuccess { log.info("botones de decisión enviados a {}: {}", destinatario, b.buttons.map { it.first }) }
                .onFailure { e -> log.error("no se enviaron los botones de decisión a {}: {}", destinatario, e.message) }
        }
        return wamid
    }

    /**
     * Aviso con decision (lo dispara el ERP): UN mensaje con el PDF de la
     * cotizacion como encabezado, el texto, el pie y los botones.
     *
     * `previo` es un texto que va antes cuando el contenido no cabe en el
     * cuerpo del interactivo (1024 caracteres). Si el PDF no se puede subir,
     * los botones salen igual, sin encabezado, y se avisa del PDF. Si Meta
     * rechaza el interactivo se manda texto y PDF sueltos: el aviso no se
     * pierde ni se reintenta entero. Devuelve el wamid del primer mensaje.
     */
    open fun avisarConDecision(
        cuenta: String,
        destinatario: String,
        previo: String?,
        cuerpo: String,
        pie: String?,
        documento: WhatsAppOutbound.Document?,
        botones: List<Pair<String, String>>,
        tenantId: Long,
        conversationId: Long,
        nota: String,
    ): String {
        val phoneNumberId = cuenta.removePrefix(PREFIJO)
        require(phoneNumberId != cuenta) { "La cuenta '$cuenta' no es de WhatsApp" }
        val primero = previo?.let { cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Text(it)) }

        val pdf = documento?.let { pdfDelErp(phoneNumberId, it, tenantId, conversationId) }
        if (documento != null && pdf == null) {
            cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Text(AVISO_SIN_PDF))
        }
        val wamid = try {
            cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Buttons(cuerpo, botones, pdf?.first, pie))
                .also { log.info("aviso con decisión enviado a {}: {}", destinatario, botones.map { it.first }) }
        } catch (e: Exception) {
            log.error("Meta rechazó el aviso con botones para {}: {}; se manda texto y PDF sueltos", destinatario, e.message)
            cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.Text(cuerpo)).also {
                pdf?.let { (doc, _) -> cliente.send(phoneNumberId, destinatario, doc) }
            }
        }
        pdf?.second?.let { quoteId ->
            runCatching { erp.addQuoteNote(tenantId, conversationId, quoteId, nota) }
                .onFailure { log.warn("no se anotó el envío en la cotización {}: {}", quoteId, it.message) }
        }
        return primero ?: wamid
    }

    /** Baja el PDF del ERP y lo sube a Meta. Null si algo falla (se registra). */
    private fun pdfDelErp(
        phoneNumberId: String,
        doc: WhatsAppOutbound.Document,
        tenantId: Long,
        conversationId: Long,
    ): Pair<WhatsAppOutbound.DocumentMedia, Long>? {
        val quoteId = ID_COTIZACION.find(doc.url)?.groupValues?.get(1)?.toLongOrNull()
        return try {
            requireNotNull(quoteId) { "ruta de documento inesperada: ${doc.url}" }
            val bytes = erp.getQuotePdf(tenantId, conversationId, quoteId)
                ?: error("el ERP no encontró la cotización $quoteId")
            val mediaId = cliente.subirMedia(phoneNumberId, bytes, "application/pdf", doc.filename)
            WhatsAppOutbound.DocumentMedia(mediaId, doc.filename, null) to quoteId
        } catch (e: Exception) {
            log.error("no se pudo preparar el PDF de la cotización {}", quoteId, e)
            null
        }
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
        leyenda: String = LEYENDA,
        nota: String = "PDF preliminar enviado al cliente por WhatsApp",
    ): String {
        val quoteId = ID_COTIZACION.find(doc.url)?.groupValues?.get(1)?.toLongOrNull()
        return try {
            requireNotNull(quoteId) { "ruta de documento inesperada: ${doc.url}" }
            val pdf = erp.getQuotePdf(tenantId, conversationId, quoteId)
                ?: error("el ERP no encontró la cotización $quoteId")
            val mediaId = cliente.subirMedia(phoneNumberId, pdf, "application/pdf", doc.filename)
            val wamid = cliente.send(phoneNumberId, destinatario, WhatsAppOutbound.DocumentMedia(mediaId, doc.filename, leyenda))
            // La bitacora es lo que ve el vendedor al revisarla: que sepa que
            // el cliente ya tiene la version preliminar.
            runCatching { erp.addQuoteNote(tenantId, conversationId, quoteId, nota) }
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
