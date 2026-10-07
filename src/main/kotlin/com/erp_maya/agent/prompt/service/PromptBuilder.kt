package com.erp_maya.agent.prompt.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.StoredMessage
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.prompt.domain.SessionState
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
    ): ModelPrompt {
        val partes = mutableListOf<PromptMessage>()

        // 1. System: el prompt versionado del agente, mas la politica de la
        //    empresa. Lo mas estable de todo.
        partes += PromptMessage(Role.SYSTEM, sistema(contexto))

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

        // La cotizacion en curso: sin esto el modelo no sabe si ya hay cliente
        // ni que lineas esperan, y volveria a preguntar lo que ya se decidio.
        estado.borrador?.let { b ->
            val lineas = b.pendientes.joinToString("; ") { "${it.quantity.toPlainString()} × ${it.name} (productId ${it.productId})" }
            partes += PromptMessage(
                Role.SYSTEM,
                buildString {
                    append("Cotizacion en curso:\n")
                    append("- cliente: ").append(b.customerName?.let { "$it (customerId ${b.customerId})" } ?: "sin definir").append('\n')
                    append("- documento: ").append(b.quoteNumber ?: "aun no creado").append('\n')
                    if (lineas.isNotEmpty()) append("- lineas en espera de cliente: ").append(lineas)
                },
            )
        }

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
    }

    private fun herramientas(contexto: ExecutionContext): String? {
        if (contexto.tools.isEmpty()) return null
        val listado = contexto.tools.joinToString("\n") { t ->
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
