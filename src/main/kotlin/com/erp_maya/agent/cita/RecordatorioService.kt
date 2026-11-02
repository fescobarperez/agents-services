package com.erp_maya.agent.cita

import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.playbook.domain.PlantillaRecordatorio
import com.erp_maya.agent.playbook.service.PlaybookService
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Entrega el recordatorio de una cita por el WhatsApp de la clinica.
 *
 * Dentro de las 24 h desde el ultimo mensaje del paciente sale un mensaje con
 * tres botones (confirmar, cambiar fecha, cancelar). Fuera de esa ventana
 * Meta solo acepta plantillas aprobadas: sale la del playbook y, si la
 * clinica no tiene, se contesta 409 para que el ERP reintente (por si el
 * paciente escribe antes) y al final lo marque como fallido.
 *
 * El mensaje queda en el hilo de la conversacion: si el paciente contesta con
 * texto en vez de pulsar un boton, el modelo sabe de que cita se trata.
 */
@Singleton
open class RecordatorioService(
    private val canales: CanalRepository,
    private val resolver: ExecutionContextResolver,
    private val conversaciones: ConversationService,
    private val playbooks: PlaybookService,
    private val cliente: WhatsAppCloudClient,
) {

    open fun entregar(n: RecordatorioRequest): RecordatorioResponse {
        if (n.kind != KIND) {
            throw HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "tipo de aviso desconocido: ${n.kind}")
        }
        val llave = "erp-recordatorio-${n.notificationId}"
        // El ERP reintenta: si ya salio, se confirma sin repetirlo.
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
        val m = playbooks.para(contexto).mensajes.recordatorioCita
        val valores = valores(p)
        val texto = CitaRules.llenar(m.texto, valores)
        val phoneNumberId = cuenta.removePrefix(PREFIJO_CUENTA)

        val ultimo = conversaciones.destino(conversacion.id)?.lastInboundAt
        val dentroDeVentana = ultimo != null && ultimo.isAfter(Instant.now().minus(VENTANA))
        val via = if (dentroDeVentana) {
            val botones = CitaService.botonesRecordatorio(p.appointmentId, m).map { it.id to it.label }
            cliente.send(phoneNumberId, waId, WhatsAppOutbound.Buttons(texto, botones, pie = m.pie))
            "botones"
        } else {
            val plantilla = plantilla(m, valores, p.appointmentId)
                ?: throw HttpStatusException(HttpStatus.CONFLICT, "fuera de la ventana de 24 h y sin plantilla aprobada")
            cliente.send(phoneNumberId, waId, plantilla)
            "plantilla"
        }
        conversaciones.registrarAviso(conversacion.id, llave, texto)
        log.info("recordatorio de la cita {} entregado a {} ({})", p.appointmentId, waId, via)
        return RecordatorioResponse(true, via = via)
    }

    companion object {
        const val KIND = "recordatorio_cita"
        private const val PREFIJO_CUENTA = "wa:"
        private val VENTANA: Duration = Duration.ofHours(24)
        private val log = LoggerFactory.getLogger(RecordatorioService::class.java)

        /** Los textos del ERP ya vienen en hora de Guatemala: se usan tal cual. */
        fun valores(p: RecordatorioPayload): Map<String, String?> = mapOf(
            "paciente" to CitaRules.primerNombre(p.patientName),
            "fecha" to p.date,
            "hora" to p.time,
            "tipo" to p.typeName,
            "profesional" to p.professionalName,
            "clinica" to p.companyName,
            "consultorio" to p.resourceName,
        )

        /** La plantilla de Meta con sus variables en orden y los payloads de sus botones. */
        fun plantilla(m: PlantillaRecordatorio, valores: Map<String, String?>, citaId: Long): WhatsAppOutbound.Template? {
            val p = m.plantilla ?: return null
            return WhatsAppOutbound.Template(
                name = p.nombre,
                language = p.idioma,
                variables = p.variables.map { valores[it].orEmpty() },
                respuestas = if (p.botones) {
                    CitaService.botonesRecordatorio(citaId, m).map { it.id }
                } else emptyList(),
            )
        }
    }
}
