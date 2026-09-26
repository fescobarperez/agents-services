package com.erp_maya.agent.api.security

import io.micronaut.context.annotation.EachProperty
import io.micronaut.context.annotation.Parameter
import io.micronaut.core.annotation.Nullable

/**
 * Un cliente autorizado a llamar al endpoint de turnos.
 *
 * Se declara en configuracion, con la clave siempre por variable de entorno:
 *
 * ```yaml
 * agent:
 *   clients:
 *     whatsapp-adapter:
 *       api-key: ${AGENT_KEY_WHATSAPP}
 *       scopes: [productos.read, cotizaciones.write]
 * ```
 */
@EachProperty("agent.clients")
class ApiClientConfiguration(@param:Parameter val name: String) {

    /** Nunca literal en el yml: siempre una variable de entorno. */
    var apiKey: String? = null

    var scopes: List<String> = emptyList()

    /** Empresa fija, si este cliente solo puede hablar por una. */
    @Nullable
    var tenantId: Long? = null

    /**
     * Una clave vacia no autentica a nadie. Sin esto, olvidar la variable de
     * entorno convertiria al cliente en comodin: cualquiera que mandara una
     * cabecera vacia entraria con sus permisos.
     */
    fun isUsable(): Boolean = !apiKey.isNullOrBlank()
}
