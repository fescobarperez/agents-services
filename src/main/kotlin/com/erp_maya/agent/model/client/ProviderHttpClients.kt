package com.erp_maya.agent.model.client

import jakarta.inject.Singleton
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Cliente HTTP para los proveedores de modelo.
 *
 * Usa `java.net.http.HttpClient` del JDK y no el cliente Netty de Micronaut:
 * con JDK 25 el TLS de Netty falla contra los proveedores con
 * «ByteBuffer derived from closeable shared sessions not supported» (el
 * descifrado GCM del JDK no acepta los buffers directos que Netty reserva en
 * arenas compartidas). El cliente del JDK no pasa por esos buffers.
 *
 * Uno solo para todo el proceso: la URL del proveedor es dato —vive en
 * `ai_providers.base_url`— y el cliente del JDK ya mantiene su propio pool.
 */
@Singleton
class ProviderHttpClients {

    private val cliente: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** Respuesta cruda; interpretar el cuerpo es cosa de cada proveedor. */
    data class Respuesta(val status: Int, val body: String)

    fun postJson(url: String, bearer: String, json: String, timeout: Duration): Respuesta {
        val peticion = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val r = cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        return Respuesta(r.statusCode(), r.body() ?: "")
    }
}
