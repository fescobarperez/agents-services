package com.erp_maya.agent.usage

import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.AiLimit
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Limite mensual de mensajes de IA del plan de la empresa (lo define la consola
 * en el ERP: `maxAiMessagesPerMonth`). Un mensaje de IA es un turno completado,
 * la misma cuenta que «Uso y costos».
 *
 * El limite se lee del ERP y se guarda unos minutos: cambiarlo en la consola
 * tarda a lo sumo eso en aplicar. Si el ERP no responde no se corta (falla
 * abierto): dejar a un cliente sin respuesta por un problema de red es peor
 * que unos mensajes de mas.
 */
@Singleton
open class AiQuotaService(private val erp: ErpClient, private val uso: UsageRepository) {

    private data class Leido(val limite: AiLimit?, val hastaMs: Long)

    private val cache = ConcurrentHashMap<Long, Leido>()

    /** ¿La empresa ya agoto sus mensajes de IA del mes? (El turno actual aun no cuenta.) */
    @Transactional(readOnly = true)
    open fun agotado(tenantId: Long, conversationId: Long): Boolean {
        val maximo = limite(tenantId, conversationId)?.monthlyMessages ?: return false
        val mes = UsageMonth.de(null)
        val usados = uso.turnosDeEmpresa(tenantId, mes.inicio, mes.fin)
        if (usados >= maximo) {
            log.info("empresa {} sin mensajes de IA este mes: {}/{}", tenantId, usados, maximo)
            return true
        }
        return false
    }

    private fun limite(tenantId: Long, conversationId: Long): AiLimit? {
        val ahora = System.currentTimeMillis()
        cache[tenantId]?.takeIf { it.hastaMs > ahora }?.let { return it.limite }
        val leido = runCatching { erp.aiLimit(tenantId, conversationId) }
        val limite = leido.getOrNull()
        leido.exceptionOrNull()?.let { log.warn("no se pudo leer el limite de IA de la empresa {}: {}", tenantId, it.message) }
        // Un fallo se reintenta pronto; una lectura buena dura mas.
        cache[tenantId] = Leido(limite, ahora + if (leido.isSuccess) VIGENCIA_MS else REINTENTO_MS)
        return limite
    }

    private companion object {
        const val VIGENCIA_MS = 5 * 60_000L
        const val REINTENTO_MS = 60_000L
        private val log = LoggerFactory.getLogger(AiQuotaService::class.java)
    }
}
