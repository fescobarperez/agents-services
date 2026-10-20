package com.erp_maya.agent.usage

import io.micronaut.data.jdbc.runtime.JdbcOperations
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Consultas de uso y costos. Las fechas van en `[desde, hasta)` y los dias y
 * meses se agrupan en hora de Guatemala. Sin transacciones propias: las abre
 * [UsageService].
 *
 * - Turnos (mensajes de IA): `agent_turns` en `done`.
 * - Llamadas: `agent_runs` (respuestas, resumenes y lectura de fotos), con su
 *   costo ya congelado en `cost_usd`.
 * - Imagenes de ejemplo: `render_jobs` en `done`, costo en `cost_usd`.
 */
@Singleton
open class UsageRepository(private val jdbc: JdbcOperations) {

    data class PorEmpresa(val tenantId: Long, val cantidad: Long, val costo: BigDecimal)
    data class Totales(val llamadas: Long, val tokensIn: Long, val tokensCached: Long, val tokensOut: Long, val costo: BigDecimal)
    data class PorModelo(val modelo: String, val proveedor: String, val llamadas: Long, val tokensIn: Long, val tokensCached: Long, val tokensOut: Long, val costo: BigDecimal)
    data class PorClave(val clave: String, val cantidad: Long, val costo: BigDecimal)

    // ── todas las empresas ──────────────────────────────────────────────────

    fun turnosPorEmpresa(desde: Instant, hasta: Instant): List<PorEmpresa> =
        lista(
            """
            SELECT cv.tenant_id, count(*) AS n, 0::numeric AS costo
            FROM agent_turns t JOIN conversations cv ON cv.id = t.conversation_id
            WHERE t.status = 'done' AND t.created_at >= ? AND t.created_at < ?
            GROUP BY cv.tenant_id
            """.trimIndent(), rango(desde, hasta),
        ) { porEmpresa(it) }

    fun llamadasPorEmpresa(desde: Instant, hasta: Instant): List<PorEmpresa> =
        lista(
            """
            SELECT tenant_id, count(*) AS n, coalesce(sum(cost_usd), 0) AS costo
            FROM agent_runs
            WHERE tenant_id IS NOT NULL AND created_at >= ? AND created_at < ?
            GROUP BY tenant_id
            """.trimIndent(), rango(desde, hasta),
        ) { porEmpresa(it) }

    fun imagenesPorEmpresa(desde: Instant, hasta: Instant): List<PorEmpresa> =
        lista(
            """
            SELECT tenant_id, count(*) AS n, coalesce(sum(cost_usd), 0) AS costo
            FROM render_jobs
            WHERE status = 'done' AND created_at >= ? AND created_at < ?
            GROUP BY tenant_id
            """.trimIndent(), rango(desde, hasta),
        ) { porEmpresa(it) }

    // ── una empresa ─────────────────────────────────────────────────────────

