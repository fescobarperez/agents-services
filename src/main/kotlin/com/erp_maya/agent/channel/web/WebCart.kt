package com.erp_maya.agent.channel.web

import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.erp.domain.ErpClientPrices
import com.erp_maya.agent.erp.domain.ErpPreviewLine
import com.erp_maya.agent.erp.domain.ErpPreviewPrices
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

/**
 * El carrito del widget: lo que el cliente ya confirmo, antes de pedir el
 * PDF. Es solo una vista: los cambios se le piden al agente por el chat, que
 * es quien aplica las reglas del playbook.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebCart(
    /** Numero de la cotizacion en el ERP; null mientras las lineas esperan los datos del cliente. */
    val number: String?,
    val items: List<WebCartItem>,
    /** false: el widget no muestra ningun precio (lo decide la empresa en la consola). */
    val showPrices: Boolean,
    val currency: String,
    val subtotal: BigDecimal?,
    val tax: BigDecimal?,
    val total: BigDecimal?,
    /** true si los montos son sin impuestos (aun no hay cotizacion en el ERP). */
    val estimated: Boolean,
    /** Hay lineas que cotiza un asesor: el total aun puede cambiar. */
    val pendingPrices: Boolean,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebCartItem(
    val name: String,
    /** Medidas o descripcion corta (p. ej. «3 × 4.5 m»). */
    val detail: String?,
    val quantity: BigDecimal,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val lineTotal: BigDecimal?,
    /** El precio lo confirma un asesor. */
    val pendingPrice: Boolean,
)

object WebCarts {

    /**
     * Arma el carrito desde la cotizacion del ERP (si ya existe) o desde las
     * lineas en espera del borrador. Null si no hay nada que mostrar.
     *
     * Respeta lo que la empresa decide mostrar: lineas ocultas al cliente no
     * salen y, con precio global, solo se ve el total.
     *
     * El precio de cada producto es el de `precios` (ganancia y cargos ya
     * repartidos dentro): asi las lineas suman el subtotal. Sin esos precios
     * no se muestra el de las lineas, solo los totales: el precio base sin
     * cargos no cuadra con el subtotal y deja ver el margen.
     */
    fun armar(
        borrador: QuoteDraft?,
        cotizacion: ErpQuote?,
        mostrarPrecios: Boolean,
        moneda: String,
        precios: ErpClientPrices? = null,
        /** Productos en espera: precios con los cargos de la empresa (vista previa del ERP). */
        previa: ErpPreviewPrices? = null,
    ): WebCart? {
        if (cotizacion != null) {
            val global = cotizacion.priceDisplay == "global"
            val porLinea = precios?.lines.orEmpty().associateBy { it.itemId }
            val items = cotizacion.items.filter { it.showToClient }.map { i ->
                val porCotizar = i.sizePricing == "review" || (i.unitPrice?.signum() ?: 0) == 0
                val alCliente = porLinea[i.id]?.takeIf { mostrarPrecios && !global && !porCotizar }
                WebCartItem(
                    name = (i.productName ?: i.description ?: "Producto").take(120),
                    detail = medidas(i.dimensions, i.dimensionUnit) ?: i.description?.takeIf { it != i.productName }?.take(160),
                    quantity = i.quantity.stripTrailingZeros(),
                    unit = i.uom,
                    unitPrice = alCliente?.unitPrice,
                    lineTotal = alCliente?.lineTotal,
                    pendingPrice = porCotizar,
                )
            }
            if (items.isEmpty()) return null
            return WebCart(
                number = cotizacion.docNumber,
                items = items,
                showPrices = mostrarPrecios,
                currency = moneda,
                // Todo por confirmar: sin montos en cero, el widget dice «Por confirmar».
                subtotal = (precios?.subtotal ?: cotizacion.subtotal).takeIf { mostrarPrecios && !global && it.positivo() },
                tax = (precios?.tax ?: cotizacion.tax).takeIf { mostrarPrecios && !global && it.positivo() },
                total = (precios?.total ?: cotizacion.total).takeIf { mostrarPrecios && it.positivo() },
                estimated = false,
                pendingPrices = items.any { it.pendingPrice },
            )
        }
        val lineas = borrador?.pendientes.orEmpty()
        if (lineas.isEmpty()) return null
        // Sin la vista previa no se muestra el precio de lista: no lleva los
        // cargos y no cuadraria con la cotizacion.
        val porClave = previa?.lines.orEmpty().associateBy { it.key }
        val items = lineas.mapIndexed { k, l ->
            val previo = porClave[clave(k)]
            val porCotizar = (l.unitPrice?.signum() ?: 0) == 0 || previo?.review == true
            val alCliente = previo?.takeIf { mostrarPrecios && !porCotizar }
            WebCartItem(
                name = l.name.take(120),
                detail = medidas(l.dimensions, null),
                quantity = l.quantity.stripTrailingZeros(),
                unit = l.unit,
                unitPrice = alCliente?.unitPrice,
                lineTotal = alCliente?.lineTotal,
                pendingPrice = porCotizar,
            )
        }
        val subtotal = previa?.subtotal?.takeIf { mostrarPrecios && it.signum() > 0 }
        return WebCart(
            number = null,
            items = items,
            showPrices = mostrarPrecios,
            currency = moneda,
            subtotal = subtotal,
            tax = null,
            total = null,
            estimated = true,
            pendingPrices = items.any { it.pendingPrice },
        )
    }

    /** Lo que se le pide al ERP para la vista previa: solo las lineas con precio de lista. */
    fun lineasPrevia(borrador: QuoteDraft?): List<ErpPreviewLine> =
        borrador?.pendientes.orEmpty().mapIndexedNotNull { k, l ->
            l.unitPrice?.takeIf { it.signum() > 0 }?.let {
                ErpPreviewLine(
                    clave(k), l.quantity, it.multiply(l.quantity).setScale(2, RoundingMode.HALF_UP),
                    productId = l.productId, height = l.dimensions?.height, width = l.dimensions?.width, depth = l.dimensions?.depth,
                )
            }
        }

    private fun clave(k: Int) = "p$k"

    private fun BigDecimal?.positivo() = this != null && this.signum() > 0

    /** «3 × 4.5 m» con las medidas que haya, en el orden ancho × largo × alto. */
    internal fun medidas(d: Dimensions?, unidad: String?): String? {
        if (d == null || d.vacia()) return null
        val partes = listOfNotNull(d.width, d.depth, d.height).map { it.stripTrailingZeros().toPlainString() }
        return partes.joinToString(" × ") + " " + (unidad?.takeIf { it.isNotBlank() } ?: "m")
    }

    /** Moneda del pais del locale de la empresa (es-GT → GTQ). */
    fun moneda(locale: String?): String = runCatching {
        Currency.getInstance(Locale.forLanguageTag(locale ?: "es-GT")).currencyCode
    }.getOrDefault("GTQ")
}
