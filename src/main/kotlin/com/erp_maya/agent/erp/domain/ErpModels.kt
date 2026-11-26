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

// ── plantillas de calculo (GET /api/product-templates/by-product/{id}) ─────

/** Dato que pide la plantilla: ancho, largo, alto… (`code` es la variable de las formulas). */
@Serdeable
data class TemplateParam(
    val code: String,
    val label: String,
    @Nullable val unit: String? = null,
    @Nullable val min: BigDecimal? = null,
    @Nullable val max: BigDecimal? = null,
    @Nullable val defaultValue: BigDecimal? = null,
    @Nullable val required: Boolean? = null,
)

/** Una eleccion de una opcion de diseño (techo: shingle). `benefit` es el argumento de venta. */
@Serdeable
data class TemplateChoice(
    val code: String,
    val label: String,
    @Nullable val renderText: String? = null,
    @Nullable val benefit: String? = null,
    @Nullable val isDefault: Boolean? = null,
)

@Serdeable
data class TemplateOption(
    val code: String,
    val label: String,
    @Nullable val required: Boolean? = null,
    val choices: List<TemplateChoice> = emptyList(),
)

/** Combinacion de elecciones con nombre: basica, recomendada, premium. */
@Serdeable
data class TemplatePreset(val code: String, val label: String, val choices: Map<String, String> = emptyMap())

/** Plantilla de calculo de un producto a la medida. Los componentes (materiales y costos) no se leen: son internos. */
@Serdeable
data class ProductTemplate(
    val id: Long,
    val productId: Long,
    @Nullable val productName: String? = null,
    val name: String,
    @Nullable val description: String? = null,
    val active: Boolean = true,
    val params: List<TemplateParam> = emptyList(),
    val options: List<TemplateOption> = emptyList(),
    val presets: List<TemplatePreset> = emptyList(),
)

/** POST /api/quotes/{id}/template-items y PUT …/template-items/{itemId}. */
@Serdeable
data class TemplateItemWrite(
    val templateId: Long,
    val params: Map<String, BigDecimal>,
    val choices: Map<String, String>,
    @Nullable val preset: String?,
    val quantity: BigDecimal,
)

/**
 * Mensajes de IA que el plan de la empresa permite por mes (`GET /api/company/ai-limit`).
 * `monthlyMessages` nulo = sin limite.
 */
@Serdeable
data class AiLimit(
    val enabled: Boolean = true,
    @Nullable val monthlyMessages: Int? = null,
)

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
    /** null · 'calculated' (precio por medida) · 'review' (lo cotiza el asesor) · 'template'. */
    @Nullable val sizePricing: String? = null,
    /** Descripcion de la linea (en una de plantilla: «Pérgola 3 × 4.5 m · Techo: Shingle»). */
    @Nullable val description: String? = null,
    /** Plantilla con la que se calculo y su configuracion (JSON con params, choices, preset y renderText). */
    @Nullable val templateId: Long? = null,
    @Nullable val templateConfig: String? = null,
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

/** Prospecto que TINO deja en el ERP (POST /api/leads). */
@Serdeable
data class LeadWrite(
    val name: String,
    @Nullable val companyName: String? = null,
    @Nullable val phone: String? = null,
    @Nullable val email: String? = null,
    @Nullable val interest: String? = null,
    @Nullable val message: String? = null,
    val wantsDemo: Boolean = false,
    /** tino_web | tino_whatsapp | web_form */
    val source: String,
    /**
     * La conversacion de origen: si el visitante corrige un dato, se actualiza
     * el mismo prospecto. Nulo en el formulario de contacto (cada envio es uno).
     */
    @Nullable val sourceRef: String? = null,
)

@Serdeable
data class LeadAck(
    val id: Long,
    val name: String,
    @Nullable val phone: String? = null,
    @Nullable val email: String? = null,
    val wantsDemo: Boolean = false,
    val status: String = "nuevo",
    /** El ERP aviso por correo al equipo de ventas en esta llamada. */
    val notified: Boolean = false,
)

/**
 * Precios al cliente (GET /api/quotes/{id}/client-prices): ganancia, gastos y
 * cargos ya van dentro del precio de cada producto y las lineas suman el subtotal.
 */
