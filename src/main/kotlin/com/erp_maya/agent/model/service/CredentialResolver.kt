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

    fun secretFor(provider: AiProvider): String {
        val valor = System.getenv(provider.credentialRef)
            ?: environment.getProperty(provider.credentialRef, String::class.java).orElse(null)

        if (valor.isNullOrBlank()) {
            // El mensaje nombra la variable que falta, nunca su contenido.
            throw ModelCallException(
                "El proveedor '${provider.code}' necesita la variable ${provider.credentialRef} y no esta definida",
            )
        }
        return valor
    }
}
