package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.StockInfo
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

@Serdeable
data class PreviewRequest(val customerId: Long?, val lines: List<QuoteLineRequest>)

@Serdeable
data class IssueRequest(val customerId: Long?, val lines: List<QuoteLineRequest>, val expectedTotal: BigDecimal)

/**
 * Implementacion HTTP de [ErpClient].
 *
 * TODO(rutas por validar): ninguna de las rutas de abajo esta confirmada
 * contra maya-erp-services. Estan aisladas aqui justamente para que ajustarlas
 * sea tocar este archivo y nada mas.
 */
@Singleton
open class ErpHttpClient(
    private val provider: ErpHttpClientProvider,
    private val tokens: ErpTokenService,
    private val config: ErpConfiguration,
) : ErpClient {

    override fun searchProducts(
        tenantId: Long,
        conversationId: Long,
        query: String,
        limit: Int,
    ): List<ProductSummary> {
        // TODO(ruta por validar): GET /api/products?search=
        val uri = UriBuilder.of("/api/products")
            .queryParam("search", query)
            .queryParam("size", limit)
            .build()
        return lista(HttpRequest.GET<Any>(uri), tenantId, conversationId, ProductSummary::class.java)
    }

    override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo? {
        // TODO(ruta por validar): GET /api/products/{id}/stock
        return uno(HttpRequest.GET<Any>("/api/products/$productId/stock"), tenantId, conversationId, StockInfo::class.java)
    }

    override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary? {
        // TODO(ruta por validar): GET /api/clients?phone=
        val uri = UriBuilder.of("/api/clients").queryParam("phone", phone).build()
        return lista(HttpRequest.GET<Any>(uri), tenantId, conversationId, CustomerSummary::class.java).firstOrNull()
    }

    override fun previewQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
    ): QuotePreview {
        // TODO(ruta por validar): POST /api/quotes/preview
        val req = HttpRequest.POST("/api/quotes/preview", PreviewRequest(customerId, lines))
        return uno(req, tenantId, conversationId, QuotePreview::class.java)
            ?: throw ErpException("El ERP no devolvio previsualizacion de la cotizacion")
    }

    override fun issueQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
        expectedTotal: BigDecimal,
        idempotencyKey: String,
    ): IssuedQuote {
        // TODO(ruta por validar): POST /api/quotes
        val req = HttpRequest.POST("/api/quotes", IssueRequest(customerId, lines, expectedTotal))
            // La llave viaja tambien al ERP: que este servicio no repita la
            // operacion no impide que un reintento de red la duplique alla.
            .header(CABECERA_IDEMPOTENCIA, idempotencyKey)
        return uno(req, tenantId, conversationId, IssuedQuote::class.java)
            ?: throw ErpException("El ERP no devolvio la cotizacion emitida")
    }

    override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo? {
        // TODO(ruta por validar): GET /api/company
        return uno(HttpRequest.GET<Any>("/api/company"), tenantId, conversationId, CompanyInfo::class.java)
    }

    // ── plomeria ────────────────────────────────────────────────────────────

    /**
     * Toda peticion lleva empresa y conversacion, aunque la URL ya las
     * implicara: es lo que permite al ERP auditar en nombre de quien actuo el
     * agente, y lo que hace evidente en el codigo si alguna llamada se fuera
     * sin empresa.
     */
    private fun <T : Any> decorar(req: MutableHttpRequest<T>, tenantId: Long, conversationId: Long) = req
        .header(CABECERA_TENANT, tenantId.toString())
        .header(CABECERA_CONVERSACION, conversationId.toString())
        .header("X-Agent-Service", config.clientId ?: "agents-services")
        .bearerAuth(tokens.currentToken())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)

    private fun <T : Any, R : Any> uno(req: MutableHttpRequest<T>, tenantId: Long, conversationId: Long, tipo: Class<R>): R? =
        ejecutar(req, tenantId, conversationId) { decorado ->
            provider.client().toBlocking().retrieve(decorado, tipo)
        }

    private fun <T : Any, R : Any> lista(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        tipo: Class<R>,
    ): List<R> =
        ejecutar(req, tenantId, conversationId) { decorado ->
            provider.client().toBlocking().retrieve(decorado, Argument.listOf(tipo))
        } ?: emptyList()

    private fun <T : Any, R : Any> ejecutar(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        llamada: (MutableHttpRequest<T>) -> R,
    ): R? {
        return try {
            llamada(decorar(req, tenantId, conversationId))
        } catch (e: HttpClientResponseException) {
            when (e.status) {
                HttpStatus.NOT_FOUND -> null
                HttpStatus.UNAUTHORIZED -> {
                    // Un 401 casi siempre es el token vencido antes de tiempo.
                    // Se descarta y se reintenta UNA vez; si vuelve a fallar es
                    // credencial mala y repetir no arregla nada.
                    tokens.invalidate()
                    log.warn("el ERP devolvio 401; se renueva el token y se reintenta una vez")
                    try {
                        llamada(decorar(req, tenantId, conversationId))
                    } catch (segundo: HttpClientResponseException) {
                        throw ErpException("El ERP rechazo la credencial del servicio", segundo)
                    }
                }
                else -> throw ErpException("El ERP respondio ${e.status.code} en ${req.path}", e)
            }
        } catch (e: Exception) {
            throw ErpException("No se pudo hablar con el ERP en ${req.path}", e)
        }
    }

    companion object {
        const val CABECERA_TENANT = "X-Tenant-Id"
        const val CABECERA_CONVERSACION = "X-Conversation-Id"
        const val CABECERA_IDEMPOTENCIA = "Idempotency-Key"
        private val log = LoggerFactory.getLogger(ErpHttpClient::class.java)
    }
}
