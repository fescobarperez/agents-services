package com.erp_maya.agent.context.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Reglas de permisos y topes: puras, sin base de datos. */
class ToolGrantTest {

    private fun grant(pattern: String, max: String? = null) =
        ToolGrant(pattern, ToolMode.WRITE, false, max?.let(::BigDecimal))

    @Test
    fun `el patron exacto solo cubre su herramienta`() {
        val g = grant("cotizaciones.issue")
        assertTrue(g.matches("cotizaciones.issue"))
        assertFalse(g.matches("cotizaciones.preview"))
        // Que un prefijo no cuele por accidente es lo que separa "emitir" de
        // "previsualizar", que es la diferencia entre mirar y facturar.
        assertFalse(g.matches("cotizaciones.issueTodo"))
    }

    @Test
    fun `el comodin final cubre la familia`() {
        val g = grant("cotizaciones.*")
        assertTrue(g.matches("cotizaciones.issue"))
        assertTrue(g.matches("cotizaciones.preview"))
        assertFalse(g.matches("productos.search"))
    }

    @Test
    fun `el tope efectivo es el menor de los dos`() {
        val ctx = contexto(quoteLimit = "25000", toolMax = "10000")
        assertEquals(BigDecimal("10000"), ctx.limitFor(ctx.tools.first()))

        val alReves = contexto(quoteLimit = "5000", toolMax = "10000")
        assertEquals(BigDecimal("5000"), alReves.limitFor(alReves.tools.first()))
    }

    @Test
    fun `sin ningun tope definido no hay limite y eso debe notarse`() {
        val ctx = contexto(quoteLimit = null, toolMax = null)
        // Null aqui NO significa "adelante": significa que nadie fijo un tope y
        // quien llame tiene que tratarlo como escalamiento.
        assertNull(ctx.limitFor(ctx.tools.first()))
    }

    @Test
    fun `una herramienta que el agente no tiene no se resuelve`() {
        val ctx = contexto(quoteLimit = "1", toolMax = "1")
        assertNull(ctx.toolFor("nomina.read"))
    }

    private fun contexto(quoteLimit: String?, toolMax: String?) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = listOf(ToolGrant("cotizaciones.issue", ToolMode.WRITE, false, toolMax?.let(::BigDecimal))),
        policy = TenantPolicy(1, true, quoteLimit?.let(::BigDecimal), null, null, "es-GT"),
    )
}
