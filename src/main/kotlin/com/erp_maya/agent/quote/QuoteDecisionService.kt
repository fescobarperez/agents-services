package com.erp_maya.agent.quote

import com.erp_maya.agent.api.dto.Choice
import com.erp_maya.agent.api.service.PanelCards
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.playbook.domain.PlantillaDecision
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.prompt.domain.QuoteDraft
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * La decision del cliente sobre la cotizacion que le envio el asesor.
 *
 * Los botones de WhatsApp llevan la accion, la cotizacion y la VERSION:
 * `cot:<accion>:<quoteId>:<version>`. Se resuelven aqui, sin pasar por el
 * modelo: aprobar o rechazar un documento comercial no puede depender de
 * como interprete un texto. Aprobar y rechazar piden confirmacion con un
 * segundo boton; el motivo de rechazo nunca se exige.
 */
@Singleton
open class QuoteDecisionService(
    private val erp: ErpClient,
    private val playbooks: PlaybookService,
) {

    /** Los tres botones de la decision sobre esa version. */
    fun botones(quoteId: Long, version: Int): List<Choice> = listOf(
        Choice(id(APROBAR, quoteId, version), "✅ Aprobar"),
        Choice(id(COMENTAR, quoteId, version), "💬 Comentarios"),
        Choice(id(RECHAZAR, quoteId, version), "❌ Rechazar"),
    )

    open fun ejecutar(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, boton: String): DraftStep {
        val partes = boton.split(':')
        val accion = partes.getOrNull(1)
        val quoteId = partes.getOrNull(2)?.toLongOrNull()
        val version = partes.getOrNull(3)?.toIntOrNull()
        if (accion == null || quoteId == null || version == null) return texto(actual, "No reconozco esa opción.")

        val t = playbooks.para(contexto).mensajes.decision
        val q = try {
            erp.getQuote(contexto.tenantId, conversationId, quoteId)
        } catch (e: ErpException) {
            return texto(actual, "No pude consultar la cotización en este momento. Intenta de nuevo en unos minutos.")
        } ?: return texto(actual, "No encontré esa cotización.")
        if (actual?.customerId != null && q.clientId != null && q.clientId != actual.customerId) {
            return texto(actual, "Esa cotización no es tuya.")
        }

        // Ya decidida, o sin decision pendiente.
        if (!q.status.equals(ENVIADA, ignoreCase = true)) return texto(actual, estadoActual(q))
        // Boton de una version anterior: no se decide nada, se ofrece la vigente.
        if (q.sentVersion != version) {
            return DraftStep(actual, llenar(t.versionAnterior, q), opciones = botones(q.id, q.sentVersion))
        }

        return try {
            when (accion) {
                APROBAR -> if (vencida(q)) {
                    avisarVencida(contexto, conversationId, q)
                    texto(actual, llenar(t.vencida, q))
                } else {
                    DraftStep(
                        actual, llenar(t.confirmarAprobar, q),
                        opciones = listOf(
                            Choice(id(CONFIRMAR_APROBAR, q.id, version), "Sí, apruebo"),
                            Choice(id(CANCELAR, q.id, version), "No"),
                        ),
                    )
                }
                CONFIRMAR_APROBAR -> {
                    erp.clientApprove(contexto.tenantId, conversationId, q.id, version)
                    log.info("cotizacion {} v{} aprobada por el cliente", q.docNumber, version)
                    texto(actual, llenar(t.aprobada, q))
                }
                RECHAZAR -> DraftStep(
                    actual, llenar(t.confirmarRechazar, q),
                    opciones = listOf(
                        Choice(id(CONFIRMAR_RECHAZAR, q.id, version), "Sí, rechazar"),
                        Choice(id(CANCELAR, q.id, version), "No"),
                    ),
                )
                CONFIRMAR_RECHAZAR -> {
                    erp.clientReject(contexto.tenantId, conversationId, q.id, version, null, null)
                    log.info("cotizacion {} v{} rechazada por el cliente", q.docNumber, version)
                    texto(actual, llenar(t.rechazada, q))
                }
                COMENTAR -> texto(actual, llenar(t.comentar, q))
                CANCELAR -> DraftStep(actual, llenar(t.cancelar, q), opciones = botones(q.id, version))
                else -> texto(actual, "No reconozco esa opción.")
            }
        } catch (e: ErpException) {
            // Entre que vio el boton y lo pulso, el vendedor pudo reenviarla o
            // cambiarla: el ERP lo rechaza y se le explica en vez de fallar.
            log.warn("decision {} sobre la cotizacion {} rechazada por el ERP: {}", accion, q.docNumber, e.message)
            val vigente = runCatching { erp.getQuote(contexto.tenantId, conversationId, q.id) }.getOrNull() ?: q
            if (vigente.status.equals(ENVIADA, ignoreCase = true) && vigente.sentVersion != version) {
                DraftStep(actual, llenar(t.versionAnterior, vigente), opciones = botones(vigente.id, vigente.sentVersion))
            } else {
                texto(actual, e.message?.substringAfter("VERSION_ANTERIOR: ") ?: estadoActual(vigente))
            }
        }
    }

    /**
     * Herramienta del modelo: el cliente aprueba por texto ("si, la apruebo").
     * Igual que el boton: version vigente, vencimiento y confirmacion explicita
     * del cliente en ESTE turno (la decide el codigo, no el modelo).
     */
    open fun aprobarPorTexto(
        contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, quoteId: Long, confirmado: Boolean,
    ): DraftStep {
        val t = playbooks.para(contexto).mensajes.decision
        val q = enviadaDelCliente(contexto, conversationId, actual, quoteId)
        if (vencida(q)) {
            avisarVencida(contexto, conversationId, q)
            return DraftStep(actual, llenar(t.vencida, q), datos = mapOf("vencida" to true,
                "instruccion" to "Dile al cliente exactamente el mensaje. No la apruebes."))
        }
        if (!confirmado) {
            throw ErpException("El cliente aún no confirmó. Pregúntale: \"${llenar(t.confirmarAprobar, q)}\" " +
                "y llama de nuevo cuando responda que sí.")
        }
        erp.clientApprove(contexto.tenantId, conversationId, q.id, q.sentVersion)
        log.info("cotizacion {} v{} aprobada por el cliente (texto)", q.docNumber, q.sentVersion)
        return DraftStep(actual, llenar(t.aprobada, q), datos = mapOf(
            "estado" to "aprobada",
            "instruccion" to "Responde con este mensaje. El proceso de cotización terminó: un asesor lo contactará.",
        ))
    }

    /** Herramienta del modelo: rechazo por texto. El motivo es opcional. */
    open fun rechazarPorTexto(
        contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, quoteId: Long,
        motivo: String?, nota: String?, confirmado: Boolean,
    ): DraftStep {
        val t = playbooks.para(contexto).mensajes.decision
        val q = enviadaDelCliente(contexto, conversationId, actual, quoteId)
        if (!confirmado) {
            throw ErpException("El cliente aún no confirmó. Pregúntale: \"${llenar(t.confirmarRechazar, q)}\" " +
                "y llama de nuevo cuando responda que sí.")
        }
        erp.clientReject(contexto.tenantId, conversationId, q.id, q.sentVersion, motivo?.takeIf { it.isNotBlank() }, nota?.takeIf { it.isNotBlank() })
        log.info("cotizacion {} v{} rechazada por el cliente (texto, motivo={})", q.docNumber, q.sentVersion, motivo)
        val mensaje = if (motivo.isNullOrBlank() && nota.isNullOrBlank()) llenar(t.rechazada, q)
        else "Entendido, cerramos la cotización ${q.docNumber}. Gracias por contarnos el motivo y por considerarnos."
        return DraftStep(actual, mensaje, datos = mapOf("estado" to "rechazada",
            "instruccion" to "Responde con este mensaje. No insistas en el motivo."))
    }

    /** Herramienta del modelo: el motivo que el cliente da DESPUES de rechazar. */
    open fun motivoRechazo(
        contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, quoteId: Long, motivo: String?, nota: String?,
    ): DraftStep {
        if (motivo.isNullOrBlank() && nota.isNullOrBlank()) throw IllegalArgumentException("Indica motivo o nota")
        val q = erp.clientReason(contexto.tenantId, conversationId, quoteId, motivo?.takeIf { it.isNotBlank() }, nota?.takeIf { it.isNotBlank() })
        return DraftStep(actual, "Gracias por contarnos; se lo hacemos saber a tu asesor.",
            datos = mapOf("cotizacion" to q.docNumber, "motivo" to (motivo ?: "otro")))
    }

    private fun enviadaDelCliente(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, quoteId: Long): ErpQuote {
        val q = erp.getQuote(contexto.tenantId, conversationId, quoteId) ?: throw ErpException("La cotización $quoteId no existe")
        if (actual?.customerId != null && q.clientId != null && q.clientId != actual.customerId) {
            throw ErpException("Esa cotización no es de este cliente")
        }
        if (!q.status.equals(ENVIADA, ignoreCase = true)) throw ErpException(estadoActual(q))
        return q
    }

    /** Texto con el que el boton queda en el historial de la conversacion. */
    fun describir(boton: String): String = when (boton.split(':').getOrNull(1)) {
        APROBAR -> "[Botón] Aprobar cotización"
        CONFIRMAR_APROBAR -> "[Botón] Sí, apruebo la cotización"
        RECHAZAR -> "[Botón] Rechazar cotización"
        CONFIRMAR_RECHAZAR -> "[Botón] Sí, rechazo la cotización"
        COMENTAR -> "[Botón] Tengo comentarios"
        CANCELAR -> "[Botón] No"
        else -> "[Botón] $boton"
    }

    private fun estadoActual(q: ErpQuote) = when (q.status?.lowercase()) {
        "aprobada" -> "La cotización ${q.docNumber} ya está aprobada. Un asesor se pondrá en contacto contigo."
        "rechazada" -> "La cotización ${q.docNumber} ya está cerrada."
        "borrador" -> "Tu asesor está preparando una nueva versión de la cotización ${q.docNumber}; te la enviaremos en cuanto esté lista."
        else -> "La cotización ${q.docNumber} está ${q.status}; por ahora no hay nada que decidir."
    }

    private fun vencida(q: ErpQuote): Boolean =
        q.validUntil?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.isBefore(LocalDate.now()) == true

    private fun avisarVencida(contexto: ExecutionContext, conversationId: Long, q: ErpQuote) {
        runCatching {
            erp.addQuoteNote(contexto.tenantId, conversationId, q.id,
                "El cliente quiso aprobarla por WhatsApp pero venció (${q.validUntil}): hay que renovarla y reenviarla")
        }.onFailure { log.warn("no se anotó el vencimiento en {}: {}", q.docNumber, it.message) }
    }

    private fun texto(actual: QuoteDraft?, mensaje: String) = DraftStep(borrador = actual, mensaje = mensaje)

    companion object {
        private val log = LoggerFactory.getLogger(QuoteDecisionService::class.java)
        const val PREFIJO = "cot:"
        const val ENVIADA = "enviada"
        const val APROBAR = "aprobar"
        const val CONFIRMAR_APROBAR = "confirmar_aprobar"
        const val RECHAZAR = "rechazar"
        const val CONFIRMAR_RECHAZAR = "confirmar_rechazar"
        const val COMENTAR = "comentar"
        const val CANCELAR = "cancelar"

        /** ¿Es la respuesta a un boton de decision? `cot:accion:id:version` */
        private val FORMATO = Regex("^cot:[a-z_]+:\\d+:\\d+$")
        fun esBoton(texto: String?): Boolean = texto != null && FORMATO.matches(texto.trim())

        fun id(accion: String, quoteId: Long, version: Int) = "$PREFIJO$accion:$quoteId:$version"

        fun llenar(molde: String, q: ErpQuote): String = llenar(
            molde,
            cliente = q.clientName,
            numero = q.docNumber,
            total = q.total?.let(PanelCards::monto),
            version = q.sentVersion,
        )

        fun llenar(molde: String, cliente: String?, numero: String, total: String?, version: Int): String = molde
            .replace("{cliente}", cliente?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "")
            .replace("{numero}", numero)
            .replace("{total}", total ?: "")
            .replace("{version}", version.toString())
            .replace(" ,", ",").replace(", !", "!").replace("¡Gracias por preferirnos, !", "¡Gracias por preferirnos!")
    }
}
