package com.erp_maya.agent.api.security

/**
 * Quien llama, resuelto desde la credencial y nunca desde el cuerpo.
 *
 * `tenantId` es nulo para un adaptador que atiende a varias empresas —el de
 * WhatsApp—, y en ese caso la empresa se deduce de la cuenta del canal. Cuando
 * viene informado, manda: una credencial atada a una empresa no puede hablar
 * por otra aunque la cuenta del canal diga lo contrario.
 */
data class CallerCredentials(
    val clientId: String,
    val tenantId: Long?,
    val scopes: Set<String>,
) {
    fun allows(scope: String): Boolean = scopes.contains(scope) || scopes.contains("*")

    companion object {
        /** Clave con la que viaja en los atributos de la petición. */
        const val ATTRIBUTE = "agent.caller"
    }
}
