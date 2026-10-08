package com.erp_maya.agent.notification

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppSender
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.playbook.domain.PlantillaCambios
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.prompt.domain.AvisoCambios
import com.erp_maya.agent.prompt.domain.AvisoItem
import com.erp_maya.agent.quote.QuoteDecisionService
import io.micronaut.http.HttpStatus
import io.micronaut.serde.ObjectMapper
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Avisos al cliente que dispara el ERP fuera de una conversacion: hoy, el
 * resultado de sus solicitudes de cambio cuando el vendedor pulsa «Aplicar».
 *
 * El mensaje es de formato fijo: las cifras y el detalle vienen armados del
 * ERP y aqui solo se acomodan en la plantilla del playbook. El modelo no
 * participa: lo que se le dice al cliente es exactamente lo que se aplico.
 */
@Singleton
open class QuoteNotificationService(
    private val conversaciones: ConversationService,
    private val resolver: ExecutionContextResolver,
    private val playbooks: PlaybookService,
    private val whatsapp: WhatsAppSender,
    private val json: ObjectMapper,
    private val decisiones: QuoteDecisionService,
) {

    open fun entregar(n: QuoteNotificationRequest): QuoteNotificationResponse {
        val llave = "erp-aviso-${n.notificationId}"
        // El ERP reintenta: si ya se entrego, se confirma sin repetir el mensaje.
        if (conversaciones.yaRegistrado(llave)) return QuoteNotificationResponse(true, "ya entregado")

        val conversationId = n.conversationRef.removePrefix("cnv_").toLongOrNull()
            ?: throw HttpStatusException(HttpStatus.BAD_REQUEST, "conversation_ref invalida: ${n.conversationRef}")
        val destino = conversaciones.destino(conversationId)
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "La conversacion ${n.conversationRef} no existe")
        if (destino.tenantId != n.tenantId) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "La conversacion no es de esa empresa")
        }
        if (!destino.channelKind.equals(CANAL_WHATSAPP, ignoreCase = true)) {
            // El widget del ERP no tiene a quien avisar: el vendedor ya lo ve.
            return QuoteNotificationResponse(false, "el canal ${destino.channelKind} no recibe avisos")
        }
        // Fuera de la ventana de 24 h Meta solo acepta plantillas aprobadas.
        // 409: el ERP reintenta con espera y, si el cliente escribe antes, sale.
        val ultimo = destino.lastInboundAt
        if (ultimo == null || ultimo.isBefore(Instant.now().minus(VENTANA))) {
            throw HttpStatusException(HttpStatus.CONFLICT, "fuera de la ventana de 24 h (falta plantilla aprobada)")
        }

        val contexto = resolver.resolve(destino.accountRef, destino.tenantId)
        val mensajes = playbooks.para(contexto).mensajes
        val documento = n.payload.pdfPath?.let {
            WhatsAppOutbound.Document(url = it, filename = "Cotizacion-${n.payload.docNumber}.pdf")
        }

        val texto = when (n.kind) {
            KIND_ENVIADA -> {
                val e = mensajes.cotizacionEnviada
                val t = componerEnviada(if (n.payload.resend) e.reenvio else e.texto, n.payload)
                whatsapp.avisar(
                    destino.accountRef, destino.externalRef, t, documento, destino.tenantId, conversationId,
                    leyenda = mensajes.cotizacionEnviada.leyendaPdf,
                    nota = if (n.payload.resend) "PDF reenviado al cliente por WhatsApp" else "PDF oficial enviado al cliente por WhatsApp",
                    botones = botonesDecision(mensajes.decision.pregunta, n.payload),
                )
                t
            }
            KIND_CAMBIOS -> {
                // Cambios en las lineas de una enviada: vuelve a borrador y el
                // cierre anuncia la version nueva (sin PDF: aun no existe).
                val plantilla = if (n.payload.newVersionPending) {
                    mensajes.cambiosAplicados.copy(cierre = mensajes.decision.nuevaVersion)
                } else mensajes.cambiosAplicados
                val t = componer(plantilla, n.payload)
                whatsapp.avisar(
                    destino.accountRef, destino.externalRef, t, documento, destino.tenantId, conversationId,
                    botones = if (n.payload.askDecision && !n.payload.newVersionPending) {
                        botonesDecision(mensajes.decision.pregunta, n.payload)
                    } else null,
                )
                recordarAviso(conversationId, n.payload)
                t
            }
            // Un tipo que este servicio no conoce no se manda a medias: 422 y el
            // ERP lo deja como fallido en la bitacora tras sus reintentos.
            else -> throw HttpStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "tipo de aviso desconocido: ${n.kind}")
        }
        conversaciones.registrarAviso(conversationId, llave, texto)
        log.info("aviso {} de la cotizacion {} entregado a {}", n.notificationId, n.payload.docNumber, n.conversationRef)
        return QuoteNotificationResponse(true)
    }

    /** Pregunta y los tres botones atados a la version enviada. */
    private fun botonesDecision(pregunta: String, p: ChangesAppliedPayload): WhatsAppOutbound.Buttons? {
        if (p.version <= 0) {
            log.warn("aviso de {} sin version enviada (version={}): no se muestran botones", p.docNumber, p.version)
            return null
        }
        val cuerpo = QuoteDecisionService.llenar(pregunta, p.clientName, p.docNumber, p.total, p.version)
        return WhatsAppOutbound.Buttons(cuerpo, decisiones.botones(p.quoteId, p.version).map { it.id to it.label })
    }

    /** Deja el aviso en el estado del hilo, con los ids, para el siguiente turno. */
    private fun recordarAviso(conversationId: Long, p: ChangesAppliedPayload) {
        val aviso = AvisoCambios(
            quoteId = p.quoteId,
            docNumber = p.docNumber,
            enviadoMs = Instant.now().toEpochMilli(),
            items = p.results.mapNotNull { r ->
                r.requestId?.let { AvisoItem(it, r.status, r.detail ?: r.requested ?: r.kind, r.response) }
            },
        )
        runCatching { conversaciones.mezclarEstado(conversationId, json.writeValueAsString(mapOf("avisoReciente" to aviso))) }
            .onFailure { log.warn("no se guardo el aviso en el estado de {}: {}", conversationId, it.message) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(QuoteNotificationService::class.java)
        private const val CANAL_WHATSAPP = "whatsapp"
        const val KIND_CAMBIOS = "cambios_aplicados"
        const val KIND_ENVIADA = "cotizacion_enviada"
        private val VENTANA: Duration = Duration.ofHours(24)

        /** El mensaje de formato fijo. Publico para probarlo sin infraestructura. */
        fun componer(p: PlantillaCambios, datos: ChangesAppliedPayload): String {
            val base = mapOf(
                "cliente" to (datos.clientName?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: ""),
                "numero" to datos.docNumber,
                "total" to (datos.total ?: ""),
            )
            val lineas = datos.results.map { r ->
                val molde = when (r.status) {
                    "aplicada" -> p.aplicada
                    "ajustada" -> p.ajustada
                    "rechazada" -> p.rechazada
                    else -> p.respondida
                }
                llenar(molde, base + mapOf("detalle" to (r.detail ?: r.requested ?: ""), "respuesta" to (r.response ?: "")))
                    .trimEnd(' ', '—', ':', '-')
            }
            return (listOf(llenar(p.encabezado, base).replace(" ,", ",")) + lineas + listOf(llenar(p.cierre, base)))
                .joinToString("\n")
        }

        /** Aviso de cotizacion enviada: un solo texto con {cliente}, {numero}, {total}. */
        fun componerEnviada(molde: String, datos: ChangesAppliedPayload): String =
            llenar(
                molde,
                mapOf(
                    "cliente" to (datos.clientName?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: ""),
                    "numero" to datos.docNumber,
                    "total" to (datos.total ?: ""),
                ),
            ).replace(" ,", ",")

        private fun llenar(molde: String, valores: Map<String, String>): String =
            valores.entries.fold(molde) { acc, (k, v) -> acc.replace("{$k}", v) }
    }
}
