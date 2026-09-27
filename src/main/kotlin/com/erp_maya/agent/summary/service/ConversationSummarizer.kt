package com.erp_maya.agent.summary.service

import com.erp_maya.agent.context.repository.AgentDefinitionRepository
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.StoredMessage
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.prompt.service.PromptBuilder
import io.micronaut.serde.ObjectMapper
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Reescribe el resumen de la conversacion con un modelo ligero.
 *
 * Dos disparadores, y ya no "cada N turnos":
 *
 *  1. **Inactividad** (el normal): cuando la conversacion lleva
 *     `agent.summary.inactivity-minutes` sin mensajes, [resumirPorInactividad]
 *     resume de una vez todo lo que falta. Una conversacion que termina en el
 *     mensaje 4 tambien queda resumida, y no se itera el resumen a cada rato.
 *
 *  2. **Respaldo por ventana**: en una conversacion larga y continua la
 *     inactividad nunca llega, y los mensajes viejos saldrian de la ventana
 *     del prompt sin haberse resumido. [refrescarSiHaceFalta] corre al inicio
 *     de cada turno y resume justo antes de que eso pase.
 *
 * Lo que falta resumir se mide por mensajes (`SessionState.summarizedThrough`),
 * no por turnos: la ventana tambien se mide en mensajes, y comparar turnos
 * contra mensajes es como el disparador anterior (6 turnos = 12 mensajes contra
 * una ventana de 8) dejaba caer mensajes sin resumir.
 */
@Singleton
open class ConversationSummarizer(
    private val agentes: AgentDefinitionRepository,
    private val conversaciones: ConversationService,
    private val router: ModelRouter,
    private val json: ObjectMapper,
) {

    /**
     * Respaldo en turno. Devuelve el estado con el resumen al dia, o el mismo
     * estado si todavia no hace falta o si no se pudo reescribir.
     *
     * Nunca lanza: un resumen es una mejora, no un requisito. Si el modelo
     * ligero esta caido, el turno sigue con el resumen anterior; tumbar la
     * conversacion por eso seria cambiar una degradacion por una caida.
     */
    open fun refrescarSiHaceFalta(conversationId: Long, estado: SessionState): SessionState {
        val pendientes = conversaciones.posteriores(conversationId, estado.summarizedThrough, MAX_POR_RESUMEN)
        if (pendientes.size < UMBRAL_RESPALDO) return estado
        return resumir(conversationId, estado, pendientes) ?: estado
    }

    /**
     * Resume una conversacion inactiva. Devuelve true si escribio un resumen.
     *
     * Escribe solo las claves del resumen (merge sobre el jsonb) y no el estado
     * entero: corre fuera de un turno y no puede pisar la escritura pendiente
     * que un turno concurrente haya guardado.
     */
    open fun resumirPorInactividad(conversationId: Long): Boolean {
        val estado = runCatching {
            json.readValue(conversaciones.leerEstado(conversationId), SessionState::class.java)
        }.getOrNull() ?: SessionState()

        val pendientes = conversaciones.posteriores(conversationId, estado.summarizedThrough, MAX_POR_RESUMEN)
        if (pendientes.isEmpty()) return false

        val nuevo = resumir(conversationId, estado, pendientes) ?: return false
        conversaciones.mezclarEstado(
            conversationId,
            json.writeValueAsString(ResumenParcial(nuevo.summary, nuevo.summaryVersion, nuevo.summarizedThrough)),
        )
        return true
    }

    private fun resumir(conversationId: Long, estado: SessionState, pendientes: List<StoredMessage>): SessionState? =
        try {
            reescribir(estado, pendientes)?.let { resumen ->
                val nuevo = estado.copy(
                    summary = resumen,
                    summaryVersion = estado.summaryVersion + 1,
                    summarizedThrough = pendientes.last().id,
                )
                log.info(
                    "resumen de la conversacion {} reescrito en la version {} ({} mensajes nuevos)",
                    conversationId, nuevo.summaryVersion, pendientes.size,
                )
                nuevo
            }
        } catch (e: Exception) {
            log.warn("no se pudo reescribir el resumen de {}: {}", conversationId, e.javaClass.simpleName)
            log.debug("detalle del fallo al resumir", e)
            null
        }

    private fun reescribir(estado: SessionState, pendientes: List<StoredMessage>): String? {
        val agente = agentes.byCode(CODIGO_AGENTE)
        if (agente == null) {
            // Sin agente de resumen no se improvisa con el de ventas: ese lleva
            // herramientas y otro prompt, y resumir con el saldria caro y mal.
            log.warn("no hay agente '{}' activo; la conversacion seguira sin resumen", CODIGO_AGENTE)
            return null
        }

        val transcripcion = pendientes.joinToString("\n") { m ->
            val quien = if (m.direction == Direction.IN) "Cliente" else "Agente"
            "$quien: ${m.body.orEmpty()}"
        }

        val previo = estado.summary?.takeIf { it.isNotBlank() }
            ?.let { "Resumen anterior:\n$it\n\n" }
            .orEmpty()

        val prompt = ModelPrompt(
            listOf(
                PromptMessage(Role.SYSTEM, agente.prompt.body),
                PromptMessage(Role.USER, previo + "Mensajes nuevos desde el resumen anterior:\n$transcripcion"),
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

    /** Solo las claves del resumen, para mezclarlas en `conversations.state`. */
    @Serdeable
    data class ResumenParcial(
        val summary: String?,
        val summaryVersion: Int,
        val summarizedThrough: Long,
    )

    companion object {
        private val log = LoggerFactory.getLogger(ConversationSummarizer::class.java)

        const val CODIGO_AGENTE = "resumen"

        /**
         * Pendientes (contando los entrantes del turno actual) a partir de los
         * cuales se resume en el turno. Con uno menos, todos los pendientes
         * que no son de este turno todavia caben en la ventana del prompt; con
         * este, el mas viejo esta por salir.
         */
        const val UMBRAL_RESPALDO = PromptBuilder.VENTANA + 1

        /** Tope de mensajes por reescritura, para acotar el costo del modelo ligero. */
        const val MAX_POR_RESUMEN = 60
    }
}
