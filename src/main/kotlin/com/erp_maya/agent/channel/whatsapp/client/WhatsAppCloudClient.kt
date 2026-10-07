package com.erp_maya.agent.channel.whatsapp.client

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI

/** No se pudo entregar un mensaje a WhatsApp. El consumidor decide si reintenta. */
class WhatsAppSendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Envio a WhatsApp Cloud API: `POST /{phone_number_id}/messages`.
 *
 * El token va SIEMPRE por variable de entorno. Sin token no se intenta nada:
 * se falla con un mensaje que nombra la variable, y el evento vuelve a la cola
 * en vez de perderse.
 */
@Singleton
open class WhatsAppCloudClient(
    @param:Value("\${whatsapp.graph-url:`https://graph.facebook.com/v23.0`}") private val graphUrl: String,
    @param:Value("\${whatsapp.access-token:}") private val accessToken: String,
) {

    private val clientePerezoso = lazy { HttpClient.create(URI(graphUrl).toURL()) }
    private val cliente: HttpClient by clientePerezoso

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

        val peticion = HttpRequest.POST("$graphUrl/$phoneNumberId/messages", cuerpo(to, mensaje))
            .bearerAuth(accessToken)
            .contentType(MediaType.APPLICATION_JSON_TYPE)

        val respuesta = try {
            cliente.toBlocking().retrieve(peticion, WaSendResponse::class.java)
        } catch (e: HttpClientResponseException) {
            // Se registra el cuerpo del error de Meta (trae el codigo y el
            // motivo), nunca el token.
            val detalle = e.response.getBody(String::class.java).orElse(e.message ?: "")
            throw WhatsAppSendException("Meta rechazo el envio (${e.status.code}): $detalle", e)
        } catch (e: Exception) {
            throw WhatsAppSendException("No se pudo contactar a la Graph API: ${e.javaClass.simpleName}", e)
        }

        return respuesta.messages?.firstOrNull()?.id
            ?: throw WhatsAppSendException("Meta acepto el envio pero no devolvio wamid")
    }

    private fun cuerpo(to: String, m: WhatsAppOutbound): WaSendRequest = when (m) {
        is WhatsAppOutbound.Text -> WaSendRequest(to = to, type = "text", text = WaSendText(m.body))
        is WhatsAppOutbound.Buttons -> WaSendRequest(
            to = to,
            type = "interactive",
            interactive = WaSendInteractive(
                type = "button",
                body = WaSendText(m.body),
                action = WaSendAction(m.buttons.map { (id, titulo) -> WaSendButton(reply = WaSendReply(id, titulo)) }),
            ),
        )
        is WhatsAppOutbound.Document -> WaSendRequest(
            to = to,
            type = "document",
            document = WaSendDocument(link = m.url, filename = m.filename),
        )
    }

    @PreDestroy
    fun cerrar() {
        if (!clientePerezoso.isInitialized()) return
        runCatching { cliente.close() }
            .onFailure { log.debug("no se pudo cerrar el cliente de la Graph API", it) }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppCloudClient::class.java)
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
)

/** Sirve para el `text` de un mensaje y para el `body` de uno interactivo. */
@Serdeable
data class WaSendText(val body: String)

@Serdeable
data class WaSendInteractive(val type: String, val body: WaSendText, val action: WaSendAction)

@Serdeable
data class WaSendAction(val buttons: List<WaSendButton>)

@Serdeable
data class WaSendButton(val type: String = "reply", val reply: WaSendReply)

@Serdeable
data class WaSendReply(val id: String, val title: String)

@Serdeable
data class WaSendDocument(val link: String, val filename: String)

@Serdeable
data class WaSendResponse(@Nullable val messages: List<WaSendMessageId>? = null)

@Serdeable
data class WaSendMessageId(@Nullable val id: String? = null)
