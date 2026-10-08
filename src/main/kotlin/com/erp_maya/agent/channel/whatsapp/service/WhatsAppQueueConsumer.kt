package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.quote.PanelActionService
import com.erp_maya.agent.quote.QuoteDecisionService
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.ApiClientConfiguration
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.channel.whatsapp.domain.MensajeEntrante
import com.erp_maya.agent.channel.whatsapp.repository.InboundEvent
import com.erp_maya.agent.channel.whatsapp.repository.InboundEventRepository
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.domain.TurnInProgressException
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

/**
 * Consumidor de la cola de WhatsApp: el paso 3 del circuito.
 *
 * Toma los eventos vencidos, agrupa la rafaga de cada remitente en un solo
 * turno y lo ejecuta llamando al servicio del turno EN PROCESO: vive en el
 * mismo servicio, asi que un POST HTTP a su propio endpoint solo sumaria una
 * capa de red y una api-key que custodiar. El endpoint queda para quien llama
 * desde fuera (el chat de Stackline, terceros).
 */
@Singleton
open class WhatsAppQueueConsumer(
    private val cola: InboundEventRepository,
    private val turnos: AgentTurnService,
    private val envio: WhatsAppSender,
    private val resolver: ExecutionContextResolver,
    private val json: ObjectMapper,
    clientes: List<ApiClientConfiguration>,
    @param:Value("\${whatsapp.consumer.enabled:true}") private val habilitado: Boolean,
    @param:Value("\${whatsapp.consumer.batch-size:20}") private val lote: Int,
) {

    /** Identifica a esta instancia en `inbound_events.locked_by`. */
    private val worker = "${nombreHost()}-${UUID.randomUUID().toString().take(8)}"

    /**
     * Con los permisos del adaptador de WhatsApp declarado en configuracion.
     * No necesita su api-key: la llamada no sale del proceso.
     */
    private val caller = CallerCredentials(
        clientId = CLIENTE,
        tenantId = null,
        scopes = clientes.firstOrNull { it.name == CLIENTE }?.scopes?.toSet().orEmpty(),
    )

    @Scheduled(fixedDelay = "\${whatsapp.consumer.interval:1s}", initialDelay = "\${whatsapp.consumer.initial-delay:10s}")
    open fun alTick() {
        if (!habilitado) return
        try {
            procesar()
        } catch (e: Exception) {
            // Un fallo de base no puede matar el planificador: el siguiente
            // tick lo vuelve a intentar.
            log.error("fallo el ciclo del consumidor de WhatsApp", e)
        }
    }

    /** Un ciclo: reclama un lote y lo procesa. Devuelve cuantos turnos corrio. */
    open fun procesar(): Int {
        val eventos = cola.claimBatch(worker, lote)
        if (eventos.isEmpty()) return 0

        val rafagas = eventos
            .groupBy { it.channelId to it.senderRef }
            .values
            .map { grupo -> grupo.sortedBy { it.id } }

        rafagas.forEach { atender(it) }
        return rafagas.size
    }

    private fun atender(grupo: List<InboundEvent>) {
        val mensajes = grupo.mapNotNull { leer(it) }
        if (mensajes.isEmpty()) {
            grupo.forEach { cola.markDiscarded(it.id, "payload ilegible") }
            return
        }

        val texto = mensajes.mapNotNull { it.texto?.takeIf(String::isNotBlank) }.joinToString("\n")
        if (texto.isBlank()) {
            // Imagen, audio o documento: la entrada de media todavia no existe.
            // Se descarta con registro en vez de abrir un turno vacio.
            log.warn("rafaga de {} sin texto (media aun no soportada); se descarta", mensajes.first().de)
            grupo.forEach { cola.markDiscarded(it.id, "sin texto: tipo de mensaje aun no soportado") }
            return
        }

        val primero = mensajes.first()
        val wamids = mensajes.map { it.wamid }
        val peticion = TurnRequest(
            channel = CANAL,
            conversationRef = ConversationRef(externalId = primero.de, account = primero.cuenta),
            input = entrada(mensajes.mapNotNull { it.texto }, texto),
            idempotencyKey = llave(wamids),
        )

        try {
            val respuesta = turnos.handle(peticion, caller, entrantesRegistrados = wamids)
            val contexto = resolver.resolve(primero.cuenta, null)
            envio.entregar(primero.cuenta, primero.de, respuesta, contexto.channel.capabilities, contexto.tenantId)
            grupo.forEach { cola.markDone(it.id) }
        } catch (e: AgentUnavailableException) {
            // Configuracion que falta: reintentar no la va a arreglar.
            log.warn("rafaga de {} descartada: {}", primero.de, e.reason)
            grupo.forEach { cola.markDiscarded(it.id, "sin agente: ${e.reason}") }
        } catch (e: TurnInProgressException) {
            // Otro consumidor tiene el mismo turno en curso: se vuelve a la
            // cola y el reintento recibe la respuesta guardada sin reejecutar.
            grupo.forEach { cola.markFailed(it.id, it.attempts, "turno en curso") }
        } catch (e: Exception) {
            // Reintentar es seguro: la llave de idempotencia hace que el turno
            // ya ejecutado se conteste desde lo guardado y solo se reenvie.
            log.error("fallo la rafaga de {} (intento {})", primero.de, grupo.maxOf { it.attempts }, e)
            val detalle = e.message ?: e.javaClass.simpleName
            grupo.forEach { cola.markFailed(it.id, it.attempts, detalle) }
        }
    }

    private fun leer(evento: InboundEvent): MensajeEntrante? =
        runCatching { json.readValue(evento.payload, MensajeEntrante::class.java) }
            .onFailure { log.warn("evento {} con payload ilegible", evento.id, it) }
            .getOrNull()

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppQueueConsumer::class.java)

        const val CANAL = "whatsapp"
        const val CLIENTE = "whatsapp-adapter"

        /**
         * Un mensaje suelto usa su wamid, como hasta ahora. Una rafaga usa una
         * huella de sus wamids ordenados: el mismo lote reintentado produce la
         * misma llave y no ejecuta dos veces.
         */
        fun llave(wamids: List<String>): String =
            wamids.singleOrNull() ?: ("rafaga:" + sha256(wamids.sorted().joinToString(",")).take(40))

        /**
         * Un boton de decision (`cot:accion:id:version`) llega como el texto
         * del mensaje: se convierte en accion para que lo resuelva el codigo y
         * no el modelo. Si en la misma rafaga hubo texto, gana el ultimo boton.
         */
        internal fun entrada(textos: List<String>, unido: String): TurnInput {
            val boton = textos.lastOrNull { QuoteDecisionService.esBoton(it) }?.trim()
                ?: return TurnInput(type = "text", text = unido)
            if (textos.size > 1) log.info("rafaga con boton {}: se ignora el texto que lo acompaña", boton)
            return TurnInput(
                type = "action",
                actionId = PanelActionService.ACCION_DECISION,
                payload = mapOf("boton" to boton),
            )
        }

        private fun sha256(texto: String): String =
            MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun nombreHost(): String =
            runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("agents")
    }
}
