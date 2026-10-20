package com.erp_maya.agent.channel.web

import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Options
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.serde.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Canal web: lo unico publico del servicio junto al webhook de WhatsApp.
 *
 * Sin api-key (el navegador no guarda secretos): la llave del widget es
 * publica y lo que protege es el dominio (`Origin`) y los limites.
 *
 * CORS a mano y solo aqui: la respuesta permite exactamente el origen que
 * ya se valido. El widget manda el JSON como `text/plain` para que el
 * navegador no haga preflight; si alguien manda `application/json`, el
 * OPTIONS tambien responde.
 */
@Controller("/v1/web")
open class WebChatController(
    private val service: WebChatService,
    private val json: ObjectMapper,
    @param:Value("\${web.trust-forwarded:true}") private val confiarProxy: Boolean,
) {

    /** El widget se lee una vez al arrancar: es un archivo del jar. */
    private val script: ByteArray =
        WebChatController::class.java.getResourceAsStream("/web/widget.js")?.use { it.readBytes() } ?: ByteArray(0)

    @Get("/widget.js")
    open fun widget(): HttpResponse<ByteArray> =
        HttpResponse.ok(script)
            .contentType("application/javascript; charset=utf-8")
            .header("Cache-Control", "public, max-age=3600")
            .header("Access-Control-Allow-Origin", "*")

    @Get("/{key}/config", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun config(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        responder(origin) { service.config(key, origin) }

    @Post(
        "/{key}/messages",
        consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON],
        produces = [MediaType.APPLICATION_JSON],
    )
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun message(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        val mensaje = runCatching { json.readValue(body ?: "", WebMessageIn::class.java) }.getOrNull()
            ?: throw WebException(400, "BAD_REQUEST", "Mensaje inválido")
        service.enviar(key, origin, ip(request), mensaje)
    }

    @Options("/{key}/messages")
    @Suppress("UNUSED_PARAMETER")
    open fun preflight(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        cors(HttpResponse.noContent<Any>(), origin)
            .header("Access-Control-Allow-Methods", "POST, OPTIONS")
            .header("Access-Control-Allow-Headers", "Content-Type")
            .header("Access-Control-Max-Age", "600")

    private fun responder(origin: String?, accion: () -> Any): MutableHttpResponse<Any> = try {
        cors(HttpResponse.ok<Any>(accion()), origin)
    } catch (e: WebException) {
        val r = HttpResponse.status<Any>(HttpStatus.valueOf(e.status)).body<Any>(WebError(e.code, e.message))
        e.retryAfter?.let { r.header("Retry-After", it.toString()) }
        cors(r, origin)
    } catch (e: Exception) {
        log.error("fallo el canal web", e)
        cors(
            HttpResponse.serverError<Any>().body<Any>(WebError("ERROR", "No pude responder. Intenta de nuevo en un momento.")),
            origin,
        )
    }

    /**
     * Se refleja el origen tal cual: si no estaba permitido, el servicio ya
     * respondio 403 y el navegador igual vera el error (no hay datos que filtrar).
     */
    private fun <T : Any> cors(r: MutableHttpResponse<T>, origin: String?): MutableHttpResponse<T> {
        if (!origin.isNullOrBlank()) r.header("Access-Control-Allow-Origin", origin)
        r.header("Vary", "Origin")
        return r
    }

    private fun ip(request: HttpRequest<Any>): String {
        if (confiarProxy) {
            request.headers.get("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return request.remoteAddress.address?.hostAddress ?: "desconocida"
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WebChatController::class.java)
    }
}
