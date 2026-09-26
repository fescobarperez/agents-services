package com.erp_maya.agent.erp.domain

import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

@Serdeable
data class ProductSummary(
    val id: Long,
    val sku: String,
    val name: String,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val unit: String? = null,
)

@Serdeable
data class StockInfo(val productId: Long, val available: BigDecimal, @Nullable val branch: String? = null)

@Serdeable
data class CustomerSummary(
    val id: Long,
    val name: String,
    @Nullable val nit: String? = null,
    @Nullable val phone: String? = null,
)

@Serdeable
data class QuoteLineRequest(val productId: Long, val quantity: BigDecimal)

/**
 * Previsualizacion de una cotizacion. `total` es lo que la compuerta compara
 * contra el tope: si al emitir cambia, la operacion no se ejecuta.
 */
@Serdeable
data class QuotePreview(
    val subtotal: BigDecimal,
    val tax: BigDecimal,
    val total: BigDecimal,
    val lines: List<QuoteLinePreview> = emptyList(),
)

@Serdeable
data class QuoteLinePreview(
    val productId: Long,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineTotal: BigDecimal,
)

@Serdeable
data class IssuedQuote(val id: Long, val number: String, val total: BigDecimal)

/**
 * Datos de la empresa que el hilo necesita: moneda, impuesto, lista de
 * precios. Se leen al abrir la conversacion y se cachean; no se copian a
 * ninguna tabla de este servicio.
 */
@Serdeable
data class CompanyInfo(
    val id: Long,
    val name: String,
    @Nullable val currency: String? = null,
    @Nullable val taxRate: BigDecimal? = null,
    @Nullable val priceListId: Long? = null,
    val active: Boolean = true,
)

/** Fallo hablando con maya-erp-services. */
class ErpException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)
