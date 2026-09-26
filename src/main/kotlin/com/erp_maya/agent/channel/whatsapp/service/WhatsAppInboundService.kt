package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.channel.whatsapp.domain.MensajeEntrante
import com.erp_maya.agent.channel.whatsapp.domain.WhatsAppWebhook
import com.erp_maya.agent.channel.whatsapp.repository.InboundEventRepository
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.service.ExecutionContextResolver
import io.micronaut.context.annotation.Value
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Encola lo que llega del webhook.
 *
 * Aqui no se llama al modelo ni se abre conversacion: solo se guarda. Todo lo
 * caro ocurre en el consumidor.
 */
@Singleton
open class WhatsAppInboundService(
    private val parser: WhatsAppParser,
    private val cola: InboundEventRepository,
    private val resolver: ExecutionContextResolver,
    private val json: ObjectMapper,
    @param:Value("\${whatsapp.burst-window-seconds:3}") private val ventanaRafaga: Long,
) {

    open fun encolar(cuerpoCrudo: ByteArray): Int {
        val webhook = json.readValue(cuerpoCrudo, WhatsAppWebhook::class.java) ?: return 0
        val mensajes = parser.extraer(webhook)
        if (mensajes.isEmpty()) return 0

        var encolados = 0
        for (mensaje in mensajes) {
            val canal = canalDe(mensaje) ?: continue
            val guardado = cola.enqueue(
                channelId = canal,
                externalId = mensaje.wamid,
                senderRef = mensaje.de,
                payload = json.writeValueAsString(mensaje),
                // Se retrasa para agrupar rafagas: quien escribe tres lineas
                // seguidas espera una respuesta, no tres.
                disponibleEn = Instant.now().plusSeconds(ventanaRafaga),
            )
            if (guardado) encolados++ else log.debug("wamid {} repetido; el unico lo descarto", mensaje.wamid)
        }
        return encolados
    }

    /**
     * El canal se resuelve aqui para no encolar mensajes de una cuenta que no
     * atendemos. Si no hay agente vigente, se descarta con un aviso: guardarlo
     * solo llenaria la cola de trabajo que nunca va a poder hacerse.
     */
    private fun canalDe(mensaje: MensajeEntrante): Long? = try {
        resolver.resolve(mensaje.cuenta, null).channel.id
    } catch (e: AgentUnavailableException) {
        log.warn("mensaje descartado para la cuenta {}: {}", mensaje.cuenta, e.reason)
        null
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppInboundService::class.java)
    }
}
