package com.erp_maya.agent.channel.web

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.domain.TurnInProgressException
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Entrada publica del canal web: valida llave, dominio y limites, y corre el
 * turno en el proceso como lo hace el consumidor de WhatsApp.
 *
 * El visitante es anonimo: la conversacion se identifica por la sesion que
 * genera el widget (`external_ref`), y el agente pide nombre y telefono cuando
 * los necesita para cotizar.
 */
@Singleton
open class WebChatService(
    private val widgets: WebWidgetRepository,
    private val turnos: AgentTurnService,
    private val resolver: ExecutionContextResolver,
    @param:Value("\${web.scopes:productos.read,clientes.read,cotizaciones.read,cotizaciones.write}") private val scopes: List<String>,
    @param:Value("\${web.rate.session-per-minute:10}") private val porSesion: Int,
    @param:Value("\${web.rate.ip-per-minute:30}") private val porIp: Int,
    @param:Value("\${web.rate.widget-per-minute:120}") private val porWidget: Int,
) {

    private val alcances = scopes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val limites = WebRateLimiter()

    /** Lo que pinta el widget al cargar. Tambien valida el dominio: si no pasa, el widget no se muestra. */
    open fun config(llave: String, origin: String?): WebConfigView {
        val w = widget(llave, origin)
        return WebConfigView(
            title = w.title ?: TITULO,
            greeting = w.greeting,
            color = w.color,
            buttons = CAPACIDADES.buttons,
            maxLength = LARGO_MAXIMO,
        )
    }

    open fun enviar(llave: String, origin: String?, ip: String, mensaje: WebMessageIn): WebReply {
        val w = widget(llave, origin)
        val sesion = mensaje.session?.takeIf { ID.matches(it) } ?: throw WebException(400, "SESSION", "Sesión inválida")
        val id = mensaje.id?.takeIf { ID.matches(it) } ?: throw WebException(400, "MESSAGE_ID", "Mensaje sin id")
        val texto = mensaje.text?.trim().orEmpty()
        if (texto.isEmpty()) throw WebException(400, "EMPTY", "Escribe un mensaje")
        if (texto.length > LARGO_MAXIMO) throw WebException(400, "TOO_LONG", "Máximo $LARGO_MAXIMO caracteres")

        // Del mas fino al mas grueso: la sesion que insiste, la IP con muchas
        // sesiones y, como tope de gasto, el widget entero.
        listOf("s:$llave:$sesion" to porSesion, "ip:$ip" to porIp, "w:$llave" to porWidget).forEach { (k, max) ->
            val espera = limites.intentar(k, max)
            if (espera > 0) throw WebException(429, "RATE_LIMIT", "Vas muy rápido. Intenta de nuevo en $espera s.", espera)
        }

        val peticion = TurnRequest(
            channel = CANAL,
            conversationRef = ConversationRef(externalId = sesion, account = cuenta(llave)),
            input = TurnInput(type = "text", text = texto),
            idempotencyKey = "web:$sesion:$id",
        )
        // La empresa sale del widget, no de lo que mande el navegador.
        val caller = CallerCredentials(clientId = CLIENTE, tenantId = w.tenantId, scopes = alcances)
        val respuesta = try {
            turnos.handle(peticion, caller)
        } catch (e: AgentUnavailableException) {
            log.warn("widget {} sin agente: {}", llave, e.reason)
            throw WebException(409, "UNAVAILABLE", "El asistente no está disponible en este momento.")
        } catch (e: TurnInProgressException) {
            throw WebException(409, "BUSY", "Estoy respondiendo tu mensaje anterior.")
        }
        val capacidades = runCatching { resolver.resolve(cuenta(llave), w.tenantId).channel.capabilities }.getOrDefault(CAPACIDADES)
        return WebRenderer.render(respuesta.events, capacidades)
    }

    private fun widget(llave: String, origin: String?): WebWidget {
        if (!LLAVE.matches(llave)) throw WebException(404, "NOT_FOUND", "Widget no encontrado")
        val w = widgets.porLlave(llave) ?: throw WebException(404, "NOT_FOUND", "Widget no encontrado")
        if (!w.active) throw WebException(409, "UNAVAILABLE", "El asistente no está disponible en este momento.")
        if (!WebOrigins.permitido(origin, w.allowedOrigins)) {
            log.warn("widget {} usado desde un dominio no permitido: {}", llave, origin)
            throw WebException(403, "ORIGIN", "Este sitio no está autorizado para usar el asistente.")
        }
        return w
    }

    companion object {
        const val CANAL = "web"
        const val CLIENTE = "web-widget"
        const val TITULO = "Asistente virtual"
        const val LARGO_MAXIMO = 1000

        /** Lo que el widget sabe pintar; va en `channels.capabilities` al crear el canal. */
        val CAPACIDADES = Capabilities(buttons = 3, markdown = false, maxChars = 2000, streaming = false)

        val LLAVE = Regex("^wk_[A-Za-z0-9]{20,40}$")
        val ID = Regex("^[A-Za-z0-9_-]{16,64}$")

        fun cuenta(llave: String) = "web:$llave"

        private val log = LoggerFactory.getLogger(WebChatService::class.java)
    }
}
