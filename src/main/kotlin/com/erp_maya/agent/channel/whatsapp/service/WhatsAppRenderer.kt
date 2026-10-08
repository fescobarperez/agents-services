package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.context.domain.Capabilities
import jakarta.inject.Singleton

/** Un mensaje ya listo para la API de WhatsApp. */
sealed interface WhatsAppOutbound {
    data class Text(val body: String) : WhatsAppOutbound
    data class Buttons(val body: String, val buttons: List<Pair<String, String>>) : WhatsAppOutbound
    data class Document(val url: String, val filename: String) : WhatsAppOutbound

    /** Sticker ya subido a Meta; lo agrega [WhatsAppSender], no el renderer. */
    data class Sticker(val mediaId: String) : WhatsAppOutbound

    /** Documento ya subido a Meta (p. ej. el PDF que se bajo del ERP). */
    data class DocumentMedia(val mediaId: String, val filename: String, val caption: String?) : WhatsAppOutbound
}

/**
 * Traduce los eventos neutrales del agente a lo que WhatsApp sabe pintar.
 *
 * Aqui —y solo aqui— vive lo especifico del canal: los tres botones como
 * maximo, el recorte a 4096 caracteres, que no hay markdown. El agente nunca
 * supo nada de esto.
 */
@Singleton
class WhatsAppRenderer {

    fun render(eventos: List<AgentEvent>, capacidades: Capabilities): List<WhatsAppOutbound> {
        val salida = mutableListOf<WhatsAppOutbound>()
        // Las opciones no viajan solas: WhatsApp exige un cuerpo junto a los
        // botones, asi que se pegan al ultimo texto en vez de inventar uno.
        var textoPendiente: String? = null

        for (evento in eventos) {
            when (evento) {
                is AgentEvent.Text -> {
                    textoPendiente?.let { salida += WhatsAppOutbound.Text(recortar(it, capacidades.maxChars)) }
                    textoPendiente = evento.text
                }

                // Las tarjetas son del panel del ERP y no se mandan al cliente:
                // traen datos internos (ids, estado, banderas) y el texto del
                // modelo ya resume lo importante; el detalle va en el PDF. La
                // expresion la convierte en sticker el sender.
                is AgentEvent.Card -> Unit

                is AgentEvent.Choices -> {
                    val cuerpo = textoPendiente ?: "Elija una opcion:"
                    textoPendiente = null
                    val botones = evento.items
                        .take(capacidades.buttons.coerceAtLeast(0))
                        .map { it.id to recortar(it.label, LARGO_BOTON) }

                    salida += if (botones.isEmpty()) {
                        // El canal no admite botones: se degradan a texto
                        // numerado en vez de perder las opciones.
                        val lista = evento.items.mapIndexed { i, o -> "${i + 1}. ${o.label}" }.joinToString("\n")
                        WhatsAppOutbound.Text(recortar("$cuerpo\n\n$lista", capacidades.maxChars))
                    } else {
                        WhatsAppOutbound.Buttons(recortar(cuerpo, LARGO_CUERPO_BOTONES), botones)
                    }
                }

                is AgentEvent.Document -> {
                    textoPendiente?.let { salida += WhatsAppOutbound.Text(recortar(it, capacidades.maxChars)) }
                    textoPendiente = null
                    salida += WhatsAppOutbound.Document(evento.url, evento.name)
                }
            }
        }

        textoPendiente?.let { salida += WhatsAppOutbound.Text(recortar(it, capacidades.maxChars)) }
        return salida
    }

    private fun recortar(texto: String, maximo: Int) =
        if (texto.length <= maximo) texto else texto.take(maximo - 1) + "…"

    private companion object {
        /** Limites de la API de WhatsApp, no elecciones nuestras. */
        const val LARGO_BOTON = 20
        const val LARGO_CUERPO_BOTONES = 1024
    }
}
