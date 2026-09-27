package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.channel.whatsapp.domain.MensajeEntrante
import com.erp_maya.agent.channel.whatsapp.domain.WhatsAppWebhook
import com.erp_maya.agent.channel.whatsapp.repository.InboundEventRepository
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.service.ConversationService
import io.micronaut.context.annotation.Value
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Registra y encola lo que llega del webhook.
 *
 * Aqui no se llama al modelo: solo se guarda. Todo lo caro ocurre en el
 * consumidor. Pero el mensaje SI queda en `messages` desde ya, y no recien
 * cuando se abre su turno: si el turno agotara los reintentos de la cola, el
 * mensaje igual tiene que aparecer en el historial de la conversacion.
 */
@Singleton
open class WhatsAppInboundService(
    private val parser: WhatsAppParser,
    private val cola: InboundEventRepository,
    private val resolver: ExecutionContextResolver,
    private val conversaciones: ConversationService,
    private val json: ObjectMapper,
    @param:Value("\${whatsapp.burst-window-seconds:3}") private val ventanaRafaga: Long,
) {

    open fun encolar(cuerpoCrudo: ByteArray): Int {
        val webhook = json.readValue(cuerpoCrudo, WhatsAppWebhook::class.java) ?: return 0
        val mensajes = parser.extraer(webhook)
        if (mensajes.isEmpty()) return 0

        var encolados = 0
        for (mensaje in mensajes) {
            val contexto = contextoDe(mensaje) ?: continue

            // Primero el historial, despues la cola. Los dos son idempotentes
            // por wamid: un reintento de Meta no duplica ni uno ni otro.
            val conversacion = conversaciones.abrirConversacion(contexto, mensaje.de)
            conversaciones.registrarEntrante(conversacion.id, null, mensaje.wamid, mensaje.texto)

            val guardado = cola.enqueue(
                channelId = contexto.channel.id,
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
     * El contexto se resuelve aqui para no encolar mensajes de una cuenta que
     * no atendemos. Si no hay agente vigente, se descarta con un aviso:
     * guardarlo solo llenaria la cola de trabajo que nunca va a poder hacerse.
     */
    private fun contextoDe(mensaje: MensajeEntrante): ExecutionContext? = try {
        resolver.resolve(mensaje.cuenta, null)
    } catch (e: AgentUnavailableException) {
        log.warn("mensaje descartado para la cuenta {}: {}", mensaje.cuenta, e.reason)
        null
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppInboundService::class.java)
    }
}
