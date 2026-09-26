package com.erp_maya.agent.summary.service

import com.erp_maya.agent.context.repository.AgentDefinitionRepository
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.prompt.service.PromptBuilder
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Reescribe el resumen de la conversacion con un modelo ligero.
 *
 * Se ejecuta ANTES de armar el prompt del turno, no despues. Dos razones: el
 * turno actual ya aprovecha el resumen fresco, y sobre todo la reescritura
 * ocurre mientras los mensajes viejos siguen dentro de la ventana — si se
 * hiciera al final, en el turno en que el mensaje mas antiguo cae fuera ya se
 * habria perdido antes de resumirlo.
 *
 * Cada [CADA] turnos, con una ventana de [PromptBuilder.VENTANA]: el margen
 * existe para que un turno que falle no deje el resumen sin escribir justo en
 * el limite.
 */
@Singleton
open class ConversationSummarizer(
    private val agentes: AgentDefinitionRepository,
    private val conversaciones: ConversationService,
    private val router: ModelRouter,
) {

    /**
     * Devuelve el estado con el resumen al dia, o el mismo estado si todavia no
     * toca o si no se pudo reescribir.
     *
     * Nunca lanza: un resumen es una mejora, no un requisito. Si el modelo
     * ligero esta caido, el turno sigue con el resumen anterior; tumbar la
     * conversacion por eso seria cambiar una degradacion por una caida.
     */
    open fun refrescarSiHaceFalta(conversationId: Long, estado: SessionState): SessionState {
        if (!toca(estado)) return estado

        return try {
            val resumen = reescribir(conversationId, estado) ?: return estado
            log.info(
                "resumen de la conversacion {} reescrito en la version {}",
                conversationId, estado.summaryVersion + 1,
            )
            estado.copy(summary = resumen, summaryVersion = estado.summaryVersion + 1)
        } catch (e: Exception) {
            log.warn("no se pudo reescribir el resumen de {}: {}", conversationId, e.javaClass.simpleName)
            log.debug("detalle del fallo al resumir", e)
            estado
        }
    }

    private fun toca(estado: SessionState) = estado.turn > 0 && estado.turn % CADA == 0

    private fun reescribir(conversationId: Long, estado: SessionState): String? {
        val agente = agentes.byCode(CODIGO_AGENTE)
        if (agente == null) {
            // Sin agente de resumen no se improvisa con el de ventas: ese lleva
            // herramientas y otro prompt, y resumir con el saldria caro y mal.
            log.warn("no hay agente '{}' activo; la conversacion seguira sin resumen", CODIGO_AGENTE)
            return null
        }

        val mensajes = conversaciones.recientes(conversationId, VENTANA_RESUMEN)
        if (mensajes.isEmpty()) return null

        val transcripcion = mensajes.joinToString("\n") { m ->
            val quien = if (m.direction == Direction.IN) "Cliente" else "Agente"
            "$quien: ${m.body.orEmpty()}"
        }

        val previo = estado.summary?.takeIf { it.isNotBlank() }
            ?.let { "Resumen anterior:\n$it\n\n" }
            .orEmpty()

        val prompt = ModelPrompt(
            listOf(
                PromptMessage(Role.SYSTEM, agente.prompt.body),
                PromptMessage(Role.USER, previo + "Conversacion reciente:\n$transcripcion"),
            ),
        )

        val salida = router.complete(
            principal = agente.model,
            respaldo = agente.fallbackModel,
            opciones = ModelOptions(agente.temperature, agente.responseTimeoutMs),
            prompt = prompt,
        )
        return salida.completion.text?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConversationSummarizer::class.java)

        const val CODIGO_AGENTE = "resumen"

        /** Cada cuantos turnos se reescribe. Menor que la ventana, a proposito. */
        const val CADA = 6

        /** Se resume sobre algo mas que la ventana para no perder el borde. */
        const val VENTANA_RESUMEN = PromptBuilder.VENTANA * 2
    }
}
