package com.erp_maya.agent.usage

import com.erp_maya.agent.admin.AdminException
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uso y costos de IA por empresa y mes, para la consola (via stack-line-services,
 * que le suma FEL, usuarios, sucursales, ingreso y margen).
 *
 * Costo = llamadas al modelo (`agent_runs.cost_usd`, congelado al registrarlas)
 * + imagenes de ejemplo (`render_jobs.cost_usd`). Mensajes de IA = turnos
 * completados; las llamadas internas (resumen, fotos, respaldo) cuestan pero
 * no cuentan.
 */
@Singleton
open class UsageService(private val repo: UsageRepository) {

    @Transactional(readOnly = true)
    open fun overview(mes: String?): UsageOverview {
        val m = mesDe(mes)
        val turnos = repo.turnosPorEmpresa(m.inicio, m.fin).associateBy { it.tenantId }
        val llamadas = repo.llamadasPorEmpresa(m.inicio, m.fin).associateBy { it.tenantId }
        val imagenes = repo.imagenesPorEmpresa(m.inicio, m.fin).associateBy { it.tenantId }
        val empresas = (turnos.keys + llamadas.keys + imagenes.keys).sorted()
        return UsageOverview(
            month = m.key,
            partial = m.partial,
            tenants = empresas.map { id ->
                TenantUsageSummary(
                    tenantId = id,
                    turns = turnos[id]?.cantidad ?: 0,
                    calls = llamadas[id]?.cantidad ?: 0,
                    images = imagenes[id]?.cantidad ?: 0,
                    costUsd = usd((llamadas[id]?.costo ?: BigDecimal.ZERO) + (imagenes[id]?.costo ?: BigDecimal.ZERO)),
                )
            },
        )
    }

    @Transactional(readOnly = true)
    open fun tenant(tenantId: Long, mes: String?, meses: Int = HISTORIAL): TenantUsageDetail {
        val m = mesDe(mes)
        val (desde, hasta) = m.inicio to m.fin
        val t = repo.totales(tenantId, desde, hasta)
        val imgModelos = repo.imagenesPorModelo(tenantId, desde, hasta)
        val imagenes = ImagesUsage(imgModelos.sumOf { it.llamadas }, usd(imgModelos.fold(BigDecimal.ZERO) { a, x -> a + x.costo }))
        val turnos = repo.turnosPorCanal(tenantId, desde, hasta)

        val porModelo = repo.llamadasPorModelo(tenantId, desde, hasta).map {
            ModelUsageRow(it.modelo, it.proveedor, it.llamadas, null, it.tokensIn, it.tokensCached, it.tokensOut, usd(it.costo))
        } + imgModelos.map { ModelUsageRow(it.modelo, it.proveedor, it.llamadas, it.llamadas, 0, 0, 0, usd(it.costo)) }

        val porTipo = repo.llamadasPorTipo(tenantId, desde, hasta)
            .sortedBy { ORDEN_TIPOS.indexOf(it.clave).let { i -> if (i < 0) 99 else i } }
            .map { KindUsageRow(it.clave, it.cantidad, usd(it.costo)) } +
            listOfNotNull(KindUsageRow("image", imagenes.count, imagenes.costUsd).takeIf { imagenes.count > 0 })

        val costoCanal = repo.costoPorCanal(tenantId, desde, hasta).associateBy { it.clave }
        val turnosCanal = turnos.associateBy { it.clave }
        val porCanal = (turnosCanal.keys + costoCanal.keys).sorted().map { c ->
            ChannelUsageRow(c, turnosCanal[c]?.cantidad ?: 0, usd(costoCanal[c]?.costo ?: BigDecimal.ZERO))
        }

        val turnosDia = repo.turnosPorDia(tenantId, desde, hasta).associateBy { it.clave }
        val costoDia = repo.costoPorDia(tenantId, desde, hasta).associateBy { it.clave }
        val diario = m.dias().map { d -> DailyUsageRow(d, turnosDia[d]?.cantidad ?: 0, usd(costoDia[d]?.costo ?: BigDecimal.ZERO)) }

        val historial = m.ultimos(meses.coerceIn(1, 24))
        val desdeHist = UsageMonth(historial.last(), m.hoy).inicio
        val turnosMes = repo.turnosPorMes(tenantId, desdeHist, hasta).associateBy { it.clave }
        val costoMes = repo.costoPorMes(tenantId, desdeHist, hasta).associateBy { it.clave }
        val historia = historial.map { ym ->
            val k = ym.toString()
            MonthUsageRow(k, turnosMes[k]?.cantidad ?: 0, usd(costoMes[k]?.costo ?: BigDecimal.ZERO))
        }

        return TenantUsageDetail(
            tenantId = tenantId,
            month = m.key,
            partial = m.partial,
            turns = turnos.sumOf { it.cantidad },
            calls = t.llamadas,
            tokensIn = t.tokensIn,
            tokensCached = t.tokensCached,
            tokensOut = t.tokensOut,
            costUsd = usd(t.costo + imagenes.costUsd),
            images = imagenes,
            byModel = porModelo,
            byKind = porTipo,
            byChannel = porCanal,
            daily = diario,
            history = historia,
        )
    }

    private fun mesDe(texto: String?): UsageMonth =
        try {
            UsageMonth.de(texto)
        } catch (e: IllegalArgumentException) {
            throw AdminException.validation(mapOf("month" to (e.message ?: "Mes inválido")))
        }

    private fun usd(v: BigDecimal): BigDecimal = v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().let {
        if (it.scale() < 0) it.setScale(0) else it
    }

    private companion object {
        const val HISTORIAL = 6
        val ORDEN_TIPOS = listOf("turn", "summary", "vision")
    }
}
