package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** El WhatsApp al asesor: el diseño ya escrito, sin crear cotizacion. */
class PlannerContactoTest {

    private fun widget(lang: String = "es") = PlannerWidget(
        channelId = 1, tenantId = 7, publicKey = "pk_x", active = true, allowedOrigins = emptyList(), name = "MV",
        title = null, accentColor = null, lang = lang, locale = "es-GT",
        projectTypes = listOf(ProjectType("pergola", "Pérgola", 501)),
        fitThresholdPct = q("90"), retentionDays = 7, maxOptions = 10, contactWhatsapp = "50212345678",
    )

    private val sesion = PlannerSessionRow("ps_AbCdEfGhIjKlMnOpQrStUvWxYz012345", 1, 7, q("20000"), "pergola",
        mapOf("uso" to "Patio trasero"), "B", Instant.now(), Instant.now())

    private fun opcion(letra: String, total: String?, render: Long? = null) = PlannerOptionRow(
        letra, null, "pergola", "Pérgola · Dos aguas, Teja", null, OptionSpec(501),
        listOf(PlannerDetail("medidas", "Medidas", "4 × 3 × 2.4 m", PlannerDetail.SIZE), PlannerDetail("cubierta", "Cubierta", "Teja")),
        if (total == null) "review" else "priced", total?.let { q(it) }, null, null, emptyList(), q("20000"),
        renderStatus = render?.let { "ready" }, renderMediaId = render?.let { "wm_$it" }, renderJobId = render,
    )

    @Test
    fun `el mensaje lleva el presupuesto, la opcion elegida, las otras, las notas y el render`() {
        val a = opcion("A", "16400.00", render = 3)
        val b = opcion("B", "24800.00", render = 9)
        val t = PlannerContacto.mensaje(widget(), sesion, listOf(a, b), b, "https://agentes/v1/planner/pk_x/media/wm_9", "B", "GTQ")
        assertTrue(t.startsWith("Hola, armé este proyecto en el planificador"), t)
        assertTrue(t.contains("Presupuesto: Q 20,000"), t)
        assertTrue(t.contains("Proyecto: Pérgola"), t)
        assertTrue(t.contains("Opción elegida: B · Pérgola · Dos aguas, Teja — Q 24,800 (se pasa por Q 4,800)"), t)
        assertTrue(t.contains("Detalles: Medidas: 4 × 3 × 2.4 m; Cubierta: Teja"), t)
        assertTrue(t.contains("Notas: uso: Patio trasero"), t)
        assertTrue(t.contains("Otras opciones que vi: A · Q 16,400"), t)
        assertTrue(t.contains("Render (B): https://agentes/v1/planner/pk_x/media/wm_9"), t)
        assertTrue(t.contains("Los precios son estimados del planificador."), t)
        assertTrue(t.contains("Ref. ABCDEFGH"), t)
        assertFalse(t.contains(sesion.id), "la sesion completa es una llave: no va en el mensaje")
    }

    @Test
    fun `sin render ni presupuesto no inventa nada`() {
        val sin = sesion.copy(budget = null, notes = emptyMap())
        val t = PlannerContacto.mensaje(widget(), sin, listOf(opcion("A", null)), opcion("A", null), null, null, "GTQ")
        assertTrue(t.contains("Opción elegida: A · Pérgola · Dos aguas, Teja — precio por confirmar"), t)
        assertFalse(t.contains("Presupuesto"), t)
        assertFalse(t.contains("Render"), t)
        assertFalse(t.contains("Otras opciones"), t)
        assertFalse(t.contains("Notas"), t)
    }

    @Test
    fun `en ingles para un planificador en ingles`() {
        val t = PlannerContacto.mensaje(widget("en"), sesion, listOf(opcion("A", "16400.00")), opcion("A", "16400.00"), null, null, "GTQ")
        assertTrue(t.startsWith("Hi, I put together this project"), t)
        assertTrue(t.contains("Chosen option: A"), t)
        assertTrue(t.contains("(fits my budget)"), t)
    }

    @Test
    fun `el enlace de WhatsApp lleva el numero y el texto codificado`() {
        val e = PlannerContacto.enlace("50212345678", "Hola, Q 20,000\nRef. ABC")
        assertTrue(e.startsWith("https://wa.me/50212345678?text="), e)
        assertFalse(e.contains("+"), "los espacios van como %20, no como +")
        assertEquals("Hola, Q 20,000\nRef. ABC", URLDecoder.decode(e.substringAfter("text="), StandardCharsets.UTF_8))
    }

    @Test
    fun `el ultimo render es el de la cola mas nueva`() {
        val r = PlannerContacto.ultimoRender(listOf(opcion("A", "1.00", render = 9), opcion("B", "1.00", render = 4), opcion("C", "1.00")))
        assertEquals("A", r?.letter)
        assertNull(PlannerContacto.ultimoRender(listOf(opcion("A", "1.00"))))
    }

    @Test
    fun `un mensaje muy largo se recorta`() {
        val largo = sesion.copy(notes = (1..60).associate { "nota$it" to "x".repeat(40) })
        val t = PlannerContacto.mensaje(widget(), largo, listOf(opcion("A", "1.00")), opcion("A", "1.00"), null, null, "GTQ")
        assertEquals(PlannerContacto.MAX_TEXTO, t.length)
        assertTrue(t.endsWith("…"))
    }
}
