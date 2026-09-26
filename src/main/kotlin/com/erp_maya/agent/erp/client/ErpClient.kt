package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
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
}
