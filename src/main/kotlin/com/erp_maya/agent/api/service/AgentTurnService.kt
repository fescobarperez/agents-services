package com.erp_maya.agent.api.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.TokenUsage
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.dto.TurnResponse
import com.erp_maya.agent.api.dto.TurnState
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.domain.AgentTurn
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.TurnInProgressException
import com.erp_maya.agent.conversation.domain.TurnStatus
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.model.repository.AgentRunRepository
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.tools.service.ConfirmationDetector
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.prompt.service.PromptBuilder
import com.erp_maya.agent.summary.service.ConversationSummarizer
import com.erp_maya.agent.quote.PanelActionService
import com.erp_maya.agent.quote.QuoteDraftService
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Orquesta un turno: credencial → contexto → conversacion → prompt → modelo.
 *
 * No es transaccional a proposito. Cada paso persiste por su cuenta para que
 * un fallo a mitad no borre el rastro de que el turno existio.
 */
@Singleton
open class AgentTurnService(
    private val resolver: ExecutionContextResolver,
    private val conversaciones: ConversationService,
    private val promptBuilder: PromptBuilder,
    private val loop: AgentLoop,
    private val confirmaciones: ConfirmationDetector,
    private val corridas: AgentRunRepository,
    private val resumidor: ConversationSummarizer,
    private val json: ObjectMapper,
    private val acciones: PanelActionService,
    private val borradores: QuoteDraftService,
    private val playbooks: PlaybookService,
) {

    /**
     * @param entrantesRegistrados wamids que ya quedaron en `messages` al
     * llegar por la cola de WhatsApp. Si viene vacio —el widget del ERP o un
     * tercero que llaman al endpoint—, el entrante se registra aqui.
     */
    open fun handle(
        request: TurnRequest,
        caller: CallerCredentials,
        entrantesRegistrados: List<String> = emptyList(),
    ): TurnResponse {
        // La empresa sale de la credencial si la trae; si no, de la cuenta del
        // canal. En ningun caso del cuerpo de la peticion.
        val contexto = resolver.resolve(request.conversationRef.account, caller.tenantId)

        val conversacion = conversaciones.abrirConversacion(contexto, request.conversationRef.externalId)
        val turno = conversaciones.abrirTurno(conversacion.id, request.idempotencyKey)

        // Solo ejecuta quien gano el INSERT de la llave de idempotencia.
        if (!turno.claimedHere) return reenviar(turno)

        conversaciones.enEjecucion(turno.id)
        if (entrantesRegistrados.isEmpty()) {
            // Para el widget del ERP la idempotency_key es un id que genera el
            // cliente e identifica el mensaje entrante.
            conversaciones.registrarEntrante(
                conversacion.id, turno.id, request.idempotencyKey,
                // Una accion del panel no trae texto: se guarda descrita para
                // que el historial (y el modelo en turnos siguientes) la vea.
                request.input.text ?: acciones.describir(request.input),
            )
        } else {
            // WhatsApp: los mensajes ya se guardaron al llegar, uno por wamid.
            // Aqui solo se les asigna el turno que los atiende.
            conversaciones.enlazarEntrantes(conversacion.id, turno.id, entrantesRegistrados)
        }

        return try {
            if (request.input.esAccion) {
                ejecutarAccion(request, contexto, conversacion.id, turno.id)
            } else {
                ejecutar(request, contexto, conversacion.id, turno.id)
            }
        } catch (e: Exception) {
            conversaciones.fallar(turno.id, TurnStatus.FAILED, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun ejecutar(
        request: TurnRequest,
        contexto: ExecutionContext,
        conversationId: Long,
        turnId: Long,
    ): TurnResponse {
        val capacidades = capacidadesEfectivas(request, contexto)

        // El resumen se refresca ANTES de armar el prompt: asi el turno actual
        // ya lo aprovecha y, sobre todo, se reescribe mientras los mensajes
        // viejos siguen dentro de la ventana en vez de despues de perderlos.
        val resumido = resumidor.refrescarSiHaceFalta(conversationId, leerEstado(conversationId))
        // WhatsApp: quien escribe se identifica por su numero (el external_id
        // de la conversacion). Va antes del prompt para que el modelo ya sepa
        // con quien habla y no le pregunte.
        val telefono = request.conversationRef.externalId.takeIf { contexto.channel.kind == CANAL_WHATSAPP }
        val identificado = if (telefono == null) resumido
        else resumido.copy(borrador = borradores.identificarPorTelefono(contexto, conversationId, resumido.borrador, telefono))

        // Si la cotizacion en curso ya no es del agente (enviada, movida por un
        // vendedor, vencida por inactividad) se cierra ANTES de que el modelo
        // la vea: lo que pida el cliente va en una nueva y no se mezcla.
        val playbook = playbooks.para(contexto)
        val estado = identificado.copy(
            borrador = borradores.revisarVigencia(contexto, conversationId, identificado.borrador, playbook),
        )

        // La ventana excluye los entrantes de ESTE turno para no duplicarlos:
        // van aparte, al final, como el disparador. Se filtra por turno y no
        // por texto: una rafaga agrupada llega como varios mensajes y un solo
        // texto unido, y comparar textos dejaria pasar los individuales.
        val recientes = conversaciones.recientes(conversationId, PromptBuilder.VENTANA + LIMITE_RAFAGA)
            .filterNot { it.turnId == turnId && it.direction == Direction.IN }

        val prompt = promptBuilder.build(contexto, estado, recientes, request.input.text, playbook)

        // La confirmacion se decide aqui y se le pasa a la compuerta ya
        // resuelta: interpretar un "si" a partir de texto libre no es algo que
        // deba improvisar el codigo que autoriza una emision.
        val confirmado = confirmaciones.isConfirmation(request.input.text)

        val salida = loop.run(
            contexto = contexto,
            conversationId = conversationId,
            prompt = prompt,
            estado = estado,
            confirmado = confirmado,
            idempotencyKey = request.idempotencyKey,
            telefono = telefono,
            playbook = playbook,
        )

        // Se audita SIEMPRE, con el modelo que de verdad respondio y no con el
        // que decia la configuracion: si entro el respaldo, eso es lo que hay
        // que poder ver despues.
        corridas.record(
            turnId = turnId,
            conversationId = conversationId,
            agentId = contexto.agent.id,
            modelId = salida.modelId,
            promptVersionId = contexto.prompt.id,
            usage = salida.usage,
            toolsCalledJson = salida.toolsCalled.takeIf { it.isNotEmpty() }
                ?.let { json.writeValueAsString(it) },
            latencyMs = salida.latencyMs,
            fallbackUsed = salida.fallbackUsed,
        )

        val texto = recortar(salida.text, capacidades.maxChars)

        val respuesta = TurnResponse(
            conversationId = idConversacion(conversationId),
            turnId = AgentTurn.publicId(turnId),
            // El texto primero y despues las tarjetas: el canal las pinta en ese orden.
            // Los documentos al final: el texto los presenta y luego llegan.
            events = listOf(AgentEvent.Text(texto)) + salida.cards + salida.documents,
            state = TurnState(summaryVersion = estado.summaryVersion),
            usage = TokenUsage(
                input = salida.usage.input,
                cached = salida.usage.cached,
                output = salida.usage.output,
            ),
        )

        conversaciones.registrarSaliente(conversationId, turnId, texto)
        // El pendiente de escritura se persiste con el estado: la compuerta lo
        // exigira en el turno siguiente y tiene que sobrevivir a un reinicio.
        conversaciones.guardarEstado(
            conversationId,
            json.writeValueAsString(
                estado.copy(turn = estado.turn + 1, pendingWrite = salida.pendingWrite, borrador = salida.borrador),
            ),
        )
        conversaciones.completar(
            turnId,
            json.writeValueAsString(respuesta),
        )
        if (salida.escalation != null) {
            conversaciones.fallar(turnId, TurnStatus.ESCALATED, salida.escalation)
        }

        log.info(
            "turno tenant={} conversation={} turn={} modelo={} fallback={} tools={} tokens={}/{} {}ms{}",
            contexto.tenantId, respuesta.conversationId, respuesta.turnId,
            salida.modelName, salida.fallbackUsed, salida.toolsCalled,
            salida.usage.input, salida.usage.output, salida.latencyMs,
            salida.escalation?.let { " ESCALADO: $it" } ?: "",
        )
        return respuesta
    }

    /**
     * Misma llave, misma respuesta. Si el primer turno sigue corriendo no se
     * espera ni se ejecuta de nuevo: ejecutar dos veces significa cobrar los
     * tokens dos veces y, si el turno emite una cotizacion, emitirla dos veces.
     */
    private fun reenviar(turno: AgentTurn): TurnResponse {
        val guardada = turno.response
        if (turno.status != TurnStatus.DONE || guardada == null) {
            throw TurnInProgressException(AgentTurn.publicId(turno.id))
        }
        log.info("turno {} reenviado desde la respuesta guardada", AgentTurn.publicId(turno.id))
        return requireNotNull(json.readValue(guardada, TurnResponse::class.java)) {
            "La respuesta guardada del turno ${turno.id} no se pudo releer"
        }
    }

    /**
     * Un boton del panel. Se resuelve sin el modelo: la intencion ya es
     * exacta (este SKU, esta linea) y pasarla por el modelo solo añadiria
     * latencia, costo y la posibilidad de que la reinterprete.
     */
    private fun ejecutarAccion(
        request: TurnRequest,
        contexto: ExecutionContext,
        conversationId: Long,
        turnId: Long,
    ): TurnResponse {
        val estado = leerEstado(conversationId)
        val paso = acciones.ejecutar(contexto, conversationId, idConversacion(conversationId), estado.borrador, request.input)

        val respuesta = TurnResponse(
            conversationId = idConversacion(conversationId),
            turnId = AgentTurn.publicId(turnId),
            events = listOf(AgentEvent.Text(paso.mensaje)) + paso.tarjetas,
            state = TurnState(summaryVersion = estado.summaryVersion),
        )
        conversaciones.registrarSaliente(conversationId, turnId, paso.mensaje)
        conversaciones.guardarEstado(
            conversationId,
            json.writeValueAsString(estado.copy(turn = estado.turn + 1, borrador = paso.borrador)),
        )
        conversaciones.completar(turnId, json.writeValueAsString(respuesta))
        log.info(
            "accion tenant={} conversation={} turn={} accion={}",
            contexto.tenantId, respuesta.conversationId, respuesta.turnId, request.input.actionId,
        )
        return respuesta
    }

    /** Un estado ilegible no puede tumbar el turno: se arranca de cero. */
    private fun leerEstado(conversationId: Long): SessionState =
        runCatching {
            json.readValue(conversaciones.leerEstado(conversationId), SessionState::class.java)
        }.getOrNull() ?: SessionState()

    /**
     * Lo que el canal declara en el turno gana sobre lo configurado: la version
     * de la app del cliente cambia mas rapido que la fila en `channels`.
     */
    private fun capacidadesEfectivas(request: TurnRequest, contexto: ExecutionContext): Capabilities =
        request.capabilities?.let {
            Capabilities(it.buttons, it.markdown, it.maxChars, it.streaming)
        } ?: contexto.channel.capabilities

    private fun idConversacion(id: Long) = "cnv_%08d".format(id)

    private fun recortar(texto: String, maximo: Int) =
        if (texto.length <= maximo) texto else texto.take(maximo - 1) + "…"

    private companion object {
        private val log = LoggerFactory.getLogger(AgentTurnService::class.java)

        /** Margen de lectura para que una rafaga no le quite lugar a la ventana. */
        const val LIMITE_RAFAGA = 10

        const val CANAL_WHATSAPP = "whatsapp"
    }
}
