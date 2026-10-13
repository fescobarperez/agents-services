package com.erp_maya.agent.quote

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ErpQuoteItem
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.StockInfo
import com.erp_maya.agent.playbook.domain.Limites
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.QuoteDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Envío de la cotización preliminar (`cotizacion.enviar`).
 *
 * Regla de negocio: el cliente **siempre** recibe el PDF y la cotización
 * **siempre** pasa a prospecto para que el asesor la vea en su lista. Superar
 * `limites.monto_maximo` ya no frena el envío: solo deja una nota de revisión
 * prioritaria en la bitácora del ERP.
 */
class QuoteDraftEnvioTest {

    /** ERP en memoria: solo lo que usa el envío. */
    private class ErpEnMemoria(var cotizacion: ErpQuote, val fallaNota: Boolean = false) : ErpClient {
        val notas = mutableListOf<String>()
        var finalizada = false

        override fun getQuote(tenantId: Long, conversationId: Long, quoteId: Long) = cotizacion
        override fun finalizeQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote {
            finalizada = true
            cotizacion = cotizacion.copy(status = "prospecto")
            return cotizacion
        }
        override fun addQuoteNote(tenantId: Long, conversationId: Long, quoteId: Long, note: String) {
            if (fallaNota) throw ErpException("ERP caído")
            notas += note
        }

        override fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int): List<ProductSummary> = error("no aplica")
        override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo? = error("no aplica")
        override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary? = error("no aplica")
        override fun previewQuote(tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>): QuotePreview = error("no aplica")
        override fun issueQuote(tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>, expectedTotal: BigDecimal, idempotencyKey: String): IssuedQuote = error("no aplica")
        override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo? = error("no aplica")
    }

    private val contexto = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
    )

    private val borrador = QuoteDraft(customerId = 7, customerName = "Cristina", quoteId = 9, quoteNumber = "COT-000009")

    private fun cotizacion(total: String, vararg items: ErpQuoteItem, priceDisplay: String? = null) = ErpQuote(
        id = 9, docNumber = "COT-000009", status = "abierta", total = BigDecimal(total),
        items = items.toList(), priceDisplay = priceDisplay,
    )

    private fun tope(monto: String) = Playbook(limites = Limites(montoMaximo = BigDecimal(monto)))

    @Test
    fun `por encima del monto maximo igual se envia y queda marcada para revision prioritaria`() {
        val erp = ErpEnMemoria(cotizacion("29120.00"))
        val paso = QuoteDraftService(erp).enviar(contexto, 1, borrador, tope("25000"))

        assertTrue(erp.finalizada, "debe pasar a prospecto para que el asesor la vea")
        assertEquals(1, paso.documentos.size, "el cliente siempre recibe el PDF")
        assertEquals("/api/quotes/9/pdf", paso.documentos.single().url)
        assertTrue(erp.notas.single().startsWith("Revisión prioritaria"))
        assertTrue((paso.datos["instruccion"] as String).contains("prioritaria"))
        assertNull(paso.borrador?.quoteId, "el agente suelta la cotización enviada")
    }

    @Test
    fun `por debajo del monto maximo no deja nota`() {
        val erp = ErpEnMemoria(cotizacion("1800.00"))
        val paso = QuoteDraftService(erp).enviar(contexto, 1, borrador, tope("25000"))

        assertTrue(erp.notas.isEmpty())
        assertFalse((paso.datos["instruccion"] as String).contains("prioritaria"))
        assertEquals(true, paso.datos["preliminar"])
    }

    @Test
    fun `si la nota falla el PDF se envia igual`() {
        val erp = ErpEnMemoria(cotizacion("29120.00"), fallaNota = true)
        val paso = QuoteDraftService(erp).enviar(contexto, 1, borrador, tope("25000"))
        assertEquals(1, paso.documentos.size)
    }

    @Test
    fun `sin un si claro del cliente no se envia`() {
        val erp = ErpEnMemoria(cotizacion("1800.00"))
        val error = assertThrows(ErpException::class.java) {
            QuoteDraftService(erp).enviar(contexto, 1, borrador, Playbook(), confirmado = false)
        }
        assertTrue(error.message!!.startsWith("SIN_CONFIRMACION"))
        assertFalse(erp.finalizada)
    }

    @Test
    fun `sin cotizacion creada no hay nada que enviar`() {
        assertThrows(ErpException::class.java) {
            QuoteDraftService(ErpEnMemoria(cotizacion("1"))).enviar(contexto, 1, QuoteDraft(customerId = 7))
        }
    }

    // ── lo que el modelo recibe de cada línea ───────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun lineas(paso: DraftStep) = paso.datos["lineas"] as List<Map<String, Any?>>

    @Test
    fun `la linea por cotizar avisa que el asesor pone el precio`() {
        val erp = ErpEnMemoria(cotizacion(
            "22406.25",
            ErpQuoteItem(1, productName = "Pérgola", quantity = BigDecimal.ONE, unitPrice = BigDecimal("22406.25"),
                lineTotal = BigDecimal("22406.25"), customSize = true, sizePricing = "calculated"),
            ErpQuoteItem(2, productName = "Pérgola grande", quantity = BigDecimal.ONE, unitPrice = BigDecimal.ZERO,
                lineTotal = BigDecimal.ZERO, customSize = true, sizePricing = "review"),
            ErpQuoteItem(3, productName = "Instalación", quantity = BigDecimal.ONE),
        ))
        val l = lineas(QuoteDraftService(erp).enviar(contexto, 1, borrador))

        assertTrue((l[0]["precio_medida"] as String).startsWith("calculado por medida"))
        assertTrue((l[1]["precio_medida"] as String).startsWith("por cotizar"))
        assertNull(l[2]["precio_medida"])
    }

    @Test
    fun `con precio global el modelo no recibe precios por linea ni lineas internas`() {
        val erp = ErpEnMemoria(cotizacion(
            "8995.00",
            ErpQuoteItem(1, productName = "Closet", quantity = BigDecimal.ONE, unitPrice = BigDecimal("8995"),
                lineTotal = BigDecimal("8995")),
            ErpQuoteItem(2, productName = "Mano de obra", quantity = BigDecimal("5"), showToClient = false),
            priceDisplay = "global",
        ))
        val paso = QuoteDraftService(erp).enviar(contexto, 1, borrador)
        val l = lineas(paso)

        assertEquals(listOf("Closet"), l.map { it["producto"] })
        assertNull(l.single()["precio_unitario"])
        assertNull(l.single()["total_linea"])
        assertEquals("Q 8,995.00", paso.datos["total"])
    }
}
