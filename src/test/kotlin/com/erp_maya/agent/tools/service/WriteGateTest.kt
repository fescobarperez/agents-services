package com.erp_maya.agent.tools.service

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.context.domain.ToolGrant
import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.tools.domain.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * La compuerta de escritura.
 *
 * Cada caso describe una forma real de acabar emitiendo un documento que el
 * cliente nunca acepto. Que la compuerta escale de mas cuesta un minuto de una
 * persona; que deje pasar de mas cuesta una nota de credito y la confianza.
 */
class WriteGateTest {

    private val gate = WriteGate()
    private val emision = ToolCall(
        "cotizaciones.issue",
        mapOf("customerId" to 4821, "lines" to listOf(mapOf("productId" to 1, "quantity" to 2)), "total" to BigDecimal("1800.00")),
    )

    private fun pendiente(
        total: String = "1800.00",
        turno: Int = 4,
        tool: String = "cotizaciones.issue",
        call: ToolCall = emision,
    ) = gate.pendingFrom(tool, BigDecimal(total), turno, call)

    @Test
    fun `deja pasar cuando se cumplen todas las condiciones`() {
        val d = gate.evaluate(contexto(), emision, WriteContext(5, pendiente(), confirmed = true))
        assertEquals(GateDecision.Allow, d)
    }

    @Test
    fun `sin previsualizacion previa no se emite`() {
        val d = gate.evaluate(contexto(), emision, WriteContext(5, null, confirmed = true))
        assertEquals(GateDecision.Reason.NO_PREVIEW, escalamiento(d).reason)
    }

    @Test
    fun `mostrar y emitir en el mismo turno no cuenta como confirmado`() {
        // Sin un turno de por medio el cliente no tuvo ocasion real de decir no.
        val d = gate.evaluate(contexto(), emision, WriteContext(4, pendiente(turno = 4), confirmed = true))
        assertEquals(GateDecision.Reason.PREVIEW_SAME_TURN, escalamiento(d).reason)
    }

    @Test
    fun `si el total cambio despues del preview no se emite`() {
        val d = gate.evaluate(contexto(), emision, WriteContext(5, pendiente(total = "1750.00"), confirmed = true))
        assertEquals(GateDecision.Reason.TOTAL_CHANGED, escalamiento(d).reason)
    }

    @Test
    fun `si cambiaron las lineas no se emite aunque el total coincida`() {
        // El caso feo: mismo total, distinta mercancia.
        val otrasLineas = emision.copy(
            arguments = emision.arguments + ("lines" to listOf(mapOf("productId" to 99, "quantity" to 2))),
        )
        val d = gate.evaluate(contexto(), otrasLineas, WriteContext(5, pendiente(), confirmed = true))
        assertEquals(GateDecision.Reason.ARGS_CHANGED, escalamiento(d).reason)
    }

    @Test
    fun `sin confirmacion explicita no se emite`() {
        val d = gate.evaluate(contexto(), emision, WriteContext(5, pendiente(), confirmed = false))
        assertEquals(GateDecision.Reason.NOT_CONFIRMED, escalamiento(d).reason)
    }

    @Test
    fun `por encima del tope se escala`() {
        val caro = emision.copy(arguments = emision.arguments + ("total" to BigDecimal("30000.00")))
        val d = gate.evaluate(
            contexto(), caro,
            WriteContext(5, pendiente(total = "30000.00", call = caro), confirmed = true),
        )
        assertEquals(GateDecision.Reason.OVER_LIMIT, escalamiento(d).reason)
    }

    @Test
    fun `manda el tope mas bajo de los dos`() {
        // Empresa 25000, herramienta 5000, total 10000: cabe en el de la
        // empresa pero no en el de la herramienta, y es esta la que corta.
        val diezMil = emision.copy(arguments = emision.arguments + ("total" to BigDecimal("10000.00")))
        val pend = pendiente(total = "10000.00", call = diezMil)

        val cortaLaHerramienta = contexto(quoteLimit = "25000", toolMax = "5000")
        assertEquals(
            GateDecision.Reason.OVER_LIMIT,
            escalamiento(gate.evaluate(cortaLaHerramienta, diezMil, WriteContext(5, pend, true))).reason,
        )

        // Y al reves: herramienta generosa, empresa restrictiva.
        val cortaLaEmpresa = contexto(quoteLimit = "5000", toolMax = "25000")
        assertEquals(
            GateDecision.Reason.OVER_LIMIT,
            escalamiento(gate.evaluate(cortaLaEmpresa, diezMil, WriteContext(5, pend, true))).reason,
        )

        // Con ambos por encima, pasa.
        val ambosAmplios = contexto(quoteLimit = "25000", toolMax = "25000")
        assertEquals(GateDecision.Allow, gate.evaluate(ambosAmplios, diezMil, WriteContext(5, pend, true)))
    }

    @Test
    fun `sin ningun tope configurado NO se ejecuta`() {
        // Que nadie haya puesto limite no es permiso ilimitado.
        val ctx = contexto(quoteLimit = null, toolMax = null)
        val d = gate.evaluate(ctx, emision, WriteContext(5, pendiente(), confirmed = true))
        assertEquals(GateDecision.Reason.NO_LIMIT_SET, escalamiento(d).reason)
    }

    @Test
    fun `una herramienta no concedida no se ejecuta`() {
        val ctx = contexto(conEmision = false)
        val d = gate.evaluate(ctx, emision, WriteContext(5, pendiente(), confirmed = true))
        assertEquals(GateDecision.Reason.NOT_GRANTED, escalamiento(d).reason)
    }

    @Test
    fun `las lecturas no pasan por la compuerta`() {
        val lectura = ToolCall("productos.search", mapOf("query" to "cemento"))
        assertEquals(GateDecision.Allow, gate.evaluate(contexto(), lectura, WriteContext(1, null, false)))
    }

    @Test
    fun `una escritura sin total declarado no se ejecuta`() {
        val sinTotal = ToolCall("cotizaciones.issue", mapOf("lines" to emptyList<Any>()))
        val d = gate.evaluate(contexto(), sinTotal, WriteContext(5, pendiente(), confirmed = true))
        assertEquals(GateDecision.Reason.NO_PREVIEW, escalamiento(d).reason)
    }

    @Test
    fun `auto_approve salta la confirmacion pero no el tope`() {
        val ctx = contexto(autoApprove = true)
        assertEquals(
            GateDecision.Allow,
            gate.evaluate(ctx, emision, WriteContext(5, pendiente(), confirmed = false)),
        )

        val caro = emision.copy(arguments = emision.arguments + ("total" to BigDecimal("99999.00")))
        val d = gate.evaluate(ctx, caro, WriteContext(5, pendiente(total = "99999.00", call = caro), confirmed = false))
        assertEquals(GateDecision.Reason.OVER_LIMIT, escalamiento(d).reason)
    }

    private fun escalamiento(d: GateDecision) =
        assertInstanceOf(GateDecision.Escalate::class.java, d)

    private fun contexto(
        quoteLimit: String? = "25000",
        toolMax: String? = "25000",
        conEmision: Boolean = true,
        autoApprove: Boolean = false,
    ) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = buildList {
            add(ToolGrant("productos.search", ToolMode.READ, true, null))
            if (conEmision) {
                add(ToolGrant("cotizaciones.issue", ToolMode.WRITE, autoApprove, toolMax?.let(::BigDecimal)))
            }
        },
        policy = TenantPolicy(1, true, quoteLimit?.let(::BigDecimal), null, null, "es-GT"),
    )
}
