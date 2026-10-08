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

        // El ultimo aviso del asesor, con ids: si el cliente pregunta "¿por que?"
        // casi siempre es sobre esto, y el modelo no debe adivinar a cual.
        estado.avisoReciente
            ?.takeIf { System.currentTimeMillis() - it.enviadoMs < AVISO_VIGENTE_MS }
            ?.let { partes += PromptMessage(Role.SYSTEM, avisoReciente(it)) }

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
        append("Ciclo de una cotizacion: la armas mientras esta abierta; al enviarla pasa a revision y ya no la editas.\n")
        if (c.reapertura.permitida) {
            append("- Si el cliente quiere modificar una que ya envio y ningun asesor la tomo, puedes reabrirla (cotizacion.reabrir)")
            if (c.reapertura.requiereConfirmacion) append(" tras confirmar con el cliente que quiere modificarla")
            append(".\n")
        }
        append("- Si un asesor ya la tomo, no la tocas: registra cada cambio con cotizacion.solicitar_cambio y dile que el asesor le respondera.\n")
        append("- Descuentos, negociacion y cambios sobre cotizaciones en revision siempre van como solicitud; nunca los prometas.\n")
        append("- Si pregunta por que se rechazo o ajusto algo: cotizacion.solicitudes y explica con el motivo y la respuesta del asesor, sin agregar razones propias. Si no le basta, cotizacion.consultar.\n")
        append("Decision del cliente sobre la cotizacion ENVIADA por su asesor (pendiente_decision=true en cotizacion.listar):\n")
        append("- Normalmente decide con los botones Aprobar / Comentarios / Rechazar. Si lo dice por texto, confirma numero y total y usa cotizacion.aprobar o cotizacion.rechazar.\n")
        append("- El motivo del rechazo es opcional: no lo exijas ni insistas. Si lo cuenta despues de rechazar, cotizacion.motivo_rechazo.\n")
        append("- Comentarios u objeciones sobre una enviada van con cotizacion.solicitar_cambio (o tipo otro); el asesor responde.\n")
        append("- Tras aprobarla el proceso de cotizacion termino: agradece y di que un asesor lo contactara; no ofrezcas modificarla.\n")
    }

    private fun avisoReciente(a: com.erp_maya.agent.prompt.domain.AvisoCambios): String = buildString {
        append("Ultimo aviso del asesor al cliente sobre ").append(a.docNumber).append(" [quoteId ").append(a.quoteId).append("]:\n")
        a.items.forEachIndexed { i, item ->
            append("  ").append(i + 1).append(". [solicitudId ").append(item.solicitudId).append("] ")
                .append(item.detalle).append(" — ").append(item.estado)
            item.respuesta?.let { append(" («").append(it).append("»)") }
            append('\n')
        }
        append("Si el cliente pregunta por una de estas, usa su solicitudId: identificala por lo que nombra (producto, ")
        append("descuento, cantidad), no por el orden. Si menciona una sola como \"la ultima\" o \"esa\" y hay mas de una ")
        append("rechazada o ajustada que pueda ser, PREGUNTALE a cual se refiere antes de registrar la consulta.")
    }

    private fun cotizacionEnCurso(estado: SessionState, playbook: Playbook): String = buildString {
        val b = estado.borrador
        b?.ultimaCerrada?.let {
            append("Cotizacion anterior: ").append(it)
            b.ultimaCerradaId?.let { id -> append(" [quoteId ").append(id).append(']') }
            append(". Ya NO la editas con cotizacion.agregar ni cotizacion.cantidad. Si el cliente quiere cambiar ESA ")
            append("cotizacion: cotizacion.reabrir si ningun asesor la tomo, o cotizacion.solicitar_cambio si ya la tiene ")
            append("un asesor (una llamada por cambio). Solo si es otra compra distinta, confirmalo y usa cotizacion.nueva.\n")
        }
        // Hay una cerrada y ninguna abierta: no se muestra checklist. Con el
        // checklist a la vista el modelo "arma una nueva" con lo que el cliente
        // dice, cuando casi siempre quiere cambiar la que ya tiene un asesor.
        if (b?.quoteId == null && b?.ultimaCerradaId != null && b.pendientes.isEmpty()) {
            append("No hay cotizacion abierta. Cliente: ")
            append(b.customerName?.let { "$it (customerId ${b.customerId})" } ?: "sin identificar").append(".\n")
            append("Si el cliente pide productos o cantidades, PRIMERO pregunta si es un cambio a la ")
            append(b.ultimaCerrada?.substringBefore(' ') ?: "cotizacion anterior")
            append(" o una cotizacion nueva. Si es un cambio: cotizacion.solicitar_cambio (quoteId ")
            append(b.ultimaCerradaId).append(") o cotizacion.reabrir si ningun asesor la tomo. ")
            append("Si es nueva: cotizacion.nueva y despues cotizacion.agregar. No pidas direccion ni fecha antes de eso.")
            return@buildString
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
        // Tino decide cuando mandar sticker, pero sin una guia en el prompt
        // la descripcion de la herramienta sola se pierde entre las demas.
        val stickers = if (contexto.toolFor(ToolCatalog.STICKER) != null && ToolCatalog.conStickers(contexto)) {
            "\nStickers (tino.sticker, como maximo uno por respuesta y nunca en mensajes seguidos):\n" +
                "- 'saludo' cuando el cliente saluda o inicia/retoma la conversacion.\n" +
                "- 'listo' cuando envias la cotizacion (cotizacion.enviar) o el cliente la aprueba.\n" +
                "- 'buscando' solo antes de una busqueda larga.\n" +
                "Llamalo junto con las demas herramientas del turno; no lo menciones en el texto."
        } else ""
        return "Herramientas disponibles:\n$listado\n" +
            "Antes de cualquier operacion de escritura muestra un resumen y espera confirmacion explicita." + stickers
    }

    companion object {
        /** Cuantos mensajes previos entran al prompt. */
        const val VENTANA = 8

        /** Un aviso del asesor deja de mostrarse al modelo a los 7 dias. */
        const val AVISO_VIGENTE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
