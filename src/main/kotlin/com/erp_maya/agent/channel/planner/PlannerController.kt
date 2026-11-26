package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.web.WebError
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.channel.web.WebHttp
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
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
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Planificador por presupuesto: entrada publica, como el chat web.
 *
 * Sin api-key: la llave (`pk_…`) viaja en el HTML; lo que protege es el
 * dominio (`Origin`) y los limites. Todo lo que cambia algo es POST con el
 * JSON como `text/plain`, para que el navegador no haga preflight (PATCH o PUT
 * siempre lo harian).
 *
 * Sesion, opciones, precios, semaforo, conversacion y renders. Guardar y
 * cotizar (fase 4) llegan despues; el widget esconde lo que `config.features`
 * tenga apagado.
 */
@Controller("/v1/planner")
open class PlannerController(
    private val service: PlannerService,
    private val chat: PlannerChatService,
    private val json: ObjectMapper,
    @param:Value("\${web.trust-forwarded:true}") private val confiarProxy: Boolean,
) {

    /** El script se lee una vez al arrancar: es un archivo del jar (reiniciar tras cambiarlo). */
    private val script: ByteArray =
        PlannerController::class.java.getResourceAsStream("/web/planner.js")?.use { it.readBytes() } ?: ByteArray(0)

    @Get("/embed.js")
    open fun embed(): HttpResponse<ByteArray> =
        HttpResponse.ok(script)
            .contentType("application/javascript; charset=utf-8")
            .header("Cache-Control", "public, max-age=3600")
            .header("Access-Control-Allow-Origin", "*")

    /** Un mensaje del visitante: responde el agente del planificador (puede crear opciones). */
    @Post("/{key}/sessions/{sid}/messages", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun message(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @PathVariable sid: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        chat.enviar(key, origin, WebHttp.ip(request, confiarProxy), sid, leer(body, PlannerMessageIn::class.java))
    }

    /** La foto del espacio, en base64 dentro de JSON (text/plain: sin preflight). */
    @Post("/{key}/sessions/{sid}/photo", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun photo(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @PathVariable sid: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.subirFoto(key, origin, WebHttp.ip(request, confiarProxy), sid, leer(body, PlannerPhotoIn::class.java))
    }

    /** «Ver render» de una opcion: queda en la cola; el widget consulta el estado hasta que este listo. */
    @Post("/{key}/sessions/{sid}/options/{letter}/render", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun render(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @PathVariable sid: String,
        @PathVariable letter: String,
        @Header("Origin") origin: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.pedirRender(key, origin, WebHttp.ip(request, confiarProxy), sid, letter)
    }

    /** Una foto o render del planificador. La pide un `<img>` (sin Origin): la protege el id aleatorio y la llave. */
    @Get("/{key}/media/{id}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun media(@PathVariable key: String, @PathVariable id: String): HttpResponse<*> = try {
        val a = service.archivo(key, id)
        HttpResponse.ok(a.bytes)
            .contentType(a.mimeType)
            .header("Content-Disposition", "inline; filename=\"${a.id}.${a.mimeType.substringAfter('/').replace("jpeg", "jpg")}\"")
            .header("Cache-Control", "private, max-age=86400")
            .header("X-Content-Type-Options", "nosniff")
            .header("Access-Control-Allow-Origin", "*")
    } catch (e: WebException) {
        HttpResponse.status<Any>(HttpStatus.valueOf(e.status)).body(WebError(e.code, e.message))
    }

    /** El visitante toco «Contactar a un asesor» (lo manda el widget con sendBeacon, sin esperar respuesta). */
    @Post("/{key}/sessions/{sid}/contact", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun contact(
        @PathVariable key: String,
        @PathVariable sid: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.contactar(key, origin, sid, if (body.isNullOrBlank()) PlannerContactIn() else leer(body, PlannerContactIn::class.java))
        mapOf("ok" to true)
    }

    @Options("/{key}/sessions/{sid}/photo")
    @Suppress("UNUSED_PARAMETER")
    open fun photoPreflight(@PathVariable key: String, @PathVariable sid: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Options("/{key}/sessions/{sid}/messages")
    @Suppress("UNUSED_PARAMETER")
    open fun messagesPreflight(@PathVariable key: String, @PathVariable sid: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Get("/{key}/config", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun config(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        responder(origin) { service.config(key, origin) }

    @Get("/{key}/types/{code}", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun type(@PathVariable key: String, @PathVariable code: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        responder(origin) { service.tipo(key, origin, code) }

    /** Los productos de una categoria del planificador, con su foto: el visitante elige uno. */
    @Get("/{key}/categories/{categoryId}/products", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun products(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @PathVariable categoryId: Long,
        @Header("Origin") origin: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.productos(key, origin, WebHttp.ip(request, confiarProxy), categoryId)
    }

    /**
     * La foto de un producto del planificador: la pide un `<img>` (sin Origin).
     * Solo productos de sus categorias. Con el `?v=` que trae la lista se guarda
     * sin vencimiento: una foto nueva cambia la version.
     */
    @Get("/{key}/products/{productId}/image")
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun productImage(
        @PathVariable key: String,
        @PathVariable productId: Long,
        @Nullable @QueryValue size: String?,
        @Nullable @QueryValue v: Long?,
    ): HttpResponse<*> = try {
        val (foto, version) = service.fotoProducto(key, productId, size == "thumb")
        HttpResponse.ok(foto.bytes)
            .contentType(foto.mime)
            .header("Cache-Control", if (v == version) "public, max-age=31536000, immutable" else "public, max-age=300")
            .header("X-Content-Type-Options", "nosniff")
            .header("Access-Control-Allow-Origin", "*")
    } catch (e: WebException) {
        HttpResponse.status<Any>(HttpStatus.valueOf(e.status)).body(WebError(e.code, e.message))
    }

    @Post("/{key}/sessions", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun createSession(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        // Sin cuerpo tambien vale: el monto puede llegar despues.
        val entrada = if (body.isNullOrBlank()) PlannerSessionIn() else leer(body, PlannerSessionIn::class.java)
        service.crearSesion(key, origin, WebHttp.ip(request, confiarProxy), entrada)
    }

    @Get("/{key}/sessions/{sid}", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun session(@PathVariable key: String, @PathVariable sid: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        responder(origin) { service.leer(key, origin, sid) }

    @Post("/{key}/sessions/{sid}/update", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun update(
        @PathVariable key: String,
        @PathVariable sid: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.actualizar(key, origin, sid, leer(body, PlannerUpdateIn::class.java))
    }

    @Post("/{key}/sessions/{sid}/options", consumes = [MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun createOption(
        request: HttpRequest<Any>,
        @PathVariable key: String,
        @PathVariable sid: String,
        @Header("Origin") origin: String?,
        @Body body: String?,
    ): MutableHttpResponse<Any> = responder(origin) {
        service.crearOpcion(key, origin, WebHttp.ip(request, confiarProxy), sid, leer(body, PlannerOptionIn::class.java))
    }

    @Post("/{key}/sessions/{sid}/options/{letter}/activate", produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun activate(
        @PathVariable key: String,
        @PathVariable sid: String,
        @PathVariable letter: String,
        @Header("Origin") origin: String?,
    ): MutableHttpResponse<Any> = responder(origin) { service.activar(key, origin, sid, letter) }

    @Options("/{key}/sessions")
    @Suppress("UNUSED_PARAMETER")
    open fun sessionsPreflight(@PathVariable key: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Options("/{key}/sessions/{sid}/update")
    @Suppress("UNUSED_PARAMETER")
    open fun updatePreflight(@PathVariable key: String, @PathVariable sid: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    @Options("/{key}/sessions/{sid}/options")
    @Suppress("UNUSED_PARAMETER")
    open fun optionsPreflight(@PathVariable key: String, @PathVariable sid: String, @Header("Origin") origin: String?): MutableHttpResponse<Any> =
        WebHttp.preflight(origin)

    private fun <T : Any> leer(body: String?, tipo: Class<T>): T =
        runCatching { json.readValue(body ?: "", tipo) }.getOrNull()
            ?: throw WebException(400, "BAD_REQUEST", "Datos inválidos")

    private fun responder(origin: String?, accion: () -> Any): MutableHttpResponse<Any> =
        WebHttp.responder(origin, log, "el planificador", accion)

    private companion object {
        private val log = LoggerFactory.getLogger(PlannerController::class.java)
    }
}

/** Borra cada hora las sesiones vencidas del planificador (con sus opciones y eventos). */
@Singleton
open class PlannerLimpieza(private val sesiones: PlannerSessionRepository) {

    @Scheduled(fixedDelay = "\${planner.cleanup-interval:1h}", initialDelay = "\${planner.cleanup-initial-delay:7m}")
    open fun alTick() {
        runCatching { sesiones.limpiar() }
            .onSuccess { if (it > 0) log.info("planificador: {} sesión(es) vencidas borradas", it) }
            .onFailure { log.error("no se pudo limpiar las sesiones vencidas del planificador", it) }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PlannerLimpieza::class.java)
    }
}
