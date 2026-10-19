package com.erp_maya.agent.quote

import com.erp_maya.agent.channel.whatsapp.client.WhatsAppDePruebas
import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.render.RenderResultado
import com.erp_maya.agent.render.ResultadosRender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Aprobar el render y guardarlo en la cotizacion, sin red ni base. */
class AdjuntoDisenoTest {

    private val resultados = object : ResultadosRender {
        var estado = "done"
        override fun resultado(id: Long) = RenderResultado(id, 1, estado, "media-render-$id", 2, 3)
    }
    private val meta = WhatsAppDePruebas()
    private val erp = ErpDePruebas()
    private val adjunto = AdjuntoDiseno(resultados, meta, erp)

    private val ficha = FichaDiseno(
        tipo = "Pérgola", material = "pino", medidas = Dimensions(BigDecimal("2.5"), BigDecimal("3"), BigDecimal("4.5")),
        medidasConfirmadas = true, detalles = mapOf("aguas" to "1"), renders = 2, ultimoRender = 41,
    )

    @Test
    fun `sin cotizacion se anota la aprobacion y se guarda cuando la cotizacion exista`() {
        val paso = adjunto.aprobar(contexto(), 1, QuoteDraft(diseno = ficha), confirmado = true)
        val f = paso.borrador!!.diseno!!
        assertEquals(41L, f.renderAprobado)
        assertFalse(f.disenoAdjunto)
        assertTrue(erp.disenos.isEmpty())

        val conCotizacion = paso.borrador!!.copy(quoteId = 900, quoteNumber = "COT-000900")
        val despues = adjunto.adjuntarSiCorresponde(contexto(), 1, conCotizacion)!!
        assertTrue(despues.diseno!!.disenoAdjunto)
        val guardado = erp.disenos[900L]
        assertNotNull(guardado)
        assertEquals("Pérgola", guardado!!.spec.tipo)
        assertEquals(2, guardado.renderNumber)
        assertNotNull(guardado.imageBase64)
        assertEquals(listOf("media-render-41"), meta.descargas)
    }

    @Test
    fun `con cotizacion se guarda en el momento y no se repite`() {
        val b = QuoteDraft(quoteId = 7, quoteNumber = "COT-7", diseno = ficha)
        val paso = adjunto.aprobar(contexto(), 1, b, confirmado = true)
        assertTrue(paso.borrador!!.diseno!!.disenoAdjunto)
        assertEquals(1, erp.disenos.size)
        adjunto.adjuntarSiCorresponde(contexto(), 1, paso.borrador)
        assertEquals(1, meta.descargas.size)
    }

    @Test
    fun `exige el si del cliente y que la imagen ya haya llegado`() {
        val b = QuoteDraft(diseno = ficha)
        assertThrows(IllegalArgumentException::class.java) { adjunto.aprobar(contexto(), 1, b, confirmado = false) }
        resultados.estado = "pending"
        assertThrows(IllegalArgumentException::class.java) { adjunto.aprobar(contexto(), 1, b, confirmado = true) }
        assertThrows(IllegalArgumentException::class.java) {
            adjunto.aprobar(contexto(), 1, QuoteDraft(diseno = ficha.copy(ultimoRender = null)), confirmado = true)
        }
    }

    @Test
    fun `si el ERP falla no se marca como guardado y se reintenta despues`() {
        meta.fallar = true
        val b = QuoteDraft(quoteId = 7, diseno = ficha.copy(renderAprobado = 41))
        val r = adjunto.adjuntarSiCorresponde(contexto(), 1, b)!!
        assertFalse(r.diseno!!.disenoAdjunto)
        meta.fallar = false
        assertTrue(adjunto.adjuntarSiCorresponde(contexto(), 1, r)!!.diseno!!.disenoAdjunto)
    }

    private fun contexto() = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:pruebas", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
    )
}
