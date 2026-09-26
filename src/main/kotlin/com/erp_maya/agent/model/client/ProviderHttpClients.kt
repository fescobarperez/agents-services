package com.erp_maya.agent.model.client

import io.micronaut.http.client.HttpClient
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Un cliente HTTP por URL base, creado una vez y reutilizado.
 *
 * No se usa un `@Client` declarativo porque la URL del proveedor es dato
 * —vive en `ai_providers.base_url`— y un `@Client` la fija al compilar.
 * Tampoco se crea uno por turno: cada cliente trae su propio pool de
 * conexiones y su event loop, y abrirlos por conversacion agota el proceso.
 */
@Singleton
class ProviderHttpClients {

    private val clientes = ConcurrentHashMap<String, HttpClient>()

    fun forBaseUrl(baseUrl: String): HttpClient =
        clientes.computeIfAbsent(baseUrl) { HttpClient.create(URI(it).toURL()) }

    @PreDestroy
    fun cerrar() {
        clientes.values.forEach { runCatching { it.close() } }
        clientes.clear()
    }
}
