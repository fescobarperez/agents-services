package com.erp_maya.agent.channel.web

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import org.slf4j.Logger

/**
 * Lo comun de las entradas publicas del navegador (chat web y planificador):
 * CORS a mano, la IP del visitante y la traduccion de errores.
 *
 * Sin api-key: lo que protege es el `Origin` (ya validado por el servicio
 * antes de responder) y los limites. Los widgets mandan el JSON como
 * `text/plain` para que el navegador no haga preflight.
 */
object WebHttp {

    /**
     * Corre `accion` y responde 200 con su resultado; un [WebException] sale
     * con su estado y codigo, y cualquier otra falla como 500 sin detalles.
     */
    fun responder(origin: String?, log: Logger, contexto: String, accion: () -> Any): MutableHttpResponse<Any> = try {
        cors(HttpResponse.ok<Any>(accion()), origin)
    } catch (e: WebException) {
        val r = HttpResponse.status<Any>(HttpStatus.valueOf(e.status)).body<Any>(WebError(e.code, e.message))
        e.retryAfter?.let { r.header("Retry-After", it.toString()) }
        cors(r, origin)
    } catch (e: Exception) {
        log.error("fallo {}", contexto, e)
        cors(
            HttpResponse.serverError<Any>().body<Any>(WebError("ERROR", "No pude responder. Intenta de nuevo en un momento.")),
            origin,
        )
    }

    /**
     * Se refleja el origen tal cual: si no estaba permitido, el servicio ya
     * respondio 403 y el navegador igual vera el error (no hay datos que filtrar).
     */
    fun <T : Any> cors(r: MutableHttpResponse<T>, origin: String?): MutableHttpResponse<T> {
        if (!origin.isNullOrBlank()) r.header("Access-Control-Allow-Origin", origin)
        r.header("Vary", "Origin")
        return r
    }

    /** Respuesta al OPTIONS de un POST (solo llega si alguien manda `application/json`). */
    fun preflight(origin: String?): MutableHttpResponse<Any> =
        cors(HttpResponse.noContent<Any>(), origin)
            .header("Access-Control-Allow-Methods", "POST, OPTIONS")
            .header("Access-Control-Allow-Headers", "Content-Type")
            .header("Access-Control-Max-Age", "600")

    /** La IP del visitante: detras del proxy viene en X-Forwarded-For. */
    fun ip(request: HttpRequest<*>, confiarProxy: Boolean): String {
        if (confiarProxy) {
            request.headers.get("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return request.remoteAddress.address?.hostAddress ?: "desconocida"
    }
}

/**
 * La firma «Con tecnología de Corvex» de los widgets (chat y planificador):
 * enlaza a `web.powered-by-url` si es https; si no, queda como texto.
 */
object WebFirma {
    fun enlace(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && it.length <= 200 && it.none(Char::isWhitespace) }
}
