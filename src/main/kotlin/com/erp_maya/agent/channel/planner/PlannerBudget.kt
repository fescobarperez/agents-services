package com.erp_maya.agent.channel.planner

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Semaforo de presupuesto: el total de una opcion contra el monto del visitante.
 *
 * | estado | regla |
 * |---|---|
 * | `review` (gris) | algun precio lo confirma un asesor: no hay total confiable |
 * | `fits` (verde) | total ≤ umbral% del presupuesto |
 * | `tight` (ambar) | umbral% < total ≤ presupuesto |
 * | `over` (rojo) | total > presupuesto (+ sugerencia) |
 *
 * El umbral (90 % por defecto) es de cada planificador. Sin presupuesto no hay
 * semaforo (salvo `review`, que avisa del precio). No se guarda: sale de lo
 * guardado y del presupuesto vigente, asi que cambiar el monto no vuelve al ERP.
 */
object PlannerBudget {

    const val FITS = "fits"
    const val TIGHT = "tight"
    const val OVER = "over"
    const val REVIEW = "review"

    val UMBRAL_DEFECTO = BigDecimal("90")
    private val CIEN = BigDecimal("100")

    data class Semaforo(
        val status: String?,
        /** Lo que le sobra al visitante (fits/tight). */
        val remaining: BigDecimal? = null,
        /** Por cuanto se pasa (over). */
        val excess: BigDecimal? = null,
        /** total / presupuesto, sin tope: el widget decide hasta donde pinta la barra. */
        val ratio: BigDecimal? = null,
    )

    data class Sugerencia(val variant: PlannerVariant, val fits: Boolean)

    fun evaluar(total: BigDecimal?, porConfirmar: Boolean, presupuesto: BigDecimal?, umbralPct: BigDecimal): Semaforo {
        if (porConfirmar || total == null || total.signum() <= 0) return Semaforo(REVIEW)
        if (presupuesto == null || presupuesto.signum() <= 0) return Semaforo(null)
        val t = total.setScale(2, RoundingMode.HALF_UP)
        val b = presupuesto.setScale(2, RoundingMode.HALF_UP)
        val umbral = b.multiply(umbralPct).divide(CIEN, 2, RoundingMode.HALF_UP)
        val ratio = t.divide(b, 4, RoundingMode.HALF_UP)
        return when {
            t <= umbral -> Semaforo(FITS, remaining = b - t, ratio = ratio)
            t <= b -> Semaforo(TIGHT, remaining = b - t, ratio = ratio)
            else -> Semaforo(OVER, excess = t - b, ratio = ratio)
        }
    }

    /**
     * Un cambio real para volver al monto: de las variantes con precio y mas
     * baratas que la opcion, la que encaja perdiendo lo menos (el total mas
     * alto que no se pasa). Si ninguna encaja, la que mas acerca, con
     * `fits = false`. Null si la opcion no se pasa o no hay con que sugerir.
     */
    fun sugerencia(variantes: List<PlannerVariant>, total: BigDecimal?, presupuesto: BigDecimal?): Sugerencia? {
        if (total == null || presupuesto == null || presupuesto.signum() <= 0 || total <= presupuesto) return null
        val candidatas = variantes.filter { v -> !v.review && v.total != null && v.total.signum() > 0 && v.total < total }
        if (candidatas.isEmpty()) return null
        val encajan = candidatas.filter { it.total!! <= presupuesto }
        return if (encajan.isNotEmpty()) {
            Sugerencia(encajan.maxBy { it.total!! }, true)
        } else {
            Sugerencia(candidatas.minBy { it.total!! }, false)
        }
    }
}
