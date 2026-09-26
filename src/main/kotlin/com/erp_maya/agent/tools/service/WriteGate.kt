package com.erp_maya.agent.tools.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.tools.domain.PendingWrite
import com.erp_maya.agent.tools.domain.ToolCall
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.security.MessageDigest

/** Veredicto de la compuerta. Solo [Allow] deja pasar. */
sealed interface GateDecision {
    data object Allow : GateDecision

    /** No se ejecuta y la conversacion pasa a una persona. */
    data class Escalate(val reason: Reason, val message: String) : GateDecision

    enum class Reason {
        NOT_GRANTED,
        NO_PREVIEW,
        PREVIEW_SAME_TURN,
        TOTAL_CHANGED,
        ARGS_CHANGED,
        NOT_CONFIRMED,
        OVER_LIMIT,
        NO_LIMIT_SET,
    }
}

/**
 * Lo que la compuerta necesita saber del turno para decidir.
 *
 * `confirmed` no lo interpreta la compuerta: se lo dan ya resuelto. Adivinar
 * un "si" a partir de texto libre es justo donde una compuerta de seguridad no
 * debe improvisar.
 */
data class WriteContext(
    val currentTurn: Int,
    val pending: PendingWrite?,
    val confirmed: Boolean,
)

/**
 * Compuerta obligatoria antes de cualquier herramienta de escritura.
 *
 * Son cuatro condiciones y se exigen TODAS. Cada una tapa una forma distinta
 * de emitir un documento que el cliente nunca acepto:
 *
 *  1. el agente tiene concedida la herramienta;
 *  2. hubo un preview, en un turno anterior, con el mismo total y los mismos
 *     argumentos;
 *  3. el cliente confirmo de forma explicita;
 *  4. el monto cabe en el tope, y existe un tope.
 *
 * Falla cerrado: ante cualquier duda escala en vez de ejecutar. Una emision de
 * mas cuesta una nota de credito y la confianza del cliente; un escalamiento
 * de mas cuesta un minuto de una persona.
 */
@Singleton
class WriteGate {

    fun evaluate(contexto: ExecutionContext, call: ToolCall, turno: WriteContext): GateDecision {
        val grant = contexto.toolFor(call.name)
            ?: return escalar(GateDecision.Reason.NOT_GRANTED, "El agente no tiene concedida '${call.name}'")

        // Las lecturas no pasan por aqui; si llegan, se dejan pasar sin mas.
        if (grant.mode != ToolMode.WRITE) return GateDecision.Allow

        val total = call.decimalArg("total")
            ?: return escalar(GateDecision.Reason.NO_PREVIEW, "La escritura no declara total")

        // 1. Tiene que haber un preview previo para esta misma herramienta.
        val pendiente = turno.pending
            ?: return escalar(GateDecision.Reason.NO_PREVIEW, "No se mostro la previsualizacion antes de emitir")
        if (pendiente.tool != call.name) {
            return escalar(GateDecision.Reason.NO_PREVIEW, "La previsualizacion era de otra operacion")
        }

        // 2. En un turno ANTERIOR. Mostrar y emitir en el mismo turno no deja
        //    al cliente ninguna oportunidad real de decir que no.
        if (pendiente.previewTurn >= turno.currentTurn) {
            return escalar(
                GateDecision.Reason.PREVIEW_SAME_TURN,
                "La previsualizacion y la emision ocurren en el mismo turno",
            )
        }

        // 3. Con el mismo total y los mismos argumentos. Si algo cambio tras
        //    mostrarlo, lo confirmado ya no es lo que se va a emitir.
        if (pendiente.total.compareTo(total) != 0) {
            return escalar(
                GateDecision.Reason.TOTAL_CHANGED,
                "El total cambio despues de mostrarlo: ${pendiente.total} vs $total",
            )
        }
        if (pendiente.argsHash != hashDe(call)) {
            return escalar(GateDecision.Reason.ARGS_CHANGED, "Los datos cambiaron despues de la previsualizacion")
        }

        // 4. Confirmacion explicita.
        if (!turno.confirmed && !grant.autoApprove) {
            return escalar(GateDecision.Reason.NOT_CONFIRMED, "El cliente no confirmo la operacion")
        }

        // 5. El monto cabe en el tope. Sin tope definido NO se ejecuta: que
        //    nadie lo haya configurado no es permiso ilimitado.
        val tope = contexto.limitFor(grant)
            ?: return escalar(
                GateDecision.Reason.NO_LIMIT_SET,
                "No hay tope configurado para '${call.name}' ni para la empresa",
            )
        if (total > tope) {
            return escalar(GateDecision.Reason.OVER_LIMIT, "El total $total supera el tope $tope")
        }

        return GateDecision.Allow
    }

    /**
     * Huella de los argumentos que definen la operacion, sin el total —que se
     * compara aparte— para que el mensaje del rechazo pueda ser especifico.
     */
    fun hashDe(call: ToolCall): String {
        val normalizado = call.arguments
            .filterKeys { it != "total" }
            .toSortedMap()
            .entries
            .joinToString("|") { "${it.key}=${it.value}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizado.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Crea el pendiente que la compuerta exigira en el turno siguiente. */
    fun pendingFrom(tool: String, total: BigDecimal, turno: Int, call: ToolCall) =
        PendingWrite(tool = tool, total = total, previewTurn = turno, argsHash = hashDe(call))

    private fun escalar(reason: GateDecision.Reason, mensaje: String) = GateDecision.Escalate(reason, mensaje)
}
