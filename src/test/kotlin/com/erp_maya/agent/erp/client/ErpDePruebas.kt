package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.IssuedQuote
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

    fun limpiar() {
        emisiones.clear()
        tenantsVistos.clear()
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
}
