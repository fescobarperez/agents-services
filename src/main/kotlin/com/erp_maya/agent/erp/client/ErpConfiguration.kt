package com.erp_maya.agent.erp.client

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.Nullable

/**
 * Como se llega a maya-erp-services. URL y llave viven aqui, nunca en la base
 * ni en el ExecutionContext.
 */
@ConfigurationProperties("erp")
class ErpConfiguration {
    var baseUrl: String = "http://localhost:8080"
    var timeoutMs: Long = 10_000

    /**
     * Llave de servicio (AGENT_KEY_ERP) con la que se piden al ERP los tokens
     * por empresa. Siempre por entorno.
     */
    @Nullable
    var serviceKey: String? = null

    fun hasServiceKey(): Boolean = !serviceKey.isNullOrBlank()
}
