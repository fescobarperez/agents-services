package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_DECK
import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_PERGOLA
import com.erp_maya.agent.channel.planner.PlannerFixtures.pergola
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpPreviewPrice
import com.erp_maya.agent.erp.domain.ErpPreviewScenario
import com.erp_maya.agent.erp.domain.ErpScenarioPrices
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.StockInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** La opcion y sus variantes van en un solo lote al ERP y el precio sale de alla. */
class PlannerPricingTest {

    /** ERP falso: solo la vista previa por lotes, con la tarifa de la pergola. */
    private class Erp(val rechazo: String? = null, val sinPrecio: Boolean = false) : ErpClient {
        val lotes = mutableListOf<List<ErpPreviewScenario>>()

        override fun previewClientPricesBatch(tenantId: Long, conversationId: Long, scenarios: List<ErpPreviewScenario>): List<ErpScenarioPrices> {
            lotes += scenarios
            return scenarios.map { s ->
                if (s.key == "base" && rechazo != null) return@map ErpScenarioPrices(s.key, error = rechazo)
                val total = if (sinPrecio) null else s.lines.mapNotNull { PlannerFixtures.tarifa(it) }.fold(BigDecimal.ZERO, BigDecimal::add)
                ErpScenarioPrices(s.key, total?.let { it - BigDecimal.ONE }, BigDecimal.ONE, total ?: BigDecimal.ZERO,
                    s.lines.map { ErpPreviewPrice(it.key, total, total ?: BigDecimal.ZERO, review = total == null) })
            }
        }

        override fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int): List<ProductSummary> = error("no se usa")
        override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo? = error("no se usa")
        override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary? = error("no se usa")
        override fun previewQuote(tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>): QuotePreview = error("no se usa")
        override fun issueQuote(
            tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>, expectedTotal: BigDecimal, idempotencyKey: String,
        ): IssuedQuote = error("no se usa")
        override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo? = error("no se usa")
    }

    private val conPlantilla = PlannerCatalog.Producto(ProductSummary(PRODUCTO_PERGOLA, "PERG", "Pérgola"), pergola())
    private val b = OptionSpec(
        PRODUCTO_PERGOLA, PlannerFixtures.PLANTILLA_PERGOLA,
        params = mapOf("ancho" to q("4"), "profundidad" to q("3")),
        choices = mapOf("techo" to "dos_aguas", "cubierta" to "teja", "luces" to "led"),
    )

    @Test
    fun `la opcion y sus variantes van en un solo lote`() {
        val erp = Erp()
        val v = PlannerPricing(erp, 8).valorar(7, b, conPlantilla)
        assertEquals(1, erp.lotes.size, "una sola llamada al ERP")
        val lote = erp.lotes.single()
        assertEquals(listOf("base", "v0", "v1", "v2"), lote.map { it.key })
        val base = lote[0].lines.single()
        assertEquals(PlannerFixtures.PLANTILLA_PERGOLA, base.templateId)
        assertEquals(b.choices, base.choices)
        assertEquals(b.params, base.params)
        assertEquals(BigDecimal.ZERO, base.lineTotal, "con plantilla el precio lo calcula el ERP")
        assertNull(base.productId)
        // Cada variante cambia exactamente una eleccion.
        assertEquals(b.choices + mapOf("luces" to "sin"), lote[1].lines.single().choices)

        assertEquals(q("24800.00"), v.precio.total)
        assertFalse(v.precio.review)
        assertEquals(listOf(q("21300.00"), q("24300.00"), q("19900.00")), v.variantes.map { it.total })
        assertTrue(v.variantes.none { it.review })
    }

    @Test
    fun `sin plantilla se manda precio de lista por cantidad y las medidas`() {
        val erp = Erp()
        val deck = PlannerCatalog.Producto(ProductSummary(PRODUCTO_DECK, "DECK", "Deck", q("1250.50"), "m2"), null)
        val spec = OptionSpec(PRODUCTO_DECK, params = mapOf("ancho" to q("5"), "profundidad" to q("4"), "alto" to q("0.3")), quantity = q("2"))
        val v = PlannerPricing(erp, 8).valorar(7, spec, deck)
        val linea = erp.lotes.single().single().lines.single()
        assertEquals(q("2501.00"), linea.lineTotal)
        assertEquals(PRODUCTO_DECK, linea.productId)
        assertEquals(q("5"), linea.width)
        assertEquals(q("4"), linea.depth)
        assertEquals(q("0.3"), linea.height)
        assertNull(linea.templateId)
        assertTrue(v.variantes.isEmpty(), "sin plantilla no hay elecciones que variar")
        assertEquals(q("2501.00"), v.precio.total)
    }

    @Test
    fun `si el asesor confirma el precio no hay total`() {
        val v = PlannerPricing(Erp(sinPrecio = true), 8).valorar(7, b, conPlantilla)
        assertTrue(v.precio.review)
        assertNull(v.precio.total)
        assertTrue(v.variantes.all { it.review && it.total == null })
    }

    @Test
    fun `si el ERP rechaza la opcion el motivo llega al visitante`() {
        val e = assertThrows(PlannerSpecException::class.java) {
            PlannerPricing(Erp(rechazo = "Ancho no puede pasar de 8 m"), 8).valorar(7, b, conPlantilla)
        }
        assertEquals("Ancho no puede pasar de 8 m", e.message)
    }

    @Test
    fun `el tope de variantes cabe en un lote del ERP`() {
        val erp = Erp()
        PlannerPricing(erp, 1).valorar(7, b, conPlantilla)
        assertEquals(2, erp.lotes.single().size)
        PlannerPricing(erp, 50).valorar(7, b, conPlantilla)
        assertTrue(erp.lotes.last().size <= PlannerPricing.MAX_LOTE)
    }

    @Test
    fun `un escenario con alguna linea por confirmar no da total parcial`() {
        val p = PlannerPricing.precio(ErpScenarioPrices("base", q("100"), q("12"), q("112"),
            listOf(ErpPreviewPrice("p", q("112"), q("112"), false), ErpPreviewPrice("q", BigDecimal.ZERO, BigDecimal.ZERO, true))))
        assertTrue(p.review)
        assertNull(p.total)
        assertEquals(q("100.00"), p.subtotal, "lo interno se guarda igual")
    }
}
