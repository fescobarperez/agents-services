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
import io.micronaut.http.annotation.QueryValue
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

    @Post(
        "/{key}/contact",
        consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON],
        produces = [MediaType.APPLICATION_JSON],
    )
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun contact(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        val form = runCatching { json.readValue(body ?: "", WebContactIn::class.java) }.getOrNull()
            ?: throw WebException(400, "BAD_REQUEST", "Formulario inválido")
        service.contacto(key, origin, ip(request), form)
    }

    /** Una foto del visitante, en base64 dentro de JSON (text/plain: sin preflight). */
    @Post(
        "/{key}/media",
        consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON],
        produces = [MediaType.APPLICATION_JSON],
    )
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun upload(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        val foto = runCatching { json.readValue(body ?: "", WebMediaIn::class.java) }.getOrNull()
            ?: throw WebException(400, "BAD_REQUEST", "Foto inválida")
        service.subirFoto(key, origin, ip(request), foto)
    }

    /** Lo que llego despues del turno (renders, avisos). El widget lo consulta mientras haya algo en camino. */
    @Get("/{key}/updates", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun updates(
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @QueryValue("session") session: String?,
        @QueryValue("after") after: Long?,
    ): MutableHttpResponse<Any> = responder(origin) { service.novedades(key, origin, session, after) }

    /** El carrito de la sesion (lo confirmado hasta ahora); `cart` null si esta vacio. */
    @Get("/{key}/cart", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun cart(
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @QueryValue("session") session: String?,
    ): MutableHttpResponse<Any> = responder(origin) { WebCartView(service.carrito(key, origin, session)) }

    /**
     * Una foto o archivo del chat (render, PDF, foto del visitante). Lo piden
     * un `<img>` o un enlace de descarga; con `download=1` se descarga.
     */
    @Get("/{key}/media/{id}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun file(
        @PathVariable key: String,
        @PathVariable id: String,
        @QueryValue("download") download: String?,
    ): HttpResponse<*> = try {
        val a = service.archivo(key, id)
        val nombre = (a.fileName ?: "${a.id}.${if (a.kind == WebChatService.KIND_DOCUMENTO) "pdf" else "jpg"}")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        HttpResponse.ok(a.bytes)
            .contentType(a.mimeType)
            .header("Content-Disposition", "${if (download == "1") "attachment" else "inline"}; filename=\"$nombre\"")
            .header("Cache-Control", "private, max-age=86400")
            .header("X-Content-Type-Options", "nosniff")
            .header("Access-Control-Allow-Origin", "*")
    } catch (e: WebException) {
        HttpResponse.status<Any>(HttpStatus.valueOf(e.status)).body(WebError(e.code, e.message))
    }

    @Options("/{key}/media")
    @Suppress("UNUSED_PARAMETER")
    open fun uploadPreflight(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Options("/{key}/contact")
    @Suppress("UNUSED_PARAMETER")
    open fun contactPreflight(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Options("/{key}/messages")
    @Suppress("UNUSED_PARAMETER")
    open fun preflight(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    private fun responder(origin: String?, accion: () -> Any): MutableHttpResponse<Any> =
        WebHttp.responder(origin, log, "el canal web", accion)

    private fun ip(request: HttpRequest<Any>): String = WebHttp.ip(request, confiarProxy)

    private companion object {
        private val log = LoggerFactory.getLogger(WebChatController::class.java)
    }
}
