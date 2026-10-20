package com.erp_maya.agent.playbook.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.playbook.repository.PlaybookRepository
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Entrega el playbook que aplica a un turno.
 *
 * Se guarda un minuto en memoria: es configuracion, y un cambio hecho en la
 * base tarda como mucho eso en verse. Si no hay playbook o el JSON no se puede
 * leer, se usa [Playbook] por defecto —lo mas conservador— y se registra.
 */
@Singleton
open class PlaybookService(
    private val repositorio: PlaybookRepository,
    private val json: ObjectMapper,
) {

    private data class Entrada(val playbook: Playbook, val vence: Instant)

    private val cache = ConcurrentHashMap<String, Entrada>()

    /** Tras guardar un playbook desde la consola: el siguiente turno lo lee de la base. */
    open fun invalidar() = cache.clear()

    open fun para(contexto: ExecutionContext): Playbook {
        val agenteEmpresa = contexto.tenantAgent?.id
        val clave = "${contexto.agent.code}/${contexto.tenantId}/${agenteEmpresa ?: "-"}"
        val ahora = Instant.now()
        cache[clave]?.takeIf { it.vence.isAfter(ahora) }?.let { return it.playbook }

        val playbook = cargar(contexto.agent.code, contexto.tenantId, agenteEmpresa)
        cache[clave] = Entrada(playbook, ahora.plus(VIGENCIA))
        return playbook
    }

    private fun cargar(agentCode: String, tenantId: Long, tenantAgentId: Long?): Playbook {
        val crudo = runCatching { repositorio.vigente(agentCode, tenantId, tenantAgentId) }
            .onFailure { log.error("no se pudo leer el playbook de {} / empresa {}", agentCode, tenantId, it) }
            .getOrNull()
            ?: return Playbook().also { log.warn("sin playbook para {} / empresa {}: se usa el de por defecto", agentCode, tenantId) }

        return runCatching { json.readValue(crudo, Playbook::class.java) }
            .onFailure { log.error("playbook ilegible para {} / empresa {}: se usa el de por defecto", agentCode, tenantId, it) }
            .getOrNull()
            ?.also { log.info("playbook de {} / empresa {} cargado (negocio: {})", agentCode, tenantId, if (it.negocio != null) "si" else "no") }
            ?: Playbook()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PlaybookService::class.java)
        val VIGENCIA: Duration = Duration.ofMinutes(1)
    }
}
