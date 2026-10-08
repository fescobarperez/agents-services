package com.erp_maya.agent.api.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.tools.domain.ToolResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Traduce resultados de herramientas a tarjetas del panel de trabajo.
 *
 * Las tarjetas salen de los DATOS que devolvio el ERP, no del texto del
 * modelo: el modelo redacta, pero lo que el panel muestra como precio o stock
 * es exactamente lo que respondio el sistema.
 *
 * Los montos van ya formateados: el canal pinta, no calcula ni redondea.
 */
object PanelCards {

    const val PRODUCT_RESULTS = "product_results"
    const val CUSTOMER = "customer"
    const val QUOTE_PREVIEW = "quote_preview"

    /**
     * Un gesto de Tino que el modelo decidio mostrar (`tino.sticker`). No es
     * del panel: solo lo pintan los canales que tienen stickers.
     */
    const val EXPRESSION = "expression"

    fun expresion(momento: String) = AgentEvent.Card(card = EXPRESSION, data = mapOf("moment" to momento))

    /** Tarjeta para un resultado, o null si esa herramienta no pinta nada. */
    fun desde(resultado: ToolResult.Ok): AgentEvent.Card? = when (resultado.name) {
        "productos.search" -> productos(resultado.data)
        else -> null
    }

    private fun productos(data: Any?): AgentEvent.Card? {
        val lista = (data as? List<*>)?.filterIsInstance<ProductSummary>().orEmpty()
        if (lista.isEmpty()) return null
        return AgentEvent.Card(
            card = PRODUCT_RESULTS,
            data = mapOf(
                "items" to lista.map { p ->
                    mapOf(
                        "id" to p.id,
                        "sku" to p.sku,
                        "name" to p.name,
                        "unit" to p.unit,
                        "warehouse" to p.branch,
                        "stock_label" to etiquetaStock(p.available, p.unit),
                        "price" to p.unitPrice?.let(::monto),
                    )
                },
            ),
        )
    }

    /** Datos del cliente de la cotizacion. */
    fun cliente(c: CustomerSummary) = AgentEvent.Card(
        card = CUSTOMER,
        data = mapOf(
            "id" to c.id,
            "name" to c.name,
            "tax_id_label" to "NIT",
            "tax_id" to c.nit,
            "phone" to c.phone,
            "email" to c.email,
        ),
    )

    /**
     * La cotizacion tal como quedo en el ERP. `estimated` mientras es
     * prospecto: el precio aun no lo reviso nadie.
     */
    fun cotizacion(q: ErpQuote) = AgentEvent.Card(
        card = QUOTE_PREVIEW,
        data = mapOf(
            "id" to q.id,
            "number" to q.docNumber,
            "status" to etiquetaEstado(q.status),
            "customer" to q.clientName,
            "lines" to q.items.map { i ->
                mapOf(
                    "id" to i.id,
                    "name" to (i.productName ?: "Línea ${i.id}"),
                    "qty" to cantidad(i.quantity),
                    "unit" to i.unitPrice?.let(::monto),
                    "total" to i.lineTotal?.let(::monto),
                )
            },
            "subtotal" to q.subtotal?.let(::monto),
            "tax_label" to q.taxRate?.let { "IVA ${cantidad(it)} %" },
            "tax" to q.tax?.let(::monto),
            "total" to q.total?.let(::monto),
            // Abierta o prospecto: nadie de ventas reviso todavia los precios.
            "estimated" to (q.status.equals("prospecto", ignoreCase = true) || q.status.equals("abierta", ignoreCase = true)),
        ),
    )

    private fun etiquetaEstado(s: String?) = when (s?.lowercase()) {
        null -> null
        "abierta" -> "En armado"
        "prospecto" -> "Prospecto"
        "borrador" -> "Borrador"
        "enviada" -> "Enviada"
        "aprobada" -> "Aprobada"
        else -> s.replaceFirstChar { it.uppercase() }
    }

    private fun etiquetaStock(disponible: BigDecimal?, unidad: String?): String? = when {
        disponible == null -> null
        disponible.signum() <= 0 -> "Sin stock"
        else -> "${cantidad(disponible)} en stock" + (unidad?.let { " ($it)" } ?: "")
    }

    // TODO: la moneda deberia venir de la empresa; el ERP aun no la expone.
    internal fun monto(valor: BigDecimal) = "Q " + formato("#,##0.00").format(valor.setScale(2, RoundingMode.HALF_UP))

    internal fun cantidad(valor: BigDecimal) = formato("#,##0.##").format(valor)

    private fun formato(patron: String) = DecimalFormat(patron, DecimalFormatSymbols(Locale.US))
}
