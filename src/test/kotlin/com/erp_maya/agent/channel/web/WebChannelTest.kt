package com.erp_maya.agent.channel.web

import com.erp_maya.agent.admin.WebChannelRules
import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.Choice
import com.erp_maya.agent.context.domain.Capabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lo que protege y traduce el canal web, sin base de datos. */
class WebChannelTest {

    // ── dominios ────────────────────────────────────────────────────────────

    @Test
    fun `normaliza dominios y rechaza los que no son origenes`() {
        assertEquals("https://www.ejemplo.com", WebOrigins.normalizar(" HTTPS://www.Ejemplo.com/ "))
        assertEquals("https://*.ejemplo.com", WebOrigins.normalizar("https://*.ejemplo.com"))
        assertEquals("http://localhost:5173", WebOrigins.normalizar("http://localhost:5173"))
        assertEquals("https://ejemplo.com:8443", WebOrigins.normalizar("https://ejemplo.com:8443"))
        assertNull(WebOrigins.normalizar("http://ejemplo.com"), "http solo en localhost")
        assertNull(WebOrigins.normalizar("https://ejemplo.com/tienda"), "sin ruta")
        assertNull(WebOrigins.normalizar("ejemplo.com"), "con esquema")
        assertNull(WebOrigins.normalizar("https://ejemplo"), "dominio con punto")
        assertNull(WebOrigins.normalizar("https://*.com"), "comodin sobre un TLD")
        assertNull(WebOrigins.normalizar("https://ejemplo.com:99999"))
    }

    @Test
    fun `el origen tiene que coincidir exacto o por subdominio`() {
        val permitidos = listOf("https://www.ejemplo.com", "https://*.tienda.gt")
        assertTrue(WebOrigins.permitido("https://www.ejemplo.com", permitidos))
        assertTrue(WebOrigins.permitido("https://WWW.ejemplo.com", permitidos))
        assertTrue(WebOrigins.permitido("https://shop.tienda.gt", permitidos))
        assertTrue(WebOrigins.permitido("https://a.b.tienda.gt", permitidos))
        assertFalse(WebOrigins.permitido("https://tienda.gt", permitidos), "el comodin no incluye el dominio base")
        assertFalse(WebOrigins.permitido("https://ejemplo.com", permitidos))
        assertFalse(WebOrigins.permitido("http://www.ejemplo.com", permitidos))
        assertFalse(WebOrigins.permitido("https://www.ejemplo.com:8443", permitidos))
        assertFalse(WebOrigins.permitido("https://eviltienda.gt", permitidos))
        assertFalse(WebOrigins.permitido(null, permitidos))
        assertFalse(WebOrigins.permitido("null", permitidos))
        assertFalse(WebOrigins.permitido("https://www.ejemplo.com", emptyList()), "sin dominios no se acepta ninguno")
    }

    // ── limites ─────────────────────────────────────────────────────────────

    @Test
    fun `el limite corta al pasar el maximo y se reinicia al minuto`() {
        var ahora = 0L
        val l = WebRateLimiter { ahora }
        repeat(3) { assertEquals(0L, l.intentar("s", 3)) }
        val espera = l.intentar("s", 3)
        assertTrue(espera in 1L..60L, "espera $espera")
        assertEquals(0L, l.intentar("otra", 3), "cada llave cuenta aparte")
        ahora += WebRateLimiter.VENTANA_MS
        assertEquals(0L, l.intentar("s", 3))
    }

    // ── respuesta al visitante ──────────────────────────────────────────────

    @Test
    fun `al visitante solo le llegan textos y opciones`() {
        val r = WebRenderer.render(
            listOf(
                AgentEvent.Text("Hola"),
                AgentEvent.Card("quote_preview", mapOf("id" to 9, "estimated" to true)),
                AgentEvent.Document("COT-1.pdf", "application/pdf", "http://erp.interno/cot/1.pdf"),
                AgentEvent.Choices(listOf(Choice("a", "Uno"), Choice("b", "Dos"), Choice("c", "Tres"), Choice("d", "Cuatro"))),
            ),
            Capabilities(buttons = 3, maxChars = 2000),
        )
        assertEquals(listOf("Hola"), r.messages)
        assertEquals(listOf("a", "b", "c"), r.choices.map { it.id })
    }

    @Test
    fun `sin botones las opciones se vuelven lista y el texto largo se recorta`() {
        val r = WebRenderer.render(
            listOf(AgentEvent.Text("x".repeat(50)), AgentEvent.Choices(listOf(Choice("a", "Uno"), Choice("b", "Dos")))),
            Capabilities(buttons = 0, maxChars = 10),
        )
        assertEquals("xxxxxxxxx…", r.messages[0])
        assertEquals("1. Uno\n2. Dos", r.messages[1])
        assertTrue(r.choices.isEmpty())
    }

    // ── consola ─────────────────────────────────────────────────────────────

    @Test
    fun `un widget nuevo necesita nombre y al menos un dominio valido`() {
        val (vacio, f) = WebChannelRules.validar(emptyMap(), null)
        assertNull(vacio)
        assertTrue(f.containsKey("name"))
        assertTrue(f.containsKey("allowedOrigins"))

        val (malo, f2) = WebChannelRules.validar(mapOf("name" to "Web", "allowedOrigins" to listOf("ejemplo.com")), null)
        assertNull(malo)
        assertTrue(f2["allowedOrigins"]!!.contains("ejemplo.com"))

        val (ok, f3) = WebChannelRules.validar(
            mapOf("name" to " Web ", "allowedOrigins" to listOf("https://www.ejemplo.com/", "https://www.ejemplo.com"), "color" to "#a1b2c3"),
            null,
        )
        assertTrue(f3.isEmpty(), f3.toString())
        assertNotNull(ok)
        assertEquals("Web", ok!!.name)
        assertEquals(listOf("https://www.ejemplo.com"), ok.origins)
        assertEquals("#A1B2C3", ok.color)
    }

    @Test
    fun `al editar se conserva lo que no viene y se valida el color`() {
        val actual = WebChannelRules.Draft("Web", listOf("https://www.ejemplo.com"), "Hola", null, "#731C37")
        val (d, f) = WebChannelRules.validar(mapOf("title" to "Ventas"), actual)
        assertTrue(f.isEmpty())
        assertEquals(listOf("https://www.ejemplo.com"), d!!.origins)
        assertEquals("Ventas", d.title)
        assertEquals("#731C37", d.color)

        val (_, f2) = WebChannelRules.validar(mapOf("color" to "rojo"), actual)
        assertTrue(f2.containsKey("color"))
    }

    @Test
    fun `la llave publica tiene el formato que acepta la entrada`() {
        val a = WebChannelRules.nuevaLlave()
        val b = WebChannelRules.nuevaLlave()
        assertTrue(WebChatService.LLAVE.matches(a), a)
        assertTrue(a != b)
    }
}
