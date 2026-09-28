package com.erp_maya.agent.quote

import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.prompt.domain.QuoteDraft
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Botones del panel de trabajo del ERP.
 *
 * - add_line {sku, quantity?}      agrega el producto (1 si no viene cantidad).
 * - edit_line {line_id, quantity?} cambia la cantidad; sin cantidad la pide.
 * - send_quote                     enviar a revision: aun no habilitado.
 *
 * Un fallo del ERP no revienta el turno: se contesta con el motivo y el
 * borrador queda como estaba.
 */
@Singleton
open class PanelActionService(private val borradores: QuoteDraftService) {

    open fun ejecutar(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        input: TurnInput,
    ): DraftStep {
        val payload = input.payload.orEmpty()
        return try {
            when (input.actionId) {
                "add_line" -> borradores.agregar(
                    contexto, conversationId, conversationRef, actual,
                    productId = numero(payload["product_id"])?.toLong(),
                    sku = payload["sku"]?.toString(),
                    cantidad = numero(payload["quantity"]) ?: BigDecimal.ONE,
                )
                "edit_line" -> {
                    val linea = numero(payload["line_id"])?.toLong()
                        ?: return sinCambios(actual, "No se indicó la línea a editar.")
                    val cantidad = numero(payload["quantity"])
                        // El panel solo manda la linea: se pide la cantidad y
                        // el siguiente mensaje la resuelve el modelo con
                        // cotizacion.cantidad, que ve el id en este texto.
                        ?: return sinCambios(actual, "¿Qué cantidad quiere para la línea $linea? Escriba 0 para quitarla.")
                    borradores.editarLinea(contexto, conversationId, actual, linea, cantidad)
                }
                "send_quote" -> sinCambios(
                    actual,
                    "Enviar a revisión desde el asistente aún no está habilitado. " +
                        "La cotización ${actual?.quoteNumber ?: ""} quedó como prospecto en el módulo Cotizaciones.",
                )
                else -> sinCambios(actual, "No reconozco la acción '${input.actionId}'.")
            }
        } catch (e: ErpException) {
            log.warn("accion {} fallo: {}", input.actionId, e.message)
            sinCambios(actual, e.message ?: "No se pudo completar la acción en el ERP.")
        } catch (e: IllegalArgumentException) {
            sinCambios(actual, e.message ?: "Datos inválidos para la acción.")
        }
    }

    /** Texto con el que la accion queda en el historial de la conversacion. */
    fun describir(input: TurnInput): String {
        val p = input.payload.orEmpty()
        return when (input.actionId) {
            "add_line" -> "[Panel] Añadir a cotización: ${p["sku"] ?: p["product_id"]}"
            "edit_line" -> "[Panel] Editar línea ${p["line_id"]}"
            "send_quote" -> "[Panel] Enviar cotización"
            else -> "[Panel] ${input.actionId}"
        }
    }

    private fun sinCambios(actual: QuoteDraft?, mensaje: String) =
        DraftStep(borrador = actual, mensaje = mensaje)

    private fun numero(v: Any?): BigDecimal? = when (v) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull()
        else -> null
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PanelActionService::class.java)
    }
}
