package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.service.PanelCards
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Los stickers animados de Tino en WhatsApp.
 *
 * CUÁNDO va uno lo decide el modelo con la herramienta `tino.sticker`, que
 * deja en el turno una tarjeta neutral `expression`. Aquí solo se traduce ese
 * momento a su archivo, se decide si va antes o después del texto y se
 * mantiene la media subida a Meta. Un sticker es adorno: si algo falla se
 * registra y la respuesta sale igual.
 *
 * Los `.webp` viven en `resources/stickers/`. Se suben la primera vez que se
 * necesitan y se reusa el media id; Meta lo borra a los 30 días, así que se
 * vuelve a subir antes.
 */
@Singleton
open class WhatsAppStickers(
    private val cliente: WhatsAppCloudClient,
    @param:Value("\${whatsapp.stickers.enabled:true}") private val habilitado: Boolean,
) {

    enum class Momento(val clave: String, val archivo: String, val antesDelTexto: Boolean) {
        SALUDO("saludo", "tino-saludo.webp", true),
        BUSCANDO("buscando", "tino-buscando.webp", true),
        LISTO("listo", "tino-listo.webp", false),
    }

    private data class Subida(val mediaId: String, val vence: Instant)

    /** Por phone_number_id + sticker: la media pertenece al número que la subió. */
    private val subidas = ConcurrentHashMap<String, Subida>()

    internal var reloj: Clock = Clock.systemUTC()

    /** El sticker que el modelo pidió en este turno, si pidió uno. */
    open fun momento(eventos: List<AgentEvent>): Momento? {
        if (!habilitado) return null
        val clave = eventos.filterIsInstance<AgentEvent.Card>()
            .firstOrNull { it.card == PanelCards.EXPRESSION }
            ?.data?.get("moment") as? String
            ?: return null
        return Momento.entries.firstOrNull { it.clave == clave }
            ?: null.also { log.warn("momento de sticker desconocido: {}", clave) }
    }

    /** El media id vigente del sticker para ese número; lo sube si hace falta. */
    open fun mediaId(phoneNumberId: String, momento: Momento): String {
        val clave = "$phoneNumberId/${momento.name}"
        val ahora = reloj.instant()
        return subidas.compute(clave) { _, previa ->
            if (previa != null && previa.vence.isAfter(ahora)) previa
            else Subida(subir(phoneNumberId, momento), ahora.plus(VIGENCIA))
        }!!.mediaId
    }

    /** Meta rechazó el envío: el id pudo vencer antes de tiempo, se sube de nuevo la próxima vez. */
    open fun olvidar(phoneNumberId: String, momento: Momento) {
        subidas.remove("$phoneNumberId/${momento.name}")
    }

    private fun subir(phoneNumberId: String, momento: Momento): String {
        val ruta = "stickers/${momento.archivo}"
        val bytes = javaClass.classLoader.getResourceAsStream(ruta)?.use { it.readBytes() }
            ?: error("No está el recurso $ruta")
        val id = cliente.subirMedia(phoneNumberId, bytes, "image/webp", momento.archivo)
        log.info("sticker {} subido a Meta para {}", momento.archivo, phoneNumberId)
        return id
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WhatsAppStickers::class.java)

        /** Meta guarda la media 30 días; se renueva con margen. */
        val VIGENCIA: Duration = Duration.ofDays(25)
    }
}
