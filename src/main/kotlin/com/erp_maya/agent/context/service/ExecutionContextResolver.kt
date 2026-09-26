package com.erp_maya.agent.context.service

import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.repository.ExecutionContextRepository

import io.micronaut.cache.annotation.CacheConfig
import io.micronaut.cache.annotation.Cacheable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Resuelve, una vez por turno, que agente atiende el mensaje.
 *
 * Falla cerrado: si no hay asignacion vigente, si la empresa tiene el bot
 * apagado o si el canal atiende a varias empresas sin que el mensaje diga a
 * cual, no se inventa un valor por defecto. Se lanza
 * [AgentUnavailableException] y el turno se escala.
 *
 * El cache es de cinco minutos porque esto es configuracion, no datos: cambiar
 * de agente o apagar una empresa tarda como mucho ese rato en propagarse, y a
 * cambio se ahorra una consulta de siete tablas en cada mensaje.
 */
@Singleton
@CacheConfig("execution-context")
open class ExecutionContextResolver(private val repository: ExecutionContextRepository) {

    /**
     * @param accountRef cuenta del canal (`channels.account_ref`)
     * @param tenantId   empresa, cuando el canal ya la trae resuelta —el widget
     *                   del ERP la saca del JWT—; nulo en WhatsApp, donde se
     *                   deduce de la asignacion del canal
     */
    @Cacheable(parameters = ["accountRef", "tenantId"])
    open fun resolve(accountRef: String, tenantId: Long?): ExecutionContext {
        val candidatos = repository.resolve(accountRef, tenantId)

        if (candidatos.isEmpty()) {
            // No se distingue "canal inexistente" de "sin agente vigente" hacia
            // afuera, pero si en el log: para quien configura, son dos arreglos
            // distintos.
            throw AgentUnavailableException(
                AgentUnavailableException.Reason.NO_ACTIVE_AGENT,
                "Sin agente vigente para el canal '$accountRef'" +
                    (tenantId?.let { " y la empresa $it" } ?: ""),
            )
        }

        // Varias empresas en el mismo canal y nadie dijo cual. Escoger la de
        // mayor prioridad seria contestarle a un cliente con datos de otro.
        val empresas = candidatos.map { it.tenantId }.distinct()
        if (empresas.size > 1) {
            throw AgentUnavailableException(
                AgentUnavailableException.Reason.AMBIGUOUS_TENANT,
                "El canal '$accountRef' atiende a ${empresas.size} empresas y el mensaje no indica cual",
            )
        }

        val contexto = candidatos.first()
        if (!contexto.policy.enabled) {
            throw AgentUnavailableException(
                AgentUnavailableException.Reason.TENANT_DISABLED,
                "La empresa ${contexto.tenantId} tiene el agente deshabilitado",
            )
        }

        log.debug(
            "Contexto resuelto tenant={} canal={} agente={} modelo={} tools={}",
            contexto.tenantId, accountRef, contexto.agent.code,
            contexto.model.modelName, contexto.tools.size,
        )
        return contexto
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ExecutionContextResolver::class.java)
    }
}
