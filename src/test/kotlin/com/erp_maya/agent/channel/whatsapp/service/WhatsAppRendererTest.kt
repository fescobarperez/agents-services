package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.Choice
import com.erp_maya.agent.context.domain.Capabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lo especifico del canal vive aqui y en ningun otro sitio. */
class WhatsAppRendererTest {

    private val renderer = WhatsAppRenderer()
    private val whatsapp = Capabilities(buttons = 3, markdown = false, maxChars = 4096)

    @Test
    fun `un texto sale como un mensaje de texto`() {
        val salida = renderer.render(listOf(AgentEvent.Text("hola")), whatsapp)
        assertEquals(listOf(WhatsAppOutbound.Text("hola")), salida)
    }

    @Test
    fun `las opciones se pegan al texto anterior, no viajan solas`() {
        // WhatsApp exige un cuerpo junto a los botones.
        val salida = renderer.render(
            listOf(
                AgentEvent.Text("¿Confirmo la cotizacion?"),
                AgentEvent.Choices(listOf(Choice("confirm", "Confirmar"), Choice("cancel", "Cancelar"))),
            ),
            whatsapp,
        )
        val botones = assertInstanceOf(WhatsAppOutbound.Buttons::class.java, salida.single())
        assertEquals("¿Confirmo la cotizacion?", botones.body)
        assertEquals(listOf("confirm" to "Confirmar", "cancel" to "Cancelar"), botones.buttons)
    }

    @Test
    fun `nunca se mandan mas botones de los que admite el canal`() {
        val muchas = (1..7).map { Choice("op$it", "Opcion $it") }
        val salida = renderer.render(listOf(AgentEvent.Text("elija"), AgentEvent.Choices(muchas)), whatsapp)
        assertEquals(3, assertInstanceOf(WhatsAppOutbound.Buttons::class.java, salida.single()).buttons.size)
    }

    @Test
    fun `sin botones las opciones se degradan a lista numerada`() {
        // Perder las opciones seria dejar al cliente sin forma de responder.
        val sinBotones = whatsapp.copy(buttons = 0)
        val salida = renderer.render(
            listOf(AgentEvent.Text("elija"), AgentEvent.Choices(listOf(Choice("a", "Uno"), Choice("b", "Dos")))),
            sinBotones,
        )
        val texto = assertInstanceOf(WhatsAppOutbound.Text::class.java, salida.single())
        assertTrue(texto.body.contains("1. Uno"))
        assertTrue(texto.body.contains("2. Dos"))
    }

    @Test
    fun `una etiqueta larga se recorta al limite de WhatsApp`() {
        val salida = renderer.render(
            listOf(
                AgentEvent.Text("x"),
                AgentEvent.Choices(listOf(Choice("c", "Una etiqueta larguisima que no cabe en un boton"))),
            ),
            whatsapp,
        )
        val boton = assertInstanceOf(WhatsAppOutbound.Buttons::class.java, salida.single()).buttons.single()
        assertTrue(boton.second.length <= 20, "el boton mide ${boton.second.length}")
    }

    @Test
    fun `una tarjeta se convierte en texto porque WhatsApp no la pinta`() {
        val salida = renderer.render(
            listOf(
                AgentEvent.Text("Su cotizacion:"),
                AgentEvent.Card("quote_preview", linkedMapOf("number" to "COT-2026-0418", "total" to "18016.32")),
            ),
            whatsapp,
        )
        val texto = assertInstanceOf(WhatsAppOutbound.Text::class.java, salida.single())
        assertTrue(texto.body.contains("Su cotizacion:"))
        assertTrue(texto.body.contains("Number: COT-2026-0418"))
        assertTrue(texto.body.contains("Total: 18016.32"))
    }

    @Test
    fun `un texto mas largo que el limite se recorta`() {
        val largo = "a".repeat(5000)
        val texto = assertInstanceOf(
            WhatsAppOutbound.Text::class.java,
            renderer.render(listOf(AgentEvent.Text(largo)), whatsapp).single(),
        )
        assertEquals(4096, texto.body.length)
    }

    @Test
    fun `un documento sale aparte del texto`() {
        val salida = renderer.render(
            listOf(
                AgentEvent.Text("Le adjunto la cotizacion"),
                AgentEvent.Document("COT-2026-0418.pdf", "application/pdf", "https://x/y.pdf"),
            ),
            whatsapp,
        )
        assertEquals(2, salida.size)
        assertInstanceOf(WhatsAppOutbound.Text::class.java, salida[0])
        assertEquals("COT-2026-0418.pdf", assertInstanceOf(WhatsAppOutbound.Document::class.java, salida[1]).filename)
    }
}
