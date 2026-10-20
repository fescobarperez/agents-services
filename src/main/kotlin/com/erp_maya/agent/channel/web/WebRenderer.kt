package com.erp_maya.agent.channel.web

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.context.domain.Capabilities

/**
 * Traduce los eventos neutrales del turno a lo que ve el visitante de la web.
 *
 * Como en WhatsApp, las tarjetas son del panel del ERP y no se muestran: traen
 * datos internos y el texto del modelo ya resume lo importante. Los documentos
 * tampoco: sus URL son del ERP y no de acceso publico.
 */
object WebRenderer {

    fun render(eventos: List<AgentEvent>, capacidades: Capabilities): WebReply {
        val textos = mutableListOf<String>()
        val opciones = mutableListOf<WebChoice>()
        for (e in eventos) {
            when (e) {
                is AgentEvent.Text -> e.text.trim().takeIf { it.isNotEmpty() }?.let { textos += recortar(it, capacidades.maxChars) }
                is AgentEvent.Choices -> {
                    val caben = capacidades.buttons.coerceAtLeast(0)
                    if (caben == 0) {
                        // Sin botones: se degradan a lista numerada, como en WhatsApp.
                        if (e.items.isNotEmpty()) textos += e.items.mapIndexed { i, o -> "${i + 1}. ${o.label}" }.joinToString("\n")
                    } else {
                        // Solo las ultimas opciones cuentan: son las que el visitante tiene delante.
                        opciones.clear()
                        e.items.take(caben).forEach { opciones += WebChoice(it.id, recortar(it.label, LARGO_OPCION)) }
                    }
                }
                is AgentEvent.Card, is AgentEvent.Document -> Unit
            }
        }
        return WebReply(textos, opciones)
    }

    private fun recortar(texto: String, maximo: Int) =
        if (maximo <= 1 || texto.length <= maximo) texto else texto.take(maximo - 1) + "…"

    private const val LARGO_OPCION = 40
}
