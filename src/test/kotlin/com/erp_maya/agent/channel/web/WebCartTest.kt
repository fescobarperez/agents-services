package com.erp_maya.agent.channel.web

import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.erp.domain.ErpClientLine
import com.erp_maya.agent.erp.domain.ErpClientPrices
import com.erp_maya.agent.erp.domain.ErpPreviewPrice
import com.erp_maya.agent.erp.domain.ErpPreviewPrices
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ErpQuoteItem
import com.erp_maya.agent.prompt.domain.DraftLine
import com.erp_maya.agent.prompt.domain.QuoteDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** El carrito del widget: que se ve y que no, sin base de datos ni ERP. */
class WebCartTest {

    private fun d(v: String) = BigDecimal(v)

    private val cotizacion = ErpQuote(
        id = 9, docNumber = "COT-000009", subtotal = d("1000.00"), tax = d("120.00"), total = d("1120.00"),
        items = listOf(
            ErpQuoteItem(1, productId = 5, productName = "Pérgola", uom = "u", quantity = d("1.000"), unitPrice = d("1000.00"),
                lineTotal = d("1000.00"), dimensions = Dimensions(height = d("2.5"), width = d("3"), depth = d("4.5"))),
            ErpQuoteItem(2, productId = 6, productName = "Banca de madera", quantity = d("2"), unitPrice = BigDecimal.ZERO,
                lineTotal = BigDecimal.ZERO, sizePricing = "review"),
            ErpQuoteItem(3, productId = 7, productName = "Flete interno", quantity = d("1"), unitPrice = d("50"), showToClient = false),
        ),
    )

    @Test
    fun `vacio no hay carrito`() {
        assertNull(WebCarts.armar(null, null, true, "GTQ"))
        assertNull(WebCarts.armar(QuoteDraft(), null, true, "GTQ"))
    }

    /** Lo que devuelve el ERP: los Q 120 de cargos y ganancia ya van dentro de la pergola. */
    private val alCliente = ErpClientPrices(
        subtotal = d("1000.00"), tax = d("120.00"), total = d("1120.00"),
        lines = listOf(ErpClientLine(1, d("1000.00"), d("1000.00")), ErpClientLine(2, d("0.00"), d("0.00")), ErpClientLine(3)),
    )

    @Test
    fun `con cotizacion en el ERP muestra sus montos y oculta lo que no ve el cliente`() {
        val c = WebCarts.armar(QuoteDraft(quoteId = 9), cotizacion, true, "GTQ", alCliente)!!
        assertEquals("COT-000009", c.number)
        assertEquals(listOf("Pérgola", "Banca de madera"), c.items.map { it.name })
        assertEquals("3 × 4.5 × 2.5 m", c.items[0].detail)
        assertEquals(d("1"), c.items[0].quantity)
        assertEquals(d("1120.00"), c.total)
        assertEquals(d("1000.00"), c.items[0].lineTotal)
        assertFalse(c.estimated)
        // La banca la cotiza un asesor: sin precio y el total puede cambiar.
        assertTrue(c.items[1].pendingPrice)
        assertNull(c.items[1].unitPrice)
        assertTrue(c.pendingPrices)
    }

    @Test
    fun `el precio de cada producto incluye los cargos y las lineas suman el subtotal`() {
        val base = cotizacion.copy(subtotal = d("1300.00"), tax = d("156.00"), total = d("1456.00"))
        val precios = alCliente.copy(subtotal = d("1300.00"), tax = d("156.00"), total = d("1456.00"),
            lines = listOf(ErpClientLine(1, d("1300.00"), d("1300.00")), ErpClientLine(2, d("0"), d("0")), ErpClientLine(3)))
        val c = WebCarts.armar(QuoteDraft(quoteId = 9), base, true, "GTQ", precios)!!
        assertEquals(d("1300.00"), c.items[0].lineTotal, "no el precio base de Q 1000")
        assertEquals(c.subtotal, c.items.mapNotNull { it.lineTotal }.fold(BigDecimal.ZERO, BigDecimal::add))
    }

    @Test
    fun `sin los precios al cliente no se muestra el precio base de las lineas`() {
        val c = WebCarts.armar(QuoteDraft(quoteId = 9), cotizacion, true, "GTQ", null)!!
        assertTrue(c.items.all { it.lineTotal == null && it.unitPrice == null })
        assertEquals(d("1120.00"), c.total)
    }

