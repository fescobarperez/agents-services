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

    /** Un campo de un formulario multipart: texto, o archivo si trae [contenido]. */
    class Parte(val nombre: String, val valor: String? = null, val archivo: String? = null,
                val mimeType: String? = null, val contenido: ByteArray? = null)

    /** POST multipart/form-data (p. ej. `/images/edits`, que recibe la foto como archivo). */
    fun postMultipart(url: String, bearer: String, partes: List<Parte>, timeout: Duration): Respuesta {
        val limite = "----stackline-${System.nanoTime()}"
        val peticion = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "multipart/form-data; boundary=$limite")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(limite, partes)))
            .build()
        val r = cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        return Respuesta(r.statusCode(), r.body() ?: "")
    }

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

    companion object {
        internal fun multipart(limite: String, partes: List<Parte>): ByteArray {
            val salida = java.io.ByteArrayOutputStream()
            partes.forEach { p ->
                salida.write("--$limite\r\n".toByteArray())
                if (p.contenido != null) {
                    salida.write(
                        ("Content-Disposition: form-data; name=\"${p.nombre}\"; filename=\"${p.archivo ?: p.nombre}\"\r\n" +
                            "Content-Type: ${p.mimeType ?: "application/octet-stream"}\r\n\r\n").toByteArray(),
                    )
                    salida.write(p.contenido)
                    salida.write("\r\n".toByteArray())
                } else {
                    salida.write("Content-Disposition: form-data; name=\"${p.nombre}\"\r\n\r\n${p.valor.orEmpty()}\r\n".toByteArray())
                }
            }
            salida.write("--$limite--\r\n".toByteArray())
            return salida.toByteArray()
        }
    }
}
