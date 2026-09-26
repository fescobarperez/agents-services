package com.erp_maya.agent.erp.client

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.Nullable

/**
 * Como se llega a maya-erp-services. URL y credenciales viven aqui, nunca en
 * la base ni en el ExecutionContext.
 */
@ConfigurationProperties("erp")
class ErpConfiguration {
    var baseUrl: String = "http://localhost:8080"
    var timeoutMs: Long = 10_000

    /** Credenciales de MAQUINA, no un usuario-persona. Siempre por entorno. */
    @Nullable
    var clientId: String? = null

    @Nullable
    var clientSecret: String? = null

    fun hasCredentials(): Boolean = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
}
