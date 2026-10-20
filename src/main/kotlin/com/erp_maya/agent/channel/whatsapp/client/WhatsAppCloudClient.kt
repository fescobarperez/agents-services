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
                header = m.documento?.let {
                    WaSendHeader(type = "document", document = WaSendDocument(id = it.mediaId, filename = it.filename))
                },
                body = WaSendBody(m.body.take(LARGO_CUERPO)),
                footer = m.pie?.takeIf { it.isNotBlank() }?.let { WaSendBody(it.take(LARGO_PIE)) },
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
        is WhatsAppOutbound.Image -> WaSendRequest(
            to = to,
            type = "image",
            image = WaSendImage(m.mediaId, m.caption?.take(LARGO_PIE_IMAGEN)),
        )
    }

    /**
     * Datos de un numero en Meta (`GET /{phone_number_id}`), para conectarlo
     * desde la consola. Solo funciona con numeros de la cuenta de WhatsApp
     * Business a la que tiene acceso el token de Stackline.
     *
     * @throws WhatsAppNumeroException si Meta no lo reconoce (4xx) o no responde.
     */
    open fun numero(phoneNumberId: String): WaNumero {
        if (accessToken.isBlank()) {
            throw WhatsAppNumeroException(WaNumeroError.SIN_TOKEN, "WHATSAPP_ACCESS_TOKEN no está definida en agents-services")
        }
        val peticion = HttpRequest.newBuilder(URI("${graphUrl.trimEnd('/')}/$phoneNumberId?fields=$CAMPOS_NUMERO"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val crudo = try {
            cliente.send(peticion, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw WhatsAppNumeroException(WaNumeroError.SIN_RESPUESTA, "No se pudo contactar a Meta: ${e.javaClass.simpleName}")
        }
        if (crudo.statusCode() !in 200..299) {
            // El mensaje de Meta explica el motivo (id inexistente, sin permiso); el token nunca sale.
            val motivo = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(crudo.body().orEmpty())?.groupValues?.get(1)
            throw WhatsAppNumeroException(WaNumeroError.RECHAZADO, motivo ?: "Meta respondió ${crudo.statusCode()}")
        }
        return runCatching { json.readValue(crudo.body(), WaNumero::class.java) }.getOrNull()
            ?: throw WhatsAppNumeroException(WaNumeroError.SIN_RESPUESTA, "Meta respondió algo que no se pudo leer")
    }

    /**
     * Baja una foto que mando el cliente. Son dos pasos: `GET /{media_id}`
     * devuelve una URL temporal (dura minutos) y esa URL se baja con el mismo
     * token. Los bytes quedan solo en memoria.
     *
     * @param maxBytes tope de tamaño: se revisa con lo que declara Meta antes
     * de bajar, y otra vez con lo que llego.
     */
    open fun descargarMedia(mediaId: String, maxBytes: Long): MediaDescargada {
        if (accessToken.isBlank()) {
            throw WhatsAppSendException("WHATSAPP_ACCESS_TOKEN no esta definida: no se puede bajar media de WhatsApp")
        }
        val info = HttpRequest.newBuilder(URI("${graphUrl.trimEnd('/')}/$mediaId"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val crudo = try {
            cliente.send(info, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw WhatsAppSendException("No se pudo contactar a la Graph API: ${e.javaClass.simpleName} ${e.message.orEmpty()}", e)
        }
        if (crudo.statusCode() !in 200..299) {
            throw WhatsAppSendException("Meta no entrego la media $mediaId (${crudo.statusCode()}): ${crudo.body().orEmpty().take(300)}")
        }
        val meta = runCatching { json.readValue(crudo.body(), WaMediaInfo::class.java) }.getOrNull()
            ?: throw WhatsAppSendException("Meta respondio algo ilegible para la media $mediaId")
        val url = meta.url ?: throw WhatsAppSendException("Meta no devolvio URL para la media $mediaId")
        meta.fileSize?.let { if (it > maxBytes) throw MediaDemasiadoGrandeException(it, maxBytes) }

        val bajada = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val archivo = try {
            cliente.send(bajada, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            throw WhatsAppSendException("No se pudo bajar la media $mediaId: ${e.javaClass.simpleName}", e)
        }
        if (archivo.statusCode() !in 200..299) {
            throw WhatsAppSendException("Meta rechazo la descarga de la media $mediaId (${archivo.statusCode()})")
        }
        val bytes = archivo.body() ?: ByteArray(0)
        if (bytes.size > maxBytes) throw MediaDemasiadoGrandeException(bytes.size.toLong(), maxBytes)
        return MediaDescargada(bytes, meta.mimeType ?: archivo.headers().firstValue("Content-Type").orElse(null))
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
    @Nullable val image: WaSendImage? = null,
)

/** El `text` de un mensaje de texto: `{"body": "..."}`. */
/** Meta no reconocio el numero o no respondio. `tipo`: sin token, rechazado o sin respuesta. */
class WhatsAppNumeroException(val tipo: String, message: String) : RuntimeException(message)

/** Un numero de WhatsApp Business tal como lo describe Meta. */
@Serdeable
data class WaNumero(
    val id: String,
    @JsonProperty("display_phone_number") @Nullable val displayPhoneNumber: String? = null,
    @JsonProperty("verified_name") @Nullable val verifiedName: String? = null,
    @JsonProperty("quality_rating") @Nullable val qualityRating: String? = null,
    @JsonProperty("code_verification_status") @Nullable val codeVerificationStatus: String? = null,
)

@Serdeable
data class WaSendText(val body: String)

/** El `body` de un mensaje interactivo: Meta lo pide como `{"text": "..."}`. */
@Serdeable
data class WaSendBody(val text: String)

/** Encabezado de un interactivo; aqui solo se usa el de documento (el PDF). */
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaSendHeader(val type: String, @Nullable val document: WaSendDocument? = null)

@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaSendInteractive(
    val type: String,
    @Nullable val header: WaSendHeader? = null,
    val body: WaSendBody,
    @Nullable val footer: WaSendBody? = null,
    val action: WaSendAction,
)

/** Limites de Meta para un interactivo con botones. */
private const val LARGO_CUERPO = 1024
private const val LARGO_PIE = 60
private const val LARGO_PIE_IMAGEN = 1024

/** Lo que se pide de un numero al conectarlo desde la consola. */
private const val CAMPOS_NUMERO = "display_phone_number,verified_name,quality_rating,code_verification_status"

/** Tipos de [WhatsAppNumeroException]. */
object WaNumeroError {
    const val SIN_TOKEN = "SIN_TOKEN"
    const val RECHAZADO = "RECHAZADO"
    const val SIN_RESPUESTA = "SIN_RESPUESTA"
}

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

/** `image` de un mensaje: media ya subida y un pie (Meta admite hasta 1024 caracteres). */
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaSendImage(val id: String, @Nullable val caption: String? = null)

/** Respuesta de `GET /{media_id}`: de donde bajarla y que es. */
@Serdeable
data class WaMediaInfo(
    @Nullable val url: String? = null,
    @JsonProperty("mime_type") @Nullable val mimeType: String? = null,
    @JsonProperty("file_size") @Nullable val fileSize: Long? = null,
)

/** Una media ya bajada. Vive solo en memoria mientras dura el turno. */
class MediaDescargada(val bytes: ByteArray, val mimeType: String?)

class MediaDemasiadoGrandeException(val bytes: Long, val maximo: Long) :
    RuntimeException("La media pesa $bytes bytes y el maximo es $maximo")

@Serdeable
data class WaMediaResponse(@Nullable val id: String? = null)

@Serdeable
data class WaSendResponse(@Nullable val messages: List<WaSendMessageId>? = null)

@Serdeable
data class WaSendMessageId(@Nullable val id: String? = null)
