package com.erp_maya.agent.erp.domain

import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Alto × ancho × profundidad (QuoteDtos.Dimensions del ERP). En el producto es
 * la medida estandar; en una linea, la que pidio el cliente.
 */
@Serdeable
data class Dimensions(
    @Nullable val height: BigDecimal? = null,
    @Nullable val width: BigDecimal? = null,
    @Nullable val depth: BigDecimal? = null,
) {
    /** Funcion y no propiedad: una propiedad se serializaria al ERP. */
    fun vacia(): Boolean = height == null && width == null && depth == null

    /** Igualdad por valor numerico: 1.8 y 1.800 son la misma medida. */
    fun igualA(otra: Dimensions?): Boolean {
        fun eq(a: BigDecimal?, b: BigDecimal?) = if (a == null || b == null) a == b else a.compareTo(b) == 0
        val o = otra ?: Dimensions()
        return eq(height, o.height) && eq(width, o.width) && eq(depth, o.depth)
    }

    /** "2 × 1.5 × 0.6 m" */
    fun texto(unidad: String? = null): String =
        listOfNotNull(height, width, depth).joinToString(" × ") { it.stripTrailingZeros().toPlainString() } +
            " " + (unidad ?: "m")

    companion object {
        fun de(height: BigDecimal?, width: BigDecimal?, depth: BigDecimal?): Dimensions? =
            Dimensions(height, width, depth).takeUnless { it.vacia() }
    }
}

@Serdeable
data class ProductSummary(
    val id: Long,
    val sku: String,
    val name: String,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val unit: String? = null,
    /** Medida estandar del catalogo (alto, ancho, profundidad), si la tiene. */
    @Nullable val dimensions: Dimensions? = null,
    @Nullable val dimensionUnit: String? = null,
    /**
     * Se fabrica bajo pedido (el producto no controla existencias): no se
     * consulta stock ni se habla de disponibilidad, sino de tiempo de entrega.
     */
    @Nullable val madeToOrder: Boolean? = null,
    /** Existencia total; la llena la herramienta de busqueda, no el ERP. */
    @Nullable val available: BigDecimal? = null,
    /** Sucursal, solo si todo el stock esta en una. */
    @Nullable val branch: String? = null,
    /** Lo propuso el asistente y aun no lo revisa nadie: no tiene precio. */
    @Nullable val pendingReview: Boolean? = null,
)

/** PUT /api/quotes/{id}/design (QuoteDesignDtos.Save del ERP): ficha + render aprobado. */
@Serdeable
data class QuoteDesignWrite(
    val spec: QuoteDesignSpec,
    @Nullable val renderNumber: Int? = null,
    @Nullable val renderMax: Int? = null,
    @Nullable val imageBase64: String? = null,
    @Nullable val imageMime: String? = null,
)

@Serdeable
data class QuoteDesignSpec(
    @Nullable val tipo: String? = null,
    @Nullable val descripcion: String? = null,
    @Nullable val material: String? = null,
    @Nullable val acabado: String? = null,
    @Nullable val alto: BigDecimal? = null,
    @Nullable val ancho: BigDecimal? = null,
    @Nullable val profundidad: BigDecimal? = null,
    @Nullable val medidasConfirmadas: Boolean? = null,
    @Nullable val detalles: Map<String, String>? = null,
)

/** Respuesta del ERP al guardar el diseño; solo interesa que exista. */
@Serdeable
data class QuoteDesignAck(@Nullable val quoteId: Long? = null, @Nullable val hasImage: Boolean? = null)

/** POST /api/products/proposals (ProductReviewDtos.Proposal del ERP). */
@Serdeable
data class ProductProposal(
    val name: String,
    @Nullable val category: String? = null,
    @Nullable val notes: String? = null,
    @Nullable val height: BigDecimal? = null,
    @Nullable val width: BigDecimal? = null,
    @Nullable val depth: BigDecimal? = null,
    @Nullable val dimensionUnit: String? = null,
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
    /** Medida pedida y la del catalogo al cotizar. */
    @Nullable val dimensions: Dimensions? = null,
    @Nullable val baseDimensions: Dimensions? = null,
    @Nullable val dimensionUnit: String? = null,
    val customSize: Boolean = false,
    val showToClient: Boolean = true,
    /** null · 'calculated' (precio por medida) · 'review' (lo cotiza el asesor). */
    @Nullable val sizePricing: String? = null,
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
    /** 'detailed' | 'global': con global el cliente solo conoce el total. */
    @Nullable val priceDisplay: String? = null,
    /** Direccion de entrega / instalacion (campo propio, sale en el PDF). */
    @Nullable val deliveryAddress: String? = null,
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
    /** Medida pedida; null = la del catalogo (nueva) o la que ya tiene (existente). */
    val dimensions: Dimensions? = null,
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
