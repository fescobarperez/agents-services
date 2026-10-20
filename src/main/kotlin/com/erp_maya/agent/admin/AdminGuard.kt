package com.erp_maya.agent.admin

import com.erp_maya.agent.api.security.ApiKeyAuthenticator
import jakarta.inject.Singleton
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Llave de servicio con scope `admin` (la de la consola, via stack-line-services). */
@Singleton
class AdminGuard(private val authenticator: ApiKeyAuthenticator) {

    fun autorizar(apiKey: String?) {
        val caller = authenticator.authenticate(apiKey)
            ?: throw AdminException(401, "UNAUTHORIZED", "Credencial invalida o ausente")
        if (!caller.allows(SCOPE)) throw AdminException(403, "FORBIDDEN", "La credencial no administra la IA")
    }

    /** `X-Operator-Name` llega URL-encoded (lleva tildes). */
    fun operador(raw: String?): String? = raw?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() }

    companion object {
        const val API_KEY = "X-Api-Key"
        const val OPERATOR = "X-Operator-Name"
        const val SCOPE = "admin"
    }
}