@Serdeable
data class ErpClientPrices(
    @Nullable val subtotal: BigDecimal? = null,
    @Nullable val tax: BigDecimal? = null,
    @Nullable val total: BigDecimal? = null,
    val lines: List<ErpClientLine> = emptyList(),
)

@Serdeable
data class ErpClientLine(
    val itemId: Long,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val lineTotal: BigDecimal? = null,
)

/** Producto aun sin cotizacion: `key` lo pone el agente; `lineTotal` = cantidad × precio de lista. */
@Serdeable
data class ErpPreviewLine(
    val key: String,
    val quantity: BigDecimal,
    val lineTotal: BigDecimal,
    /** Con producto y medidas el ERP aplica el precio por medida (o la deja para el asesor). */
    @Nullable val productId: Long? = null,
    @Nullable val height: BigDecimal? = null,
    @Nullable val width: BigDecimal? = null,
    @Nullable val depth: BigDecimal? = null,
    /**
     * Con plantilla, el ERP calcula la linea como al agregarla a la
     * cotizacion (`lineTotal` se ignora). Los costos no salen del ERP.
     */
    @Nullable val templateId: Long? = null,
    @Nullable val params: Map<String, BigDecimal>? = null,
    @Nullable val choices: Map<String, String>? = null,
    @Nullable val preset: String? = null,
)

@Serdeable
data class ErpPreviewRequest(val lines: List<ErpPreviewLine>)

/** Un escenario de la vista previa por lotes: cada uno se valora solo, con sus propios cargos. */
@Serdeable
data class ErpPreviewScenario(val key: String, val lines: List<ErpPreviewLine>)

@Serdeable
data class ErpPreviewBatchRequest(val scenarios: List<ErpPreviewScenario>)

/** POST /api/quotes/client-prices/preview/batch. */
@Serdeable
data class ErpPreviewBatch(val scenarios: List<ErpScenarioPrices> = emptyList())

/** Los precios de un escenario; si el ERP no lo pudo valorar, montos nulos y `error` con el motivo. */
@Serdeable
data class ErpScenarioPrices(
    val key: String,
    @Nullable val subtotal: BigDecimal? = null,
    @Nullable val tax: BigDecimal? = null,
    @Nullable val total: BigDecimal? = null,
    val lines: List<ErpPreviewPrice> = emptyList(),
    @Nullable val error: String? = null,
)

/**
 * Vista previa (POST /api/quotes/client-prices/preview): con los cargos,
 * ganancia, IVA y redondeo de la empresa, repartidos dentro de cada producto.
 */
@Serdeable
data class ErpPreviewPrices(
    @Nullable val subtotal: BigDecimal? = null,
    @Nullable val tax: BigDecimal? = null,
    @Nullable val total: BigDecimal? = null,
    val lines: List<ErpPreviewPrice> = emptyList(),
)

@Serdeable
data class ErpPreviewPrice(
    val key: String,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val lineTotal: BigDecimal? = null,
    /** La medida se sale de la tolerancia: el precio lo pone un asesor. */
    val review: Boolean = false,
)

// ── catalogo por categorias (planificador) ──────────────────────────────────

/** Una categoria del catalogo del ERP (GET /api/categories). `parentId` null = categoria raiz. */
@Serdeable
data class CatalogCategory(val id: Long, val name: String, @Nullable val parentId: Long? = null)

/**
 * Un producto como lo muestra el planificador: con su categoria, su
 * descripcion (de ahi saca el agente de que esta hecho) y la version de su
 * foto (null = sin foto). Sin costos.
 */
@Serdeable
data class CatalogProduct(
    val id: Long,
    val sku: String,
    val name: String,
    @Nullable val price: BigDecimal? = null,
    @Nullable val unit: String? = null,
    @Nullable val categoryId: Long? = null,
    @Nullable val categoryName: String? = null,
    @Nullable val description: String? = null,
    @Nullable val imageVersion: Long? = null,
    @Nullable val status: String? = null,
    @Nullable val itemType: String? = null,
)

/** La foto de un producto tal como la guarda el ERP (ya reducida al subirla). */
class ProductImageData(val mime: String, val bytes: ByteArray)
