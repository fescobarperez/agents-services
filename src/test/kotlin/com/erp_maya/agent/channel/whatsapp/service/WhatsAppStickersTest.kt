package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.service.PanelCards
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppDePruebas
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppStickers.Momento
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** El sticker sale solo si el modelo lo pidio, y la media se reusa. */
class WhatsAppStickersTest {

    private val ahora = Instant.parse("2026-10-07T15:00:00Z")
    private val meta = WhatsAppDePruebas()

    private fun stickers(habilitado: Boolean = true) =
        WhatsAppStickers(meta, habilitado).also { it.reloj = Clock.fixed(ahora, ZoneOffset.UTC) }

    private val texto = AgentEvent.Text("hola")

    @Test
    fun `sin expresion del modelo no hay sticker`() {
        assertNull(stickers().momento(listOf(texto)))
    }

    @Test
    fun `traduce el momento que pidio el modelo`() {
        val s = stickers()
        assertEquals(Momento.SALUDO, s.momento(listOf(texto, PanelCards.expresion("saludo"))))
        assertEquals(Momento.LISTO, s.momento(listOf(texto, PanelCards.expresion("listo"))))
    }

    @Test
    fun `el saludo va antes del texto y el listo despues`() {
        assertEquals(true, Momento.SALUDO.antesDelTexto)
        assertEquals(false, Momento.LISTO.antesDelTexto)
    }

    @Test
    fun `un momento desconocido se ignora`() {
        assertNull(stickers().momento(listOf(PanelCards.expresion("baile"))))
    }

    @Test
    fun `apagados no manda ninguno`() {
        assertNull(stickers(habilitado = false).momento(listOf(PanelCards.expresion("saludo"))))
    }

    @Test
    fun `la media se sube una vez y se renueva al vencer`() {
        val s = stickers()
        val primero = s.mediaId("pruebas", Momento.SALUDO)
        assertEquals(primero, s.mediaId("pruebas", Momento.SALUDO))
        assertEquals(listOf("tino-saludo.webp"), meta.subidas)

        s.reloj = Clock.fixed(ahora.plus(Duration.ofDays(26)), ZoneOffset.UTC)
        assertNotEquals(primero, s.mediaId("pruebas", Momento.SALUDO))
        assertEquals(2, meta.subidas.size)
    }

    @Test
    fun `olvidar fuerza una subida nueva`() {
        val s = stickers()
        s.mediaId("pruebas", Momento.LISTO)
        s.olvidar("pruebas", Momento.LISTO)
        s.mediaId("pruebas", Momento.LISTO)
        assertEquals(2, meta.subidas.size)
    }
}
