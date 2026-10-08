package com.erp_maya.agent.channel.whatsapp.client

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.ObjectMapper
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** No se pudo entregar un mensaje a WhatsApp. El consumidor decide si reintenta. */
class WhatsAppSendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Envio a WhatsApp Cloud API: `POST /{phone_number_id}/messages`, y subida de
 * media (`POST /{phone_number_id}/media`) para los stickers.
 *
 * El token va SIEMPRE por variable de entorno. Sin token no se intenta nada:
 * se falla con un mensaje que nombra la variable, y el evento vuelve a la cola
 * en vez de perderse.
 *
 * Usa `java.net.http.HttpClient` del JDK y no el cliente Netty de Micronaut:
 * con JDK 25 el TLS de Netty falla contra hosts HTTPS («ByteBuffer derived
 * from closeable shared sessions not supported»), el mismo problema que
 * tuvimos con el proveedor del modelo.
 */
@Singleton
open class WhatsAppCloudClient(
    @param:Value("\${whatsapp.graph-url:`https://graph.facebook.com/v23.0`}") private val graphUrl: String,
    @param:Value("\${whatsapp.access-token:}") private val accessToken: String,
) {

    private val cliente: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build()
    }
    private val json: ObjectMapper by lazy { ObjectMapper.getDefault() }

    /**
     * Entrega un mensaje y devuelve el wamid que le asigno Meta.
     *
     * @param phoneNumberId la cuenta que envia, sin el prefijo `wa:`.
     * @param to el `wa_id` del cliente.
     */
    open fun send(phoneNumberId: String, to: String, mensaje: WhatsAppOutbound): String {
        if (accessToken.isBlank()) {
            throw WhatsAppSendException("WHATSAPP_ACCESS_TOKEN no esta definida: no se puede enviar a WhatsApp")
        }

        val peticion = HttpRequest.newBuilder(URI("${graphUrl.trimEnd('/')}/$phoneNumberId/messages"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo(to, mensaje))))
            .build()

        val crudo = try {
            cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw WhatsAppSendException("No se pudo contactar a la Graph API: ${e.javaClass.simpleName} ${e.message.orEmpty()}", e)
        }

        if (crudo.statusCode() !in 200..299) {
            // Se registra el cuerpo del error de Meta (trae el codigo y el
            // motivo), nunca el token.
            throw WhatsAppSendException("Meta rechazo el envio (${crudo.statusCode()}): ${crudo.body().orEmpty().take(500)}")
        }

        val respuesta = runCatching { json.readValue(crudo.body(), WaSendResponse::class.java) }.getOrNull()

        return respuesta?.messages?.firstOrNull()?.id
            ?: throw WhatsAppSendException("Meta acepto el envio pero no devolvio wamid")
    }

    private fun cuerpo(to: String, m: WhatsAppOutbound): WaSendRequest = when (m) {
        is WhatsAppOutbound.Text -> WaSendRequest(to = to, type = "text", text = WaSendText(m.body))
        is WhatsAppOutbound.Buttons -> WaSendRequest(
            to = to,
            type = "interactive",
            interactive = WaSendInteractive(
                type = "button",
                body = WaSendBody(m.body),
                action = WaSendAction(m.buttons.map { (id, titulo) -> WaSendButton(reply = WaSendReply(id, titulo)) }),
            ),
        )
        is WhatsAppOutbound.Document -> WaSendRequest(
            to = to,
            type = "document",
            document = WaSendDocument(link = m.url, filename = m.filename),
        )
        is WhatsAppOutbound.DocumentMedia -> WaSendRequest(
            to = to,
            type = "document",
            document = WaSendDocument(id = m.mediaId, filename = m.filename, caption = m.caption),
        )
        is WhatsAppOutbound.Sticker -> WaSendRequest(to = to, type = "sticker", sticker = WaSendMedia(m.mediaId))
    }

    /**
     * Sube un archivo a Meta (`POST /{phone_number_id}/media`) y devuelve su
     * media id, que sirve para enviarlo durante 30 dias sin volver a subirlo.
     */
    open fun subirMedia(phoneNumberId: String, contenido: ByteArray, mimeType: String, nombre: String): String {
        if (accessToken.isBlank()) {
            throw WhatsAppSendException("WHATSAPP_ACCESS_TOKEN no esta definida: no se puede subir media a WhatsApp")
        }
        val limite = "----stackline-${System.nanoTime()}"
        val cuerpo = multipart(limite, mimeType, nombre, contenido)

        val peticion = HttpRequest.newBuilder(URI("${graphUrl.trimEnd('/')}/$phoneNumberId/media"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "multipart/form-data; boundary=$limite")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo))
            .build()

        val crudo = try {
            cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw WhatsAppSendException("No se pudo contactar a la Graph API: ${e.javaClass.simpleName} ${e.message.orEmpty()}", e)
        }
        if (crudo.statusCode() !in 200..299) {
            throw WhatsAppSendException("Meta rechazo la subida de $nombre (${crudo.statusCode()}): ${crudo.body().orEmpty().take(500)}")
        }
        return runCatching { json.readValue(crudo.body(), WaMediaResponse::class.java) }.getOrNull()?.id
            ?: throw WhatsAppSendException("Meta acepto la subida de $nombre pero no devolvio id")
    }

    private fun multipart(limite: String, mimeType: String, nombre: String, contenido: ByteArray): ByteArray {
        val salida = java.io.ByteArrayOutputStream()
        fun campo(nombreCampo: String, valor: String) {
            salida.write("--$limite\r\nContent-Disposition: form-data; name=\"$nombreCampo\"\r\n\r\n$valor\r\n".toByteArray())
        }
        campo("messaging_product", "whatsapp")
        campo("type", mimeType)
        salida.write(
            ("--$limite\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$nombre\"\r\n" +
                "Content-Type: $mimeType\r\n\r\n").toByteArray(),
        )
        salida.write(contenido)
        salida.write("\r\n--$limite--\r\n".toByteArray())
        return salida.toByteArray()
    }

}

@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaSendRequest(
    @JsonProperty("messaging_product") val messagingProduct: String = "whatsapp",
    @JsonProperty("recipient_type") val recipientType: String = "individual",
    val to: String,
    val type: String,
    @Nullable val text: WaSendText? = null,
    @Nullable val interactive: WaSendInteractive? = null,
    @Nullable val document: WaSendDocument? = null,
    @Nullable val sticker: WaSendMedia? = null,
)

/** El `text` de un mensaje de texto: `{"body": "..."}`. */
@Serdeable
data class WaSendText(val body: String)

/** El `body` de un mensaje interactivo: Meta lo pide como `{"text": "..."}`. */
@Serdeable
data class WaSendBody(val text: String)

@Serdeable
data class WaSendInteractive(val type: String, val body: WaSendBody, val action: WaSendAction)

@Serdeable
data class WaSendAction(val buttons: List<WaSendButton>)

@Serdeable
data class WaSendButton(val type: String = "reply", val reply: WaSendReply)

@Serdeable
data class WaSendReply(val id: String, val title: String)

/** Por `link` (URL publica) o por `id` (media ya subida a Meta). */
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaSendDocument(
    @Nullable val link: String? = null,
    @Nullable val id: String? = null,
    val filename: String,
    @Nullable val caption: String? = null,
)

/** Media ya subida a Meta, referida por su id. */
@Serdeable
data class WaSendMedia(val id: String)

@Serdeable
data class WaMediaResponse(@Nullable val id: String? = null)

@Serdeable
data class WaSendResponse(@Nullable val messages: List<WaSendMessageId>? = null)

@Serdeable
data class WaSendMessageId(@Nullable val id: String? = null)
