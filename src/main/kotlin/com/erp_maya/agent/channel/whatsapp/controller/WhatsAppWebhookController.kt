package com.erp_maya.agent.channel.whatsapp.controller

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppInboundService
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppSignature
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import org.slf4j.LoggerFactory

/**
 * Webhook de WhatsApp Cloud API.
 *
 * Controlador delgado de verdad: verifica la firma, encola y contesta. El
 * turno se procesa fuera, porque Meta corta a los cinco segundos y un turno
 * con modelo y herramientas puede tardar mas que eso.
 */
@Controller("/webhooks/whatsapp")
open class WhatsAppWebhookController(
    private val firmas: WhatsAppSignature,
    private val entrada: WhatsAppInboundService,
    @param:Value("\${whatsapp.verify-token:}") private val verifyToken: String,
) {

    /** Alta del webhook: Meta pide que le devuelvan el reto en texto plano. */
    @Get(produces = [MediaType.TEXT_PLAIN])
    open fun verify(
        @QueryValue("hub.mode") mode: String?,
        @QueryValue("hub.verify_token") token: String?,
        @QueryValue("hub.challenge") challenge: String?,
    ): HttpResponse<String> {
        if (verifyToken.isBlank()) {
            log.error("WHATSAPP_VERIFY_TOKEN no esta configurado: no se puede dar de alta el webhook")
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }
        return if (mode == "subscribe" && token == verifyToken && challenge != null) {
            log.info("webhook de WhatsApp verificado")
            HttpResponse.ok(challenge)
        } else {
            log.warn("intento de verificacion del webhook con token invalido")
            HttpResponse.status(HttpStatus.FORBIDDEN)
        }
    }

    /**
     * Recepcion de mensajes.
     *
     * Se recibe el cuerpo como `ByteArray` a proposito: la firma se calcula
     * sobre los bytes EXACTOS que mando Meta. Deserializar y volver a
     * serializar cambia el orden y los espacios, y la firma ya no cuadra.
     *
     * Responde 200 siempre que la firma sea valida, incluso si el contenido no
     * sirve: un 500 hace que Meta reintente en bucle un mensaje que nunca va a
     * procesarse bien.
     */
    @Post(consumes = [MediaType.APPLICATION_JSON])
    open fun receive(
        @Header("X-Hub-Signature-256") firma: String?,
        @Body cuerpo: ByteArray,
    ): HttpResponse<String> {
        if (!firmas.isValid(cuerpo, firma)) {
            log.warn("webhook rechazado: firma invalida o ausente")
            return HttpResponse.status(HttpStatus.FORBIDDEN)
        }

        return try {
            val encolados = entrada.encolar(cuerpo)
            log.debug("webhook aceptado, {} mensaje(s) encolado(s)", encolados)
            HttpResponse.ok("EVENT_RECEIVED")
        } catch (e: Exception) {
            // Se registra y se acepta igual: reintentar no arreglara un cuerpo
            // que no sabemos interpretar, y el bucle de reintentos de Meta si
            // hace daño.
            log.error("no se pudo encolar el webhook; se acepta para no provocar reintentos", e)
            HttpResponse.ok("EVENT_RECEIVED")
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppWebhookController::class.java)
    }
}
