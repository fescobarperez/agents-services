package com.erp_maya.agent.api.security

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** Traduce la cabecera `X-Api-Key` a las credenciales de quien llama. */
@Singleton
class ApiKeyAuthenticator(clients: List<ApiClientConfiguration>) {

    private val porClave: Map<String, CallerCredentials> = clients
        .filter { it.isUsable() }
        .associate { cfg ->
            cfg.apiKey!! to CallerCredentials(cfg.name, cfg.tenantId, cfg.scopes.toSet())
        }

    init {
        val descartados = clients.count { !it.isUsable() }
        if (descartados > 0) {
            log.warn("{} cliente(s) sin api-key configurada quedan deshabilitados", descartados)
        }
        log.info("Clientes habilitados: {}", porClave.values.joinToString { it.clientId })
    }

    fun authenticate(apiKey: String?): CallerCredentials? {
        if (apiKey.isNullOrBlank()) return null
        // Comparacion en tiempo constante: un equals normal se rinde en el
        // primer byte distinto y eso filtra la clave a quien sepa medir.
        return porClave.entries.firstOrNull { iguales(it.key, apiKey) }?.value
    }

    private fun iguales(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        private val log = LoggerFactory.getLogger(ApiKeyAuthenticator::class.java)
    }
}
