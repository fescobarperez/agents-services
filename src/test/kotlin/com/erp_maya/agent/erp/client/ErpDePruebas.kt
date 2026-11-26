package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CatalogCategory
import com.erp_maya.agent.erp.domain.CatalogProduct
import com.erp_maya.agent.erp.domain.ProductImageData
import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpPreviewLine
import com.erp_maya.agent.erp.domain.ErpPreviewPrice
import com.erp_maya.agent.erp.domain.ErpPreviewScenario
import com.erp_maya.agent.erp.domain.ErpScenarioPrices
import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductProposal
import com.erp_maya.agent.erp.domain.QuoteDesignAck
import com.erp_maya.agent.erp.domain.QuoteDesignWrite
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.StockInfo
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton
import java.math.BigDecimal

/**
 * ERP simulado. Registra lo que le piden para poder afirmar que una emision
 * NO ocurrio cuando la compuerta la bloqueo — que es el punto entero.
 */
@Singleton
@Replaces(ErpHttpClient::class)
@Requires(env = [Environment.TEST])
class ErpDePruebas : ErpClient {

    val emisiones = mutableListOf<Triple<Long, BigDecimal, String>>()
    val tenantsVistos = mutableListOf<Long>()

    var totalPreview: BigDecimal = BigDecimal("1800.00")

    /** Diseños guardados en cotizaciones: quoteId → lo que se mando. */
    val disenos = mutableMapOf<Long, QuoteDesignWrite>()

    /** Productos que el asistente propuso. */
    val propuestas = mutableListOf<ProductProposal>()

    // ── planificador ──
    /** Catalogo simulado: productId → producto y su plantilla (si tiene). */
    val productos = mutableMapOf<Long, ProductSummary>()
    val plantillas = mutableMapOf<Long, ProductTemplate>()
    /** Cada lote de vista previa que se pidio (para contar llamadas al ERP). */
    val lotes = mutableListOf<List<ErpPreviewScenario>>()
    /** Precio TOTAL al cliente de una linea; null = la cotiza un asesor. Por defecto, su lineTotal. */
    var tarifa: (ErpPreviewLine) -> BigDecimal? = { it.lineTotal.takeIf { t -> t.signum() > 0 } }
    /** Si no es null, el ERP rechaza el escenario base con este motivo. */
    var rechazo: String? = null
    /** Simula el ERP caido en la vista previa. */
    var caido = false
    /** Categorias del catalogo y los productos de cada una (el ERP real ya incluye los de sus subcategorias). */
    val categorias = mutableListOf<CatalogCategory>()
    val porCategoria = mutableMapOf<Long, List<CatalogProduct>>()
    /** Fotos por producto y cuantas se pidieron (para ver la cache de miniaturas). */
    val fotos = mutableMapOf<Long, ProductImageData>()
    var fotosPedidas = 0

    fun limpiar() {
        emisiones.clear()
        tenantsVistos.clear()
        propuestas.clear()
        disenos.clear()
        productos.clear()
        plantillas.clear()
        lotes.clear()
        tarifa = { it.lineTotal.takeIf { t -> t.signum() > 0 } }
        rechazo = null
        caido = false
        categorias.clear()
        porCategoria.clear()
        fotos.clear()
        fotosPedidas = 0
        // Tambien el total: el bean es singleton y sin esto un test que lo sube
        // contamina al siguiente segun el orden en que corran.
        totalPreview = BigDecimal("1800.00")
    }

    override fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int): List<ProductSummary> {
        tenantsVistos += tenantId
        return listOf(ProductSummary(1, "CEM-42", "Cemento gris 42.5 kg", BigDecimal("90.00"), "saco"))
    }

    override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo {
        tenantsVistos += tenantId
        return StockInfo(productId, BigDecimal("120"), "Casa Matriz")
    }

    override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary {
        tenantsVistos += tenantId
        return CustomerSummary(4821, "Ferreteria El Angel", "1234567-8", phone)
    }

    override fun previewQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
    ): QuotePreview {
        tenantsVistos += tenantId
        return QuotePreview(totalPreview.multiply(BigDecimal("0.95")), totalPreview.multiply(BigDecimal("0.05")), totalPreview)
    }

    override fun issueQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
        expectedTotal: BigDecimal,
        idempotencyKey: String,
    ): IssuedQuote {
        tenantsVistos += tenantId
        emisiones += Triple(tenantId, expectedTotal, idempotencyKey)
        return IssuedQuote(99, "COT-2026-0418", expectedTotal)
    }

    override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo {
        tenantsVistos += tenantId
        return CompanyInfo(tenantId, "Empresa de pruebas", "GTQ", BigDecimal("5"), null, true)
    }

    override fun proposeProduct(tenantId: Long, conversationId: Long, propuesta: ProductProposal): ProductSummary {
        tenantsVistos += tenantId
        propuestas += propuesta
        return ProductSummary(900 + propuestas.size.toLong(), "ASIS-${propuestas.size}", propuesta.name, null, "Unidad",
            pendingReview = true)
    }

    override fun saveQuoteDesign(tenantId: Long, conversationId: Long, quoteId: Long, diseno: QuoteDesignWrite): QuoteDesignAck {
        tenantsVistos += tenantId
        disenos[quoteId] = diseno
        return QuoteDesignAck(quoteId, diseno.imageBase64 != null)
    }

    override fun getProduct(tenantId: Long, conversationId: Long, productId: Long): ProductSummary? {
        tenantsVistos += tenantId
        return productos[productId]
    }

    override fun categories(tenantId: Long, conversationId: Long): List<CatalogCategory> {
        tenantsVistos += tenantId
        return categorias.toList()
    }

    override fun productsInCategory(tenantId: Long, conversationId: Long, categoryId: Long, limit: Int): List<CatalogProduct> {
        tenantsVistos += tenantId
        return porCategoria[categoryId].orEmpty().take(limit)
    }

    override fun productImage(tenantId: Long, conversationId: Long, productId: Long, thumb: Boolean): ProductImageData? {
        fotosPedidas++
        return fotos[productId]
    }

    override fun templateByProduct(tenantId: Long, conversationId: Long, productId: Long): ProductTemplate? {
        tenantsVistos += tenantId
        return plantillas[productId]
    }

    /** Cada escenario se valora solo; el total ya trae IVA (12 %) y la linea en 0 queda para el asesor. */
    override fun previewClientPricesBatch(tenantId: Long, conversationId: Long, scenarios: List<ErpPreviewScenario>): List<ErpScenarioPrices> {
        tenantsVistos += tenantId
        if (caido) throw ErpException("No se pudo hablar con el ERP en /api/quotes/client-prices/preview/batch")
        lotes += scenarios
        return scenarios.map { s ->
            if (s.key == "base" && rechazo != null) return@map ErpScenarioPrices(s.key, error = rechazo)
            val precios = s.lines.map { l -> l.key to tarifa(l) }
            val total = precios.mapNotNull { it.second }.fold(BigDecimal.ZERO, BigDecimal::add)
            val subtotal = total.divide(BigDecimal("1.12"), 2, java.math.RoundingMode.HALF_UP)
            ErpScenarioPrices(
                key = s.key,
                subtotal = subtotal,
                tax = total - subtotal,
                total = total,
                lines = precios.map { (k, p) -> ErpPreviewPrice(k, p, p ?: BigDecimal.ZERO, review = p == null) },
            )
        }
    }
}
