package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.conversation.domain.TurnInProgressException
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * La conversacion del planificador: el visitante escribe y el agente asignado
 * al planificador responde, creando opciones con las herramientas
 * `planificador.*` ([PlannerTools]).
 *
 * Va aparte de [PlannerService] para no cerrar un ciclo: el turno usa las
 * herramientas, y las herramientas usan el nucleo del planificador.
 *
 * La conversacion se abre con la sesion del planificador como `external_ref`,
 * asi cada sesion es una conversacion y las herramientas saben sobre cual
 * trabajar sin que el modelo lo diga.
 */
@Singleton
open class PlannerChatService(
    private val planner: PlannerService,
    private val turnos: AgentTurnService,
    @param:Value("\${planner.rate.messages-per-session:10}") private val porSesion: Int,
    @param:Value("\${planner.rate.messages-per-ip:30}") private val porIp: Int,
    @param:Value("\${planner.rate.messages-per-widget:120}") private val porWidget: Int,
) {

    open fun enviar(llave: String, origin: String?, ip: String, sid: String, body: PlannerMessageIn): PlannerReply {
        val w = planner.widget(llave, origin)
        val s = planner.sesion(w, sid)
        val id = body.id?.takeIf { ID.matches(it) } ?: throw WebException(400, "MESSAGE_ID", "Mensaje sin id")
        val texto = body.text?.trim().orEmpty()
        if (texto.isEmpty()) throw WebException(400, "EMPTY", "Escribe tu idea")
        if (texto.length > PlannerService.LARGO_MENSAJE) throw WebException(400, "TOO_LONG", "Máximo ${PlannerService.LARGO_MENSAJE} caracteres")
        // Del mas fino al mas grueso: la sesion que insiste, la IP con muchas
        // sesiones y, como tope de gasto del modelo, el planificador entero.
        planner.limitar("pm:${s.id}", porSesion, "Vas muy rápido.")
        planner.limitar("pm:ip:$ip", porIp, "Vas muy rápido.")
        planner.limitar("pm:w:$llave", porWidget, "Hay mucha actividad.")

        val peticion = TurnRequest(
            channel = PlannerTools.CANAL,
            conversationRef = ConversationRef(externalId = s.id, account = PlannerService.cuenta(llave)),
            input = TurnInput(type = "text", text = texto),
            idempotencyKey = "planner:${s.id}:$id",
        )
        // La empresa sale del planificador, no de lo que mande el navegador.
        val caller = CallerCredentials(clientId = CLIENTE, tenantId = w.tenantId, scopes = emptySet())
        val respuesta = try {
            turnos.handle(peticion, caller)
        } catch (e: AgentUnavailableException) {
            log.warn("planificador {} sin agente: {}", llave, e.reason)
            throw WebException(409, "UNAVAILABLE", "El asistente no está disponible en este momento. Puedes seguir armando tus opciones.")
        } catch (e: TurnInProgressException) {
            throw WebException(409, "BUSY", "Estoy respondiendo tu mensaje anterior.")
        }
        planner.tocar(w, s)

        val textos = respuesta.events.filterIsInstance<AgentEvent.Text>().map { it.text.trim() }.filter { it.isNotEmpty() }
        val respuestas = respuesta.events.filterIsInstance<AgentEvent.Card>()
            .lastOrNull { it.card == PlannerTools.CARD_RESPUESTAS }
            ?.let { PlannerTools.respuestas(it.data) }.orEmpty()
        return PlannerReply(textos, respuestas, planner.estado(w, planner.sesion(w, sid)))
    }

    companion object {
        const val CLIENTE = "planner-widget"
        val ID = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val log = LoggerFactory.getLogger(PlannerChatService::class.java)
    }
}
