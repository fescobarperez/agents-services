package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpPreviewLine
import com.erp_maya.agent.erp.domain.ErpPreviewScenario
import com.erp_maya.agent.erp.domain.ErpScenarioPrices
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Precio total de una opcion con la vista previa del ERP: cargos, ganancia,
 * IVA y redondeo de la empresa ya van dentro, igual que en la cotizacion.
 *
 * La opcion y sus variantes (un cambio cada una) van en UNA llamada por lotes:
 * cada escenario se valora solo, con sus propios cargos fijos. Con eso la
 * sugerencia de «se pasa por» sale despues sin volver al ERP.
 */
@Singleton
open class PlannerPricing(
    private val erp: ErpClient,
    @param:Value("\${planner.max-variants:8}") private val maxVariantes: Int,
) {

    data class Precio(val total: BigDecimal?, val subtotal: BigDecimal?, val tax: BigDecimal?, val review: Boolean)

    data class Valoracion(val precio: Precio, val variantes: List<PlannerVariant>)

    /**
     * [ErpException] si el ERP no responde; [PlannerSpecException] si el ERP
     * rechaza la opcion (p. ej. una medida que la plantilla no admite).
     */
    open fun valorar(tenantId: Long, spec: OptionSpec, producto: PlannerCatalog.Producto): Valoracion {
        val variantes = producto.template?.let { PlannerSpecs.variantes(spec, it, maxVariantes.coerceIn(0, MAX_LOTE - 1)) }.orEmpty()
        val escenarios = listOf(ErpPreviewScenario(BASE, listOf(linea(spec, producto)))) +
            variantes.mapIndexed { i, v -> ErpPreviewScenario(clave(i), listOf(linea(spec.copy(choices = spec.choices + v.change), producto))) }
        val r = erp.previewClientPricesBatch(tenantId, PlannerCatalog.SIN_CONVERSACION, escenarios).associateBy { it.key }
        val base = r[BASE] ?: throw ErpException("El ERP no devolvió el precio de la opción")
        base.error?.let { throw PlannerSpecException("spec", it) }
        return Valoracion(
            precio(base),
            variantes.mapIndexed { i, v ->
                val p = r[clave(i)]?.let { precio(it) }
                v.copy(total = p?.total, review = p == null || p.review)
            },
        )
    }

    companion object {
        const val BASE = "base"
        const val LINEA = "p"
        /** Escenarios por llamada que acepta el ERP. */
        const val MAX_LOTE = 10

        private fun clave(i: Int) = "v$i"

        /**
         * La linea de la opcion para el ERP. Con plantilla, el ERP la calcula
         * (los costos no salen de alla). Sin ella, precio de lista × cantidad
         * y las medidas, para que el ERP aplique su precio por medida.
         */
        fun linea(spec: OptionSpec, producto: PlannerCatalog.Producto): ErpPreviewLine =
            if (spec.templateId != null) {
                ErpPreviewLine(
                    LINEA, spec.quantity, BigDecimal.ZERO,
                    templateId = spec.templateId, params = spec.params, choices = spec.choices, preset = spec.preset,
                )
            } else {
                val lista = producto.product.unitPrice?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO
                ErpPreviewLine(
                    LINEA, spec.quantity, lista.multiply(spec.quantity).setScale(2, RoundingMode.HALF_UP),
                    productId = spec.productId,
                    height = spec.params["alto"], width = spec.params["ancho"], depth = spec.params["profundidad"],
                )
            }

        /**
         * Sin total confiable si alguna linea la cotiza un asesor (medida fuera
         * de tolerancia, materia prima sin costo, producto sin precio): entonces
         * no se da un total parcial al visitante.
         */
        fun precio(s: ErpScenarioPrices): Precio {
            val review = s.error != null || s.total == null || s.total.signum() <= 0 || s.lines.isEmpty() || s.lines.any { it.review }
            return Precio(s.total?.let(::dinero)?.takeIf { !review }, s.subtotal?.let(::dinero), s.tax?.let(::dinero), review)
        }

        /** A centavos: igual en la columna NUMERIC(14,2) que en el JSON de las variantes. */
        private fun dinero(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)
    }
}
