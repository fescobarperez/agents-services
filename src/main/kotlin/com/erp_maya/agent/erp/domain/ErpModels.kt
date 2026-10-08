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
    /** Existencia total; la llena la herramienta de busqueda, no el ERP. */
    @Nullable val available: BigDecimal? = null,
    /** Sucursal, solo si todo el stock esta en una. */
    @Nullable val branch: String? = null,
)

@Serdeable
data class StockInfo(val productId: Long, val available: BigDecimal, @Nullable val branch: String? = null)

@Serdeable
data class CustomerSummary(
    val id: Long,
    val name: String,
    @Nullable val nit: String? = null,
    @Nullable val phone: String? = null,
    @Nullable val email: String? = null,
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

/** Linea de una cotizacion tal como la devuelve el ERP. */
@Serdeable
data class ErpQuoteItem(
    val id: Long,
    @Nullable val productId: Long? = null,
    @Nullable val productName: String? = null,
    @Nullable val uom: String? = null,
    val quantity: BigDecimal,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val discount: BigDecimal? = null,
    @Nullable val lineTotal: BigDecimal? = null,
)

/** Cotizacion del ERP (GET/POST/PUT /api/quotes). Solo lo que el agente usa. */
@Serdeable
data class ErpQuote(
    val id: Long,
    val docNumber: String,
    @Nullable val clientId: Long? = null,
    @Nullable val clientName: String? = null,
    @Nullable val validUntil: String? = null,
    @Nullable val notes: String? = null,
    @Nullable val subtotal: BigDecimal? = null,
    @Nullable val tax: BigDecimal? = null,
    @Nullable val taxRate: BigDecimal? = null,
    @Nullable val profitCalcType: String? = null,
    @Nullable val profitValue: BigDecimal? = null,
    @Nullable val total: BigDecimal? = null,
    @Nullable val status: String? = null,
    val items: List<ErpQuoteItem> = emptyList(),
    /** Sube con cada envio: la decision del cliente se ata a esta version. */
    val sentVersion: Int = 0,
)

/** Linea para crear (productId) o editar (id) una cotizacion. */
data class QuoteLineWrite(
    val id: Long? = null,
    val productId: Long? = null,
    val description: String? = null,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal? = null,
    val discount: BigDecimal? = null,
    val uom: String? = null,
)

/** Fallo hablando con maya-erp-services. */
class ErpException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

/** GET /api/quotes/by-client/{id}: en que va cada cotizacion del cliente. */
@Serdeable
data class ErpQuoteSummary(
    val id: Long,
    val docNumber: String,
    @Nullable val status: String? = null,
    @Nullable val total: BigDecimal? = null,
    /** Un vendedor ya la abrio: el cliente ya no la puede reabrir. */
    val taken: Boolean = false,
    @Nullable val quoteDate: String? = null,
    val sentVersion: Int = 0,
    @Nullable val validUntil: String? = null,
)

/** POST /api/quotes/{id}/change-requests. */
@Serdeable
data class ChangeRequestWrite(
    val kind: String,
    @Nullable val productId: Long? = null,
    @Nullable val productName: String? = null,
    @Nullable val quantity: BigDecimal? = null,
    @Nullable val discountPct: BigDecimal? = null,
    @Nullable val detail: String? = null,
    @Nullable val source: String? = null,
    @Nullable val requestedBy: String? = null,
    /** Solo para kind = consulta: la solicitud sobre la que pregunta el cliente. */
    @Nullable val parentId: Long? = null,
)

@Serdeable
data class ErpChangeRequest(
    val id: Long,
    val kind: String,
    @Nullable val status: String? = null,
    @Nullable val summary: String? = null,
    @Nullable val detail: String? = null,
    @Nullable val reasonLabel: String? = null,
    /** Lo que se le dijo al cliente: explicacion del motivo + comentario del vendedor. */
    @Nullable val clientMessage: String? = null,
    @Nullable val parentId: Long? = null,
)
