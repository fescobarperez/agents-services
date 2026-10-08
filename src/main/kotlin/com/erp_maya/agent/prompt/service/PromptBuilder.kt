package com.erp_maya.agent.prompt.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.StoredMessage
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.playbook.domain.DatoRequerido
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.quote.QuoteChecklist
import com.erp_maya.agent.tools.service.ToolCatalog
import jakarta.inject.Singleton

/**
 * Arma el prompt de un turno con tamaño constante.
 *
 * Nunca se reenvia la conversacion completa. Entran: system, herramientas,
 * resumen, entidades, los ultimos [VENTANA] mensajes y el mensaje nuevo. El
 * historico entero vive en Postgres para auditoria, no en el prompt: si
 * creciera con la conversacion, el costo por turno crecería con ella y una
 * charla larga acabaria por no caber.
 *
 * El orden va de lo mas estable a lo mas volatil para que el proveedor cachee
 * el prefijo entre turnos.
 */
@Singleton
class PromptBuilder {

    fun build(
        contexto: ExecutionContext,
        estado: SessionState,
        recientes: List<StoredMessage>,
        entrante: String?,
        playbook: Playbook = Playbook(),
    ): ModelPrompt {
        val partes = mutableListOf<PromptMessage>()

        // 1. System: el prompt versionado del agente, mas la politica de la
        //    empresa. Lo mas estable de todo.
        partes += PromptMessage(Role.SYSTEM, sistema(contexto))

        // 1b. El negocio y sus reglas (playbook). Estable por empresa, asi que
        //     va en el prefijo cacheado, antes que todo lo de la conversacion.
        partes += PromptMessage(Role.SYSTEM, negocio(contexto, playbook))

        // 2. Herramientas. Vacio hasta el paso 6, pero ocupa su lugar en el
        //    orden para no mover el prefijo cacheado cuando llegue.
        herramientas(contexto)?.let { partes += PromptMessage(Role.SYSTEM, it) }

        // 3. Resumen de lo que ya paso.
        estado.summary?.takeIf { it.isNotBlank() }?.let {
            partes += PromptMessage(Role.SYSTEM, "Resumen de la conversacion:\n$it")
        }

        // 4. Entidades: los datos duros, que no dependen de que el resumen los
        //    mencione.
        if (estado.entities.isNotEmpty()) {
            val datos = estado.entities.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
            partes += PromptMessage(Role.SYSTEM, "Datos confirmados de este hilo:\n$datos")
        }

        // La cotizacion en curso y su checklist, ya calculados por el codigo:
        // el modelo no deduce del historial si hay una abierta ni que falta.
        partes += PromptMessage(Role.SYSTEM, cotizacionEnCurso(estado, playbook))

        // 5. La ventana de mensajes, del mas viejo al mas nuevo.
        recientes.takeLast(VENTANA).forEach { m ->
            partes += PromptMessage(
                if (m.direction == Direction.IN) Role.USER else Role.ASSISTANT,
                m.body.orEmpty(),
            )
        }

        // 6. El mensaje que dispara este turno.
        entrante?.takeIf { it.isNotBlank() }?.let { partes += PromptMessage(Role.USER, it) }

        return ModelPrompt(partes.filter { !it.content.isNullOrBlank() })
    }

    private fun sistema(contexto: ExecutionContext): String = buildString {
        append(contexto.prompt.body)
        append("\n\nIdioma y formato: responde en ")
        append(contexto.policy.locale)
        contexto.policy.tone?.let { append(", en tono ").append(it) }
        append(".")
        // Las capacidades son parte del prompt porque condicionan la forma de
        // la respuesta: pedir markdown a un canal que no lo pinta produce
        // asteriscos sueltos en la pantalla del cliente.
        if (!contexto.channel.capabilities.markdown) {
            append(" El canal no interpreta markdown: escribe texto plano, sin asteriscos ni guiones de lista.")
        }
        if (contexto.channel.capabilities.buttons > 0) {
            append(" Puedes ofrecer hasta ${contexto.channel.capabilities.buttons} opciones para elegir.")
        }
        append(" Limite de ${contexto.channel.capabilities.maxChars} caracteres por mensaje.")
        // Los montos los calcula el ERP (impuesto, redondeo, descuentos). Un
        // total que el modelo saca de cabeza termina contradiciendo al PDF.
        append(
            " Montos: nunca calcules subtotales, impuestos ni totales. Antes de crear la cotizacion menciona solo " +
                "precio unitario y cantidad; despues repite exactamente las lineas y montos que devuelvan las " +
                "herramientas cotizacion.*.",
        )
    }

