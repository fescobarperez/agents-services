package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.context.domain.Capabilities
import jakarta.inject.Singleton

/** Un mensaje ya listo para la API de WhatsApp. */
sealed interface WhatsAppOutbound {
    data class Text(val body: String) : WhatsAppOutbound
    data class Buttons(val body: String, val buttons: List<Pair<String, String>>) : WhatsAppOutbound
    data class Document(val url: String, val filename: String) : WhatsAppOutbound
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

                // Los resultados del catalogo son del panel del ERP: en WhatsApp
                // el texto del modelo ya los enumera y repetirlos es ruido.
                is AgentEvent.Card -> if (evento.card in SOLO_PANEL) Unit else {
                    val cuerpo = (textoPendiente?.let { "$it\n\n" } ?: "") + formatear(evento)
                    textoPendiente = cuerpo
                }

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

    /** Una tarjeta en WhatsApp es texto: no hay componente para pintarla. */
    private fun formatear(card: AgentEvent.Card): String {
        // Solo campos simples: listas y mapas son para canales con panel.
        val lineas = card.data.entries
            .filter { it.value != null && it.value !is Collection<*> && it.value !is Map<*, *> }
            .joinToString("\n") { "${etiqueta(it.key)}: ${it.value}" }
        return lineas
    }

    private fun etiqueta(clave: String) = clave.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun recortar(texto: String, maximo: Int) =
        if (texto.length <= maximo) texto else texto.take(maximo - 1) + "…"

    private companion object {
        /** Limites de la API de WhatsApp, no elecciones nuestras. */
        const val LARGO_BOTON = 20
        const val LARGO_CUERPO_BOTONES = 1024

        /** Tarjetas que solo tienen sentido en un canal con panel. */
        val SOLO_PANEL = setOf("product_results")
    }
}
