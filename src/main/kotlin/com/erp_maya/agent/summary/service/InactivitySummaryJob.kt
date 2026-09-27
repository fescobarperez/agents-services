package com.erp_maya.agent.summary.service

import com.erp_maya.agent.conversation.service.ConversationService
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Resume las conversaciones que quedaron quietas.
 *
 * Es el disparador normal del resumen: una conversacion se da por pausada tras
 * `agent.summary.inactivity-minutes` sin mensajes, y ahi se resume una sola vez
 * todo lo que falta. El respaldo por ventana vive en el turno
 * ([ConversationSummarizer.refrescarSiHaceFalta]).
 *
 * Con varias instancias, dos podrian resumir la misma conversacion a la vez:
 * cuesta una llamada extra al modelo ligero y la segunda escritura gana, pero
 * no se pierde nada. Si llega a importar, se reclama con un campo en `state`.
 */
@Singleton
open class InactivitySummaryJob(
    private val conversaciones: ConversationService,
    private val resumidor: ConversationSummarizer,
    @param:Value("\${agent.summary.job-enabled:true}") private val habilitado: Boolean,
    @param:Value("\${agent.summary.inactivity-minutes:10}") private val minutos: Int,
    @param:Value("\${agent.summary.batch-size:20}") private val lote: Int,
) {

    @Scheduled(fixedDelay = "\${agent.summary.check-interval:1m}", initialDelay = "\${agent.summary.initial-delay:1m}")
    open fun alTick() {
        if (!habilitado) return
        try {
            ejecutar()
        } catch (e: Exception) {
            log.error("fallo el ciclo de resumen por inactividad", e)
        }
    }

    /** Un ciclo. Devuelve cuantas conversaciones resumio. */
    open fun ejecutar(): Int =
        conversaciones.inactivasSinResumir(minutos, lote)
            .count { resumidor.resumirPorInactividad(it) }

    private companion object {
        private val log = LoggerFactory.getLogger(InactivitySummaryJob::class.java)
    }
}