    private fun negocio(contexto: ExecutionContext, playbook: Playbook): String = buildString {
        val n = playbook.negocio
        if (n != null) {
            append("El negocio:\n")
            n.descripcion?.let { append("- ").append(it).append('\n') }
            n.entregas?.let { append("- Entregas: ").append(it).append('\n') }
            n.pagos?.let { append("- Formas de pago: ").append(it).append('\n') }
            n.horario?.let { append("- Horario: ").append(it).append('\n') }
            n.notas?.let { append("- ").append(it).append('\n') }
        }
        append("Con quien hablas: ")
        append(
            if (playbook.audiencia(contexto.channel.kind) == Playbook.AUDIENCIA_VENDEDOR) {
                "un vendedor de la empresa. Puedes darle detalle interno (estados, existencias por bodega)."
            } else {
                "un cliente final. No le muestres datos internos (ids, estados del sistema, costos ni existencias " +
                    "exactas de bodega); dile solo si hay disponibilidad."
            },
        ).append('\n')

        val l = playbook.limites
        append("Limites:\n")
        if (!l.puedeOfrecerDescuentos) append("- No ofrezcas ni negocies descuentos; si los piden, un agente de ventas lo atendera.\n")
        l.montoMaximo?.let { append("- Cotizaciones por encima de Q ").append(it.toPlainString()).append(" las revisa un agente de ventas antes de enviarse.\n") }
        l.lineasMaximas?.let { append("- Maximo ").append(it).append(" productos distintos por cotizacion.\n") }
        if (l.fueraDeAlcance.isNotEmpty()) {
            append("- No resuelves: ").append(l.fueraDeAlcance.joinToString(", "))
                .append(". Si lo piden, di que un agente de ventas le dara seguimiento.\n")
        }

        val c = playbook.cotizacion
        append("Para enviar una cotizacion se necesita:\n")
        c.datosRequeridos.forEach { d ->
            append("- ").append(d.etiqueta)
            if (d.opcional) append(" (opcional)")
            if (d.origen != DatoRequerido.ORIGEN_SISTEMA) append(" [cotizacion.dato clave=").append(d.clave).append(']')
            d.descripcion?.let { append(": ").append(it) }
            append('\n')
        }
        if (c.envio.requiereConfirmacion) append("El cliente debe confirmar explicitamente antes de que envies la cotizacion.\n")
    }

    private fun cotizacionEnCurso(estado: SessionState, playbook: Playbook): String = buildString {
        val b = estado.borrador
        b?.ultimaCerrada?.let {
            append("Cotizacion anterior CERRADA: ").append(it)
                .append(". No se modifica; lo que pida el cliente va en una nueva.\n")
        }
        append("Cotizacion en curso:\n")
        val cliente = when {
            b?.customerName != null -> "${b.customerName} (customerId ${b.customerId}); ya identificado, no le pidas sus datos"
            b?.telefonoRevisado == true -> "no registrado con este numero; pide nombre y NIT (CF si no tiene) y registralo con cotizacion.cliente_nuevo"
            else -> "sin definir"
        }
        append("- cliente: ").append(cliente).append('\n')
        append("- documento: ")
        append(
            b?.quoteNumber?.let {
                "$it, abierto. cotizacion.agregar SUMA a este documento: si el cliente pide algo que parece otra " +
                    "compra, preguntale si va en $it o en una cotizacion nueva (cotizacion.nueva)"
            } ?: "aun no creado (la primera linea con cliente lo crea)",
        ).append('\n')
        val pendientes = b?.pendientes.orEmpty()
        if (pendientes.isNotEmpty()) {
            append("- lineas en espera de cliente: ")
            append(pendientes.joinToString("; ") { "${it.quantity.toPlainString()} × ${it.name} (productId ${it.productId})" })
            append('\n')
        }
        append("Checklist:\n")
        QuoteChecklist.calcular(b, playbook).forEach { item ->
            append(if (item.listo) "  [x] " else "  [ ] ").append(item.dato.etiqueta)
            if (item.dato.opcional) append(" (opcional)")
            item.valor?.takeIf { item.dato.origen != DatoRequerido.ORIGEN_SISTEMA }?.let { append(": ").append(it) }
            append('\n')
        }
        val faltan = QuoteChecklist.faltantes(b, playbook)
        append(
            if (faltan.isEmpty()) "Todo lo obligatorio esta completo: puedes ofrecer enviarla."
            else "Siguiente: consigue ${faltan.first().etiqueta.lowercase()}. No ofrezcas enviarla hasta completar el checklist.",
        )
    }

    private fun herramientas(contexto: ExecutionContext): String? {
        val visibles = contexto.tools.filter { it.pattern !in ToolCatalog.SOLO_WHATSAPP || ToolCatalog.conStickers(contexto) }
        if (visibles.isEmpty()) return null
        val listado = visibles.joinToString("\n") { t ->
            val tope = t.maxAmount?.let { " (tope $it)" } ?: ""
            "- ${t.pattern} [${t.mode.name.lowercase()}]$tope"
        }
        return "Herramientas disponibles:\n$listado\n" +
            "Antes de cualquier operacion de escritura muestra un resumen y espera confirmacion explicita."
    }

    companion object {
        /** Cuantos mensajes previos entran al prompt. */
        const val VENTANA = 8
    }
}
