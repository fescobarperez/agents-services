package com.erp_maya.agent.cita

import com.erp_maya.agent.api.dto.Choice
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.SaludFeedbackWrite
import com.erp_maya.agent.playbook.domain.PlantillaControl
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.quote.DraftStep
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Recordatorio de control (Stackline Salud): unos dias antes de la fecha
 * limite se le pregunta al paciente como sigue, con tres botones. Lo que
 * conteste se guarda en el control (dentro de su caso, en el ERP) y se le
 * ofrece agendar; desde ahi sigue el modelo con citas.disponibilidad y
 * citas.agendar (con el followUpId, para que la cita quede en el caso).
 *
 * Misma mecanica que [RecordatorioService]: botones dentro de la ventana de
 * 24 h, plantilla aprobada fuera de ella, 409 si no hay como escribir.
 */
@Singleton
open class ControlService(
    private val canales: CanalRepository,
    private val resolver: ExecutionContextResolver,
    private val conversaciones: ConversationService,
    private val playbooks: PlaybookService,
    private val cliente: WhatsAppCloudClient,
    private val erp: ErpClient,
) {

    open fun entregar(n: ControlRequest): RecordatorioResponse {
        if (n.kind != KIND) {
            throw HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "tipo de aviso desconocido: ${n.kind}")
        }
        val llave = "erp-control-${n.notificationId}"
        if (conversaciones.yaRegistrado(llave)) return RecordatorioResponse(true, "ya entregado")

        val p = n.payload
        val waId = CitaRules.waId(p.phone)
            ?: throw HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "telefono invalido para WhatsApp: ${p.phone}")
        val cuenta = canales.whatsappDe(n.tenantId)
            ?: throw HttpStatusException(HttpStatus.CONFLICT, "la empresa ${n.tenantId} no tiene un WhatsApp conectado")
        val contexto = try {
            resolver.resolve(cuenta, n.tenantId)
        } catch (e: AgentUnavailableException) {
            throw HttpStatusException(HttpStatus.CONFLICT, "sin agente para el WhatsApp de la empresa: ${e.reason}")
        }
        val conversacion = conversaciones.abrirConversacion(contexto, waId)
        val m = playbooks.para(contexto).mensajes.recordatorioControl
        val valores = valores(p)
        val texto = CitaRules.llenar(m.texto, valores)
        val phoneNumberId = cuenta.removePrefix(PREFIJO_CUENTA)

        val ultimo = conversaciones.destino(conversacion.id)?.lastInboundAt
        val dentroDeVentana = ultimo != null && ultimo.isAfter(Instant.now().minus(VENTANA))
        val via = if (dentroDeVentana) {
            cliente.send(phoneNumberId, waId, WhatsAppOutbound.Buttons(texto, botones(p.followUpId, m).map { it.id to it.label }, pie = m.pie))
            "botones"
        } else {
            val plantilla = plantilla(m, valores, p.followUpId)
                ?: throw HttpStatusException(HttpStatus.CONFLICT, "fuera de la ventana de 24 h y sin plantilla aprobada")
            cliente.send(phoneNumberId, waId, plantilla)
            "plantilla"
        }
        conversaciones.registrarAviso(conversacion.id, llave, texto)
        log.info("recordatorio del control {} entregado a {} ({})", p.followUpId, waId, via)
        return RecordatorioResponse(true, via = via)
    }

    /**
     * Un boton `ctl:respuesta:id`. Se guarda sin pasar por el modelo: es lo
     * que el paciente dijo, no algo que interpretar. El ERP comprueba que el
     * control sea de este numero.
     */
    open fun boton(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, boton: String): DraftStep {
        val (respuesta, id) = ControlRules.leerBoton(boton) ?: return DraftStep(actual, "No reconozco esa opción.")
        val m = playbooks.para(contexto).mensajes.recordatorioControl
        val telefono = conversaciones.destino(conversationId)?.externalRef ?: return DraftStep(actual, m.noEsTuyo)
        val r = try {
            erp.saludFollowUpFeedback(contexto.tenantId, conversationId, id, SaludFeedbackWrite(respuesta, telefono))
        } catch (e: ErpException) {
            log.info("respuesta al control {} no registrada: {}", id, e.message)
            return DraftStep(actual, if (e.message?.contains("ya está") == true) m.yaAtendido else m.noEsTuyo)
        }
        val v = mapOf(
            "paciente" to CitaRules.primerNombre(r.patientName),
            "profesional" to (r.professionalName ?: "tu médico"),
            "tipo" to r.typeName,
        )
        val texto = when (respuesta) {
            ControlRules.MEJOR -> m.mejor
            ControlRules.IGUAL -> m.igual
            else -> m.peor
        }
        return DraftStep(actual, limpiar(CitaRules.llenar(texto, v)))
    }

    /** Texto con el que el boton queda en el historial: le dice al modelo como seguir. */
    fun describir(boton: String): String {
        val (respuesta, id) = ControlRules.leerBoton(boton) ?: return "[Botón] $boton"
        return "[Botón] Control #$id: el paciente dice que se siente $respuesta. Si quiere agendar el control, usa " +
            "citas.opciones y citas.disponibilidad con el profesional que se le mencionó, y citas.agendar con followUpId $id."
    }

    companion object {
        const val KIND = "recordatorio_control"
        private const val PREFIJO_CUENTA = "wa:"
        private val VENTANA: Duration = Duration.ofHours(24)
        private val log = LoggerFactory.getLogger(ControlService::class.java)

        fun valores(p: ControlPayload): Map<String, String?> = mapOf(
            "paciente" to CitaRules.primerNombre(p.patientName),
            "fecha" to p.date,
            "profesional" to (p.professionalName ?: "tu médico"),
            "tipo" to p.typeName,
            "clinica" to p.companyName,
        )

        fun botones(followUpId: Long, m: PlantillaControl): List<Choice> = listOf(
            Choice(ControlRules.boton(ControlRules.MEJOR, followUpId), CitaRules.titulo(m.botonMejor)),
            Choice(ControlRules.boton(ControlRules.IGUAL, followUpId), CitaRules.titulo(m.botonIgual)),
            Choice(ControlRules.boton(ControlRules.PEOR, followUpId), CitaRules.titulo(m.botonPeor)),
        )

        fun plantilla(m: PlantillaControl, valores: Map<String, String?>, followUpId: Long): WhatsAppOutbound.Template? {
            val p = m.plantilla ?: return null
            return WhatsAppOutbound.Template(
                name = p.nombre,
                language = p.idioma,
                variables = p.variables.map { valores[it].orEmpty() },
                respuestas = if (p.botones) botones(followUpId, m).map { it.id } else emptyList(),
            )
        }

        /** Sin el nombre (ficha sin nombre) quedarian «, .» sueltos: se arreglan. */
        internal fun limpiar(texto: String): String =
            texto.replace(Regex(",\\s*([.!?])"), "$1").replace(Regex("\\s+([.!?,])"), "$1").trim()
    }
}
