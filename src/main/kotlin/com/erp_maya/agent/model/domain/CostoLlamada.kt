package com.erp_maya.agent.model.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Precios por 1 000 tokens de un modelo (USD). `cached` nulo = se cobra como entrada. */
data class PreciosModelo(val input1k: BigDecimal, val cached1k: BigDecimal?, val output1k: BigDecimal)

/**
 * Costo en USD de una llamada al modelo, la misma regla que la consola
 * (src/lib/usage.js) y que API.md · «Uso y costos»:
 *
 *   ((tokens_in − cacheados) × entrada + cacheados × cacheado + tokens_out × salida) / 1000
 *
 * `tokens_in` incluye los cacheados (asi lo reporta el proveedor).
 */
object CostoLlamada {

    fun usd(uso: ModelUsage, p: PreciosModelo): BigDecimal {
        val cacheados = uso.cached.coerceIn(0, uso.input.coerceAtLeast(0))
        val nuevos = (uso.input - cacheados).coerceAtLeast(0)
        val total = BigDecimal(nuevos).multiply(p.input1k) +
            BigDecimal(cacheados).multiply(p.cached1k ?: p.input1k) +
            BigDecimal(uso.output.coerceAtLeast(0)).multiply(p.output1k)
        return total.divide(MIL, ESCALA, RoundingMode.HALF_UP)
    }

    private val MIL = BigDecimal(1000)
    private const val ESCALA = 6
}
