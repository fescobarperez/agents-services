package com.erp_maya.agent.model.service

import com.erp_maya.agent.model.domain.AiProvider
import com.erp_maya.agent.model.domain.ModelCallException
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton

/**
 * Convierte el `credential_ref` de la base en el secreto de verdad.
 *
 * La base guarda el NOMBRE (`OPENAI_API_KEY`), nunca el valor: una clave en
 * Postgres se filtra en cada respaldo y en cada volcado a un entorno de
 * pruebas. Aqui se resuelve contra el entorno, en memoria y para esa llamada.
 */
@Singleton
class CredentialResolver(private val environment: Environment) {

    /** Si la variable esta definida, sin exponer su valor (la consola solo ve si/no). */
    fun isSet(credentialRef: String): Boolean = !valorDe(credentialRef).isNullOrBlank()

    private fun valorDe(ref: String): String? =
        System.getenv(ref)
            // Lo que el .env local dejo como propiedad del sistema.
            ?: System.getProperty(ref)
            ?: environment.getProperty(ref, String::class.java).orElse(null)

    fun secretFor(provider: AiProvider): String {
        val valor = valorDe(provider.credentialRef)

        if (valor.isNullOrBlank()) {
            // El mensaje nombra la variable que falta, nunca su contenido.
            throw ModelCallException(
                "El proveedor '${provider.code}' necesita la variable ${provider.credentialRef} y no esta definida",
            )
        }
        return valor
    }
}
