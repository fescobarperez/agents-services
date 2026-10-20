package com.erp_maya.agent.usage

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Un mes de uso en hora de Guatemala: `[inicio, fin)`. El mes en curso es
 * `partial` y sus dias llegan hasta hoy.
 */
data class UsageMonth(val mes: YearMonth, val hoy: LocalDate) {

    val key: String get() = mes.toString()
    val partial: Boolean get() = mes == YearMonth.from(hoy)
    val inicio: Instant get() = mes.atDay(1).atStartOfDay(ZONA).toInstant()
    val fin: Instant get() = mes.plusMonths(1).atDay(1).atStartOfDay(ZONA).toInstant()

    /** Los dias del mes (hasta hoy si esta en curso), `YYYY-MM-DD`. */
    fun dias(): List<String> {
        val ultimo = if (partial) hoy.dayOfMonth else mes.lengthOfMonth()
        return (1..ultimo).map { mes.atDay(it).toString() }
    }

    /** `n` meses terminando en este, del mas reciente al mas antiguo. */
    fun ultimos(n: Int): List<YearMonth> = (0 until n).map { mes.minusMonths(it.toLong()) }

    companion object {
        val ZONA: ZoneId = ZoneId.of("America/Guatemala")

        /** Mes `YYYY-MM`; nulo = el actual. Invalido o futuro → [IllegalArgumentException]. */
        fun de(texto: String?, ahora: Instant = Instant.now()): UsageMonth {
            val hoy = LocalDate.ofInstant(ahora, ZONA)
            val mes = if (texto.isNullOrBlank()) YearMonth.from(hoy) else try {
                require(Regex("""^\d{4}-(0[1-9]|1[0-2])$""").matches(texto)) { "Mes inválido (YYYY-MM)" }
                YearMonth.parse(texto)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Mes inválido (YYYY-MM)")
            }
            require(!mes.isAfter(YearMonth.from(hoy))) { "El mes no puede ser futuro" }
            return UsageMonth(mes, hoy)
        }
    }
}
