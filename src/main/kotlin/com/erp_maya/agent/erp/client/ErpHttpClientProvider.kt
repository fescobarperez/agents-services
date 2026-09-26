package com.erp_maya.agent.erp.client

import io.micronaut.http.client.DefaultHttpClientConfiguration
import io.micronaut.http.client.HttpClient
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import java.net.URI
import java.time.Duration

/**
 * Un unico cliente HTTP contra el ERP, creado al arrancar y reutilizado.
 *
 * No se crea por turno: cada cliente trae su pool de conexiones y su event
 * loop, y abrirlos por conversacion agota el proceso con el primer pico de
 * trafico.
 */
@Singleton
class ErpHttpClientProvider(config: ErpConfiguration) {

    private val cliente: HttpClient = HttpClient.create(
        URI(config.baseUrl).toURL(),
        DefaultHttpClientConfiguration().apply {
            // Un agente que espera es un cliente esperando: mejor fallar y
            // escalar que dejar la conversacion colgada.
            requestTimeout = Duration.ofMillis(config.timeoutMs)
        },
    )

    fun client(): HttpClient = cliente

    @PreDestroy
    fun cerrar() = cliente.close()
}
