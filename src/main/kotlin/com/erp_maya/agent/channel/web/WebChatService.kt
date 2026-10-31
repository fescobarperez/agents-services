package com.erp_maya.agent.channel.web

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.LeadWrite
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
    private val erp: ErpClient,
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

    /**
     * Formulario de contacto de la pagina de la empresa. Usa la misma llave y
     * los mismos dominios que el chat; el prospecto queda en el ERP de la
     * empresa del widget (nunca la que diga el navegador) y el ERP avisa por correo.
     */
    open fun contacto(llave: String, origin: String?, ip: String, form: WebContactIn): WebContactAck {
        val w = widget(llave, origin)
        // Un bot que llena el campo oculto recibe "ok" y no se guarda nada:
        // si le dijeramos que fallo, cambiaria de estrategia.
        if (!form.website.isNullOrBlank()) {
            log.info("formulario de contacto descartado (trampa) widget={} ip={}", llave, ip)
            return WebContactAck(true)
        }
        listOf("c:ip:$ip" to CONTACTO_POR_IP, "c:w:$llave" to CONTACTO_POR_WIDGET).forEach { (k, max) ->
            val espera = limites.intentar(k, max)
            if (espera > 0) throw WebException(429, "RATE_LIMIT", "Demasiados envíos. Intenta de nuevo en $espera s.", espera)
        }
        val nombre = form.name?.trim().orEmpty()
        val correo = form.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val telefono = form.phone?.trim()?.takeIf { it.isNotEmpty() }
        val mensaje = form.message?.trim().orEmpty()
        if (nombre.isEmpty() || nombre.length > 150) throw WebException(400, "NAME", "Escribe tu nombre")
        if (correo == null && telefono == null) throw WebException(400, "CONTACT", "Déjanos un correo o un teléfono")
        if (correo != null && (correo.length > 150 || !CORREO.matches(correo))) throw WebException(400, "EMAIL", "El correo no es válido")
        if (telefono != null && !TELEFONO.matches(telefono)) throw WebException(400, "PHONE", "El teléfono no es válido")
        if (mensaje.isEmpty()) throw WebException(400, "MESSAGE", "Escribe tu mensaje")
        if (mensaje.length > LARGO_MAXIMO) throw WebException(400, "TOO_LONG", "Máximo $LARGO_MAXIMO caracteres")

        val prospecto = LeadWrite(
            name = nombre,
            companyName = form.company?.trim()?.takeIf { it.isNotEmpty() }?.take(150),
            phone = telefono,
            email = correo,
            interest = form.interest?.trim()?.takeIf { it.isNotEmpty() }?.take(80),
            message = mensaje,
            wantsDemo = false,
            source = "web_form",
        )
        try {
            erp.createLead(w.tenantId, 0L, prospecto)
        } catch (e: ErpException) {
            log.error("no se pudo registrar el contacto del widget {}: {}", llave, e.message)
            throw WebException(502, "UNAVAILABLE", "No pudimos enviar tu mensaje. Intenta de nuevo en un momento.")
        }
        return WebContactAck(true)
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
        val CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val TELEFONO = Regex("^[+0-9 ()-]{7,25}$")

        /** Envios del formulario por minuto: una persona manda uno; mas que esto es abuso. */
        const val CONTACTO_POR_IP = 3
        const val CONTACTO_POR_WIDGET = 30

        fun cuenta(llave: String) = "web:$llave"

        private val log = LoggerFactory.getLogger(WebChatService::class.java)
    }
}