    @Test
    fun `sin precios por configuracion no sale ningun monto`() {
        val c = WebCarts.armar(QuoteDraft(quoteId = 9), cotizacion, false, "GTQ")!!
        assertFalse(c.showPrices)
        assertTrue(c.items.all { it.unitPrice == null && it.lineTotal == null })
        assertNull(c.subtotal); assertNull(c.tax); assertNull(c.total)
    }

    @Test
    fun `con precio global solo se ve el total`() {
        val c = WebCarts.armar(QuoteDraft(quoteId = 9), cotizacion.copy(priceDisplay = "global"), true, "GTQ")!!
        assertTrue(c.items.all { it.unitPrice == null })
        assertNull(c.subtotal)
        assertEquals(d("1120.00"), c.total)
    }

    private val enEspera = QuoteDraft(pendientes = listOf(
        DraftLine(5, "PER-1", "Pérgola", d("2"), d("1500.50"), "u"),
        DraftLine(6, "NUEVO-1", "Banca a la medida", d("1")),
    ))

    @Test
    fun `en espera solo se pide la vista previa de lo que tiene precio`() {
        val lineas = WebCarts.lineasPrevia(enEspera)
        assertEquals(1, lineas.size)
        assertEquals(d("3001.00"), lineas[0].lineTotal)
    }

    @Test
    fun `en espera el precio de cada producto ya lleva los cargos de la empresa`() {
        val clave = WebCarts.lineasPrevia(enEspera).single().key
        val previa = ErpPreviewPrices(subtotal = d("3901.30"), tax = d("468.16"), total = d("4369.46"),
            lines = listOf(ErpPreviewPrice(clave, d("1950.65"), d("3901.30"))))
        val c = WebCarts.armar(enEspera, null, true, "GTQ", previa = previa)!!
        assertNull(c.number)
        assertTrue(c.estimated)
        assertEquals(d("3901.30"), c.items[0].lineTotal, "no el precio de lista de Q 3,001")
        assertTrue(c.items[1].pendingPrice)
        assertEquals(d("3901.30"), c.subtotal)
        assertNull(c.total)
    }

    @Test
    fun `en espera sin vista previa no se muestra el precio de lista`() {
        val c = WebCarts.armar(enEspera, null, true, "GTQ")!!
        assertTrue(c.items.all { it.lineTotal == null })
        assertNull(c.subtotal)
    }

    @Test
    fun `la moneda sale del pais del locale`() {
        assertEquals("GTQ", WebCarts.moneda("es-GT"))
        assertEquals("MXN", WebCarts.moneda("es-MX"))
        assertEquals("GTQ", WebCarts.moneda(null))
    }

    @Test
    fun `medida fuera de tolerancia queda por confirmar y sin montos en cero`() {
        val conMedida = QuoteDraft(pendientes = listOf(
            DraftLine(5, "PER-1", "Pérgola", d("1"), d("20000"), "u", Dimensions(height = d("2.5"), width = d("4"), depth = d("5"))),
        ))
        val linea = WebCarts.lineasPrevia(conMedida).single()
        assertEquals(5L, linea.productId)
        assertEquals(d("4"), linea.width)
        val previa = ErpPreviewPrices(subtotal = d("0.00"), tax = d("0.00"), total = d("0.00"),
            lines = listOf(ErpPreviewPrice(linea.key, d("0.00"), d("0.00"), review = true)))
        val c = WebCarts.armar(conMedida, null, true, "GTQ", previa = previa)!!
        assertTrue(c.items[0].pendingPrice)
        assertNull(c.items[0].lineTotal)
        assertNull(c.subtotal)

        val cotizada = cotizacion.copy(subtotal = BigDecimal.ZERO, tax = BigDecimal.ZERO, total = BigDecimal.ZERO,
            items = cotizacion.items.drop(1))
        val q = WebCarts.armar(QuoteDraft(quoteId = 9), cotizada, true, "GTQ")!!
        assertNull(q.subtotal); assertNull(q.tax); assertNull(q.total)
        assertTrue(q.pendingPrices)
    }
}
