package com.erp_maya.agent.channel.web

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.InputImage
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
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.prompt.domain.SessionState
import io.micronaut.serde.ObjectMapper
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppSender
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.vision.service.LectorImagenes
import java.util.Base64
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
    private val media: WebMediaRepository,
    private val playbooks: PlaybookService,
    private val conversaciones: ConversationService,
    private val json: ObjectMapper,
    @param:Value("\${web.scopes:productos.read,clientes.read,cotizaciones.read,cotizaciones.write}") private val scopes: List<String>,
    @param:Value("\${web.rate.session-per-minute:10}") private val porSesion: Int,
    @param:Value("\${web.rate.ip-per-minute:30}") private val porIp: Int,
    @param:Value("\${web.rate.widget-per-minute:120}") private val porWidget: Int,
    /** La firma «Con tecnología de Corvex» enlaza aqui (vacio: solo texto). */
    @param:Value("\${web.powered-by-url:}") private val poweredByUrl: String = "",
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
            maxImages = fotosPorMensaje(llave, w),
            showPrices = w.showPrices,
            currency = moneda(llave, w),
            poweredByUrl = WebFirma.enlace(poweredByUrl),
        )
    }

    /**
     * El carrito de la sesion: lo que el cliente ya confirmo. El widget lo pide
     * al abrir (las respuestas del chat ya lo traen). Null si esta vacio.
     */
    open fun carrito(llave: String, origin: String?, session: String?): WebCart? {
        val w = widget(llave, origin)
        val sesion = session?.takeIf { ID.matches(it) } ?: throw WebException(400, "SESSION", "Sesión inválida")
        val espera = limites.intentar("k:$llave:$sesion", CARRITO_POR_SESION)
        if (espera > 0) throw WebException(429, "RATE_LIMIT", "Intenta de nuevo en $espera s.", espera)
        val conversacion = widgets.conversacion(w.channelId, sesion) ?: return null
        return carritoDe(llave, w, conversacion)
    }

    /**
     * Arma el carrito desde el estado de la conversacion. Si ya hay cotizacion
     * en el ERP se lee de alli (precios, IVA y total reales). Un fallo no
     * tumba la respuesta del chat: el carrito simplemente no viene.
     */
    private fun carritoDe(llave: String, w: WebWidget, conversacion: Long): WebCart? = runCatching {
        val estado = json.readValue(conversaciones.leerEstado(conversacion), SessionState::class.java)
        val borrador = estado?.borrador
        val cotizacion = borrador?.quoteId?.let { erp.getQuote(w.tenantId, conversacion, it) }
        // Con precios visibles, los del cliente: cargos y ganancia dentro de cada producto.
        val precios = cotizacion?.takeIf { w.showPrices }?.let { q ->
            runCatching { erp.getClientPrices(w.tenantId, conversacion, q.id) }
                .onFailure { log.warn("sin precios al cliente de la cotizacion {}: {}", q.id, it.message) }
                .getOrNull()
        }
        // Productos en espera (aun sin cotizacion): el ERP los valua con los cargos de la empresa.
        val previa = if (cotizacion == null && w.showPrices) {
            WebCarts.lineasPrevia(borrador).takeIf { it.isNotEmpty() }?.let { lineas ->
                runCatching { erp.previewClientPrices(w.tenantId, conversacion, lineas) }
                    .onFailure { log.warn("sin vista previa de precios de la conversacion {}: {}", conversacion, it.message) }
                    .getOrNull()
            }
        } else null
        WebCarts.armar(borrador, cotizacion, w.showPrices, moneda(llave, w), precios, previa)
    }.onFailure { log.warn("no se pudo armar el carrito de la conversacion {}: {}", conversacion, it.message) }.getOrNull()

    private fun moneda(llave: String, w: WebWidget): String =
        WebCarts.moneda(runCatching { resolver.resolve(cuenta(llave), w.tenantId).policy.locale }.getOrNull())

    /** Cuantas fotos acepta por mensaje (las del playbook), o 0 si la empresa no recibe fotos. */
    private fun fotosPorMensaje(llave: String, w: WebWidget): Int = runCatching {
        val cfg = playbooks.para(resolver.resolve(cuenta(llave), w.tenantId)).imagenes
        if (cfg.aceptar) cfg.maxPorTurno.coerceIn(1, MAX_FOTOS_MENSAJE) else 0
    }.getOrDefault(0)

    /**
     * Una foto del visitante. Se guarda con vencimiento y devuelve su id, que
     * el widget manda despues junto con el mensaje. Solo fotos (por su
     * contenido, no por lo que diga el navegador) y de hasta 5 MB.
     */
    open fun subirFoto(llave: String, origin: String?, ip: String, foto: WebMediaIn): WebMediaAck {
        val w = widget(llave, origin)
        if (fotosPorMensaje(llave, w) == 0) throw WebException(409, "NO_IMAGES", "Este chat no recibe fotos.")
        val sesion = foto.session?.takeIf { ID.matches(it) } ?: throw WebException(400, "SESSION", "Sesión inválida")
        listOf("m:$llave:$sesion" to FOTOS_POR_SESION, "m:ip:$ip" to FOTOS_POR_IP).forEach { (k, max) ->
            val espera = limites.intentar(k, max)
            if (espera > 0) throw WebException(429, "RATE_LIMIT", "Demasiadas fotos. Intenta de nuevo en $espera s.", espera)
        }
        val crudo = foto.data?.substringAfter("base64,")?.trim().orEmpty()
        if (crudo.isEmpty()) throw WebException(400, "EMPTY", "Falta la foto")
        if (crudo.length > MAX_FOTO_BYTES * 4 / 3 + 8) throw WebException(413, "TOO_LARGE", "La foto pesa más de 5 MB")
        val bytes = runCatching { Base64.getDecoder().decode(crudo) }.getOrNull()
            ?: throw WebException(400, "BAD_IMAGE", "No pude leer la foto")
        if (bytes.size > MAX_FOTO_BYTES) throw WebException(413, "TOO_LARGE", "La foto pesa más de 5 MB")
        val mime = tipoImagen(bytes) ?: throw WebException(415, "BAD_IMAGE", "Solo fotos JPG, PNG o WebP")
        val id = media.guardar(llave, sesion, KIND_IMAGEN, mime, null, bytes)
        return WebMediaAck(id, ruta(llave, id))
    }

    /** Lo que llego despues del turno (renders, avisos), desde `despues`. */
    open fun novedades(llave: String, origin: String?, session: String?, despues: Long?): WebUpdates {
        val w = widget(llave, origin)
        val sesion = session?.takeIf { ID.matches(it) } ?: throw WebException(400, "SESSION", "Sesión inválida")
        val desde = (despues ?: 0L).coerceAtLeast(0L)
        val entregas = media.entregas(llave, sesion, desde)
        val items = entregas.map { e ->
            WebUpdate(
                text = e.text?.takeIf { e.kind == KIND_TEXTO },
                attachment = e.mediaId?.takeIf { e.kind != KIND_TEXTO && e.mimeType != null }?.let { id ->
                    WebAttachment(e.kind, ruta(llave, id), e.fileName, e.mimeType, e.sizeBytes, e.text)
                },
            )
        }.filter { it.text != null || it.attachment != null }
        return WebUpdates(items, entregas.lastOrNull()?.id ?: desde, media.rendersEnCamino(w.channelId, sesion))
    }

    /**
     * Una foto o archivo del chat. Lo pide un `<img>` o un enlace, que no
     * mandan Origin: lo que protege es el id (aleatorio, 32 caracteres) y que
     * sea de este widget. Vencido, ya no existe.
     */
    open fun archivo(llave: String, id: String): WebMediaArchivo {
        if (!LLAVE.matches(llave)) throw WebException(404, "NOT_FOUND", "No encontrado")
        val a = media.leer(id)?.takeIf { it.widgetKey == llave }
            ?: throw WebException(404, "NOT_FOUND", "Este archivo ya no está disponible")
        return a
    }

    open fun enviar(llave: String, origin: String?, ip: String, mensaje: WebMessageIn): WebReply {
        val w = widget(llave, origin)
        val sesion = mensaje.session?.takeIf { ID.matches(it) } ?: throw WebException(400, "SESSION", "Sesión inválida")
        val id = mensaje.id?.takeIf { ID.matches(it) } ?: throw WebException(400, "MESSAGE_ID", "Mensaje sin id")
        val texto = mensaje.text?.trim().orEmpty()
        val fotos = fotosDelMensaje(llave, sesion, mensaje.images)
        if (texto.isEmpty() && fotos.isEmpty()) throw WebException(400, "EMPTY", "Escribe un mensaje")
        if (texto.length > LARGO_MAXIMO) throw WebException(400, "TOO_LONG", "Máximo $LARGO_MAXIMO caracteres")

        // Del mas fino al mas grueso: la sesion que insiste, la IP con muchas
        // sesiones y, como tope de gasto, el widget entero.
        listOf("s:$llave:$sesion" to porSesion, "ip:$ip" to porIp, "w:$llave" to porWidget).forEach { (k, max) ->
            val espera = limites.intentar(k, max)
            if (espera > 0) throw WebException(429, "RATE_LIMIT", "Vas muy rápido. Intenta de nuevo en $espera s.", espera)
        }

        val clave = "web:$sesion:$id"
        // Como en WhatsApp: cada foto entra al historial como marca y al
        // atender el turno la marca se cambia por su lectura en texto.
        val cuerpo = (List(fotos.size) { LectorImagenes.MARCA_FOTO } + listOfNotNull(texto.takeIf { it.isNotEmpty() }))
            .joinToString(" ")
        val peticion = TurnRequest(
            channel = CANAL,
            conversationRef = ConversationRef(externalId = sesion, account = cuenta(llave)),
            input = TurnInput(
                type = "text",
                text = cuerpo,
                images = fotos.map { InputImage(InputImage.SOURCE_WEB, it.id, it.mimeType, clave) }.takeIf { it.isNotEmpty() },
            ),
            idempotencyKey = clave,
        )
        // Desde aqui consulta el widget lo que llegue despues (renders): se
        // toma antes del turno para no perder uno que termine muy rapido.
        val cursor = media.ultimaEntrega(llave, sesion)
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
        val base = WebRenderer.render(respuesta.events, capacidades)
        val conversacion = respuesta.conversationId.filter { it.isDigit() }.toLongOrNull()
        val adjuntos = respuesta.events.filterIsInstance<AgentEvent.Document>()
            .mapNotNull { documento(llave, sesion, w.tenantId, conversacion, it) }
        return base.copy(
            attachments = adjuntos,
            pending = media.rendersEnCamino(w.channelId, sesion),
            cursor = cursor,
            cart = conversacion?.let { carritoDe(llave, w, it) },
        )
    }

    /** Las fotos que el visitante subio: de este widget, de esta sesion y vigentes. */
    private fun fotosDelMensaje(llave: String, sesion: String, ids: List<String>?): List<WebMediaArchivo> {
        val lista = ids.orEmpty().distinct()
        if (lista.size > MAX_FOTOS_MENSAJE) throw WebException(400, "TOO_MANY", "Máximo $MAX_FOTOS_MENSAJE fotos por mensaje")
        return lista.map { id ->
            media.leer(id)?.takeIf { it.widgetKey == llave && it.sessionRef == sesion && it.kind == KIND_IMAGEN }
                ?: throw WebException(400, "BAD_IMAGE", "Una de las fotos ya no está disponible. Adjúntala de nuevo.")
        }
    }

    /**
     * El PDF de la cotizacion: se baja del ERP con el bot y queda en el chat
     * como adjunto, igual que en WhatsApp. Si falla, se registra y el texto
     * del agente sigue llegando.
     */
    private fun documento(llave: String, sesion: String, tenantId: Long, conversacion: Long?, doc: AgentEvent.Document): WebAttachment? {
        val quoteId = WhatsAppSender.ID_COTIZACION.find(doc.url)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return try {
            requireNotNull(conversacion) { "conversacion sin id" }
            val pdf = erp.getQuotePdf(tenantId, conversacion, quoteId) ?: error("el ERP no encontró la cotización $quoteId")
            val id = media.guardar(llave, sesion, KIND_DOCUMENTO, doc.mediaType, doc.name, pdf)
            runCatching { erp.addQuoteNote(tenantId, conversacion, quoteId, "PDF preliminar enviado al cliente por el chat web") }
                .onFailure { log.warn("no se anotó el envío en la cotización {}: {}", quoteId, it.message) }
            WebAttachment(KIND_DOCUMENTO, ruta(llave, id), doc.name, doc.mediaType, pdf.size, WhatsAppSender.LEYENDA)
        } catch (e: Exception) {
            log.error("no se pudo preparar el PDF de la cotización {} para el chat web", quoteId, e)
            null
        }
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
        /** Consultas del carrito por sesion y minuto (cada una lee el ERP). */
        const val CARRITO_POR_SESION = 30
        const val TITULO = "Asistente virtual"
        const val LARGO_MAXIMO = 1000

        const val KIND_IMAGEN = "image"
        const val KIND_DOCUMENTO = "document"
        const val KIND_TEXTO = "text"
        const val MAX_FOTOS_MENSAJE = 5
        const val MAX_FOTO_BYTES = 5 * 1024 * 1024
        const val FOTOS_POR_SESION = 20
        const val FOTOS_POR_IP = 40

        /** Ruta del archivo, relativa a /v1/web: el widget le antepone su base. */
        fun ruta(llave: String, id: String) = "/$llave/media/$id"

        /** El tipo real de la imagen por sus primeros bytes; null si no es JPG, PNG ni WebP. */
        fun tipoImagen(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

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