    /** Mensajes de IA (turnos completados) de la empresa en el rango. */
    fun turnosDeEmpresa(tenantId: Long, desde: Instant, hasta: Instant): Long =
        lista(
            """
            SELECT count(*) AS n FROM agent_turns t JOIN conversations cv ON cv.id = t.conversation_id
            WHERE cv.tenant_id = ? AND t.status = 'done' AND t.created_at >= ? AND t.created_at < ?
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { it.getLong("n") }.first()

    fun totales(tenantId: Long, desde: Instant, hasta: Instant): Totales =
        lista(
            """
            SELECT count(*) AS n, coalesce(sum(tokens_in), 0) AS t_in, coalesce(sum(tokens_cached), 0) AS t_cache,
                   coalesce(sum(tokens_out), 0) AS t_out, coalesce(sum(cost_usd), 0) AS costo
            FROM agent_runs WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { Totales(it.getLong("n"), it.getLong("t_in"), it.getLong("t_cache"), it.getLong("t_out"), it.getBigDecimal("costo")) }
            .first()

    fun llamadasPorModelo(tenantId: Long, desde: Instant, hasta: Instant): List<PorModelo> =
        lista(
            """
            SELECT m.model_name, p.code AS proveedor, count(*) AS n, sum(r.tokens_in) AS t_in,
                   sum(r.tokens_cached) AS t_cache, sum(r.tokens_out) AS t_out, sum(r.cost_usd) AS costo
            FROM agent_runs r JOIN ai_models m ON m.id = r.model_id JOIN ai_providers p ON p.id = m.provider_id
            WHERE r.tenant_id = ? AND r.created_at >= ? AND r.created_at < ?
            GROUP BY m.model_name, p.code ORDER BY costo DESC, n DESC
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) {
            PorModelo(it.getString("model_name"), it.getString("proveedor"), it.getLong("n"), it.getLong("t_in"),
                it.getLong("t_cache"), it.getLong("t_out"), it.getBigDecimal("costo"))
        }

    fun imagenesPorModelo(tenantId: Long, desde: Instant, hasta: Instant): List<PorModelo> =
        lista(
            """
            SELECT m.model_name, p.code AS proveedor, count(*) AS n, coalesce(sum(j.cost_usd), 0) AS costo
            FROM render_jobs j JOIN ai_models m ON m.id = j.model_id JOIN ai_providers p ON p.id = m.provider_id
            WHERE j.tenant_id = ? AND j.status = 'done' AND j.created_at >= ? AND j.created_at < ?
            GROUP BY m.model_name, p.code ORDER BY costo DESC
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { PorModelo(it.getString("model_name"), it.getString("proveedor"), it.getLong("n"), 0, 0, 0, it.getBigDecimal("costo")) }

    fun llamadasPorTipo(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> =
        lista(
            """
            SELECT kind AS clave, count(*) AS n, sum(cost_usd) AS costo FROM agent_runs
            WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
            GROUP BY kind
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { porClave(it) }

    fun turnosPorCanal(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> =
        lista(
            """
            SELECT ch.kind AS clave, count(*) AS n, 0::numeric AS costo
            FROM agent_turns t JOIN conversations cv ON cv.id = t.conversation_id JOIN channels ch ON ch.id = cv.channel_id
            WHERE cv.tenant_id = ? AND t.status = 'done' AND t.created_at >= ? AND t.created_at < ?
            GROUP BY ch.kind
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { porClave(it) }

    /** Llamadas e imagenes por canal (las imagenes, por el canal de su conversacion). */
    fun costoPorCanal(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> =
        lista(
            """
            SELECT clave, 0 AS n, sum(costo) AS costo FROM (
                SELECT coalesce(channel, '—') AS clave, cost_usd AS costo FROM agent_runs
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                UNION ALL
                SELECT ch.kind, coalesce(j.cost_usd, 0) FROM render_jobs j
                JOIN conversations cv ON cv.id = j.conversation_id JOIN channels ch ON ch.id = cv.channel_id
                WHERE j.tenant_id = ? AND j.status = 'done' AND j.created_at >= ? AND j.created_at < ?
            ) x GROUP BY clave
            """.trimIndent(),
            { st -> empresa(tenantId, desde, hasta)(st); bindEmpresa(st, 4, tenantId, desde, hasta) },
        ) { porClave(it) }

    /** Turnos por dia (`YYYY-MM-DD` en hora de Guatemala). */
    fun turnosPorDia(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> =
        lista(
            """
            SELECT to_char(t.created_at AT TIME ZONE '$ZONA', 'YYYY-MM-DD') AS clave, count(*) AS n, 0::numeric AS costo
            FROM agent_turns t JOIN conversations cv ON cv.id = t.conversation_id
            WHERE cv.tenant_id = ? AND t.status = 'done' AND t.created_at >= ? AND t.created_at < ?
            GROUP BY 1
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { porClave(it) }

    /** Costo (llamadas + imagenes) por dia. */
    fun costoPorDia(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> = costoAgrupado(tenantId, desde, hasta, "YYYY-MM-DD")

    /** Turnos por mes (`YYYY-MM`), para el historial. */
    fun turnosPorMes(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> =
        lista(
            """
            SELECT to_char(t.created_at AT TIME ZONE '$ZONA', 'YYYY-MM') AS clave, count(*) AS n, 0::numeric AS costo
            FROM agent_turns t JOIN conversations cv ON cv.id = t.conversation_id
            WHERE cv.tenant_id = ? AND t.status = 'done' AND t.created_at >= ? AND t.created_at < ?
            GROUP BY 1
            """.trimIndent(), empresa(tenantId, desde, hasta),
        ) { porClave(it) }

    fun costoPorMes(tenantId: Long, desde: Instant, hasta: Instant): List<PorClave> = costoAgrupado(tenantId, desde, hasta, "YYYY-MM")

    private fun costoAgrupado(tenantId: Long, desde: Instant, hasta: Instant, formato: String): List<PorClave> =
        lista(
            """
            SELECT to_char(creado AT TIME ZONE '$ZONA', '$formato') AS clave, 0 AS n, sum(costo) AS costo FROM (
                SELECT created_at AS creado, cost_usd AS costo FROM agent_runs
                WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                UNION ALL
                SELECT created_at, coalesce(cost_usd, 0) FROM render_jobs
                WHERE tenant_id = ? AND status = 'done' AND created_at >= ? AND created_at < ?
            ) x GROUP BY 1
            """.trimIndent(),
            { st -> empresa(tenantId, desde, hasta)(st); bindEmpresa(st, 4, tenantId, desde, hasta) },
        ) { porClave(it) }

    // ── utilidades ──────────────────────────────────────────────────────────

    private fun rango(desde: Instant, hasta: Instant): (PreparedStatement) -> Unit = { st ->
        st.setTimestamp(1, Timestamp.from(desde)); st.setTimestamp(2, Timestamp.from(hasta))
    }

    private fun empresa(tenantId: Long, desde: Instant, hasta: Instant): (PreparedStatement) -> Unit = { st ->
        bindEmpresa(st, 1, tenantId, desde, hasta)
    }

    private fun bindEmpresa(st: PreparedStatement, desdeIndice: Int, tenantId: Long, desde: Instant, hasta: Instant) {
        st.setLong(desdeIndice, tenantId)
        st.setTimestamp(desdeIndice + 1, Timestamp.from(desde))
        st.setTimestamp(desdeIndice + 2, Timestamp.from(hasta))
    }

    private fun porEmpresa(rs: ResultSet) = PorEmpresa(rs.getLong(1), rs.getLong("n"), rs.getBigDecimal("costo") ?: BigDecimal.ZERO)

    private fun porClave(rs: ResultSet) = PorClave(rs.getString("clave"), rs.getLong("n"), rs.getBigDecimal("costo") ?: BigDecimal.ZERO)

    private fun <T : Any> lista(sql: String, bind: (PreparedStatement) -> Unit = {}, fila: (ResultSet) -> T): List<T> =
        jdbc.prepareStatement(sql) { st ->
            bind(st)
            val rs = st.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += fila(rs)
            out
        }

    private companion object {
        const val ZONA = "America/Guatemala"
    }
}
