package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.QuoteLineWrite
import com.erp_maya.agent.erp.domain.StockInfo
import java.math.BigDecimal

/**
 * Todo lo que este servicio necesita de maya-erp-services, en terminos de
 * dominio y no de rutas.
 *
 * La interfaz existe por una razon concreta: **las rutas del ERP todavia no
 * estan validadas**. Detras de estos metodos, cambiar un path toca un solo
 * archivo en vez de esparcirse por las herramientas del agente.
 *
 * `tenantId` y `conversationId` son parametros explicitos y no estado
 * implicito: es lo que obliga a que ninguna llamada salga sin empresa, que es
 * el error mas caro posible aqui.
 */
interface ErpClient {

    fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int = 10): List<ProductSummary>

    fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo?

    fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary?

    fun previewQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
    ): QuotePreview

    /**
     * Emite la cotizacion. `idempotencyKey` se deriva del id del mensaje
     * entrante para que un reintento no emita dos documentos.
     */
    fun issueQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
        expectedTotal: BigDecimal,
        idempotencyKey: String,
    ): IssuedQuote

    fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo?

    // ── Borrador de cotizacion (asistente) ──────────────────────────────────
    // Con cuerpo por defecto para que los dobles de prueba existentes sigan
    // compilando; la implementacion real esta en ErpHttpClient.

    fun searchCustomers(tenantId: Long, conversationId: Long, query: String, limit: Int = 5): List<CustomerSummary> =
        throw ErpException("searchCustomers no implementado")

    /** Alta de un cliente nuevo (lo pide el asistente cuando nadie tiene ese numero). */
    fun createCustomer(tenantId: Long, conversationId: Long, name: String, nit: String?, phone: String?): CustomerSummary =
        throw ErpException("createCustomer no implementado")

    fun getCustomer(tenantId: Long, conversationId: Long, customerId: Long): CustomerSummary? =
        throw ErpException("getCustomer no implementado")

    fun getProduct(tenantId: Long, conversationId: Long, productId: Long): ProductSummary? =
        throw ErpException("getProduct no implementado")

    /** Crea la cotizacion como PROSPECTO del asistente. */
    fun createProspectQuote(
        tenantId: Long,
        conversationId: Long,
        customer: CustomerSummary,
        lines: List<QuoteLineWrite>,
        channel: String,
        conversationRef: String,
    ): ErpQuote = throw ErpException("createProspectQuote no implementado")

    fun getQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote? =
        throw ErpException("getQuote no implementado")

    /** Reemplaza las lineas: las que no vengan se eliminan; sin id son nuevas. */
    fun updateQuoteLines(tenantId: Long, conversationId: Long, quote: ErpQuote, lines: List<QuoteLineWrite>): ErpQuote =
        throw ErpException("updateQuoteLines no implementado")
}
