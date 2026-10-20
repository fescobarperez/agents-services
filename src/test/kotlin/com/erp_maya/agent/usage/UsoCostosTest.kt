package com.erp_maya.agent.usage

import com.erp_maya.agent.model.domain.CostoLlamada
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.PreciosModelo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import java.math.BigDecimal
import java.time.Instant

/** Las reglas de uso y costos que comparten agents-services y la consola (src/lib/usage.js). */
class UsoCostosTest {

    private val gpt4o = PreciosModelo(BigDecimal("0.0025"), BigDecimal("0.00125"), BigDecimal("0.01"))

    @Test
    fun `el costo descuenta los cacheados de la entrada y los cobra a su precio`() {
        // 1000 nuevos × 0.0025 + 1000 cacheados × 0.00125 + 500 × 0.01 = 2.5 + 1.25 + 5 = 8.75 / 1000
        val c = CostoLlamada.usd(ModelUsage(input = 2000, cached = 1000, output = 500), gpt4o)
        assertEquals(0, BigDecimal("0.00875").compareTo(c), c.toPlainString())
    }

    @Test
    fun `sin precio de cacheado se cobran como entrada y nunca mas cacheados que entrada`() {
        val sinCache = gpt4o.copy(cached1k = null)
        assertEquals(0, BigDecimal("0.005").compareTo(CostoLlamada.usd(ModelUsage(2000, 1000, 0), sinCache)))
        // cached > input (dato raro del proveedor): se acota a la entrada.
        assertEquals(0, BigDecimal("0.00125").compareTo(CostoLlamada.usd(ModelUsage(1000, 5000, 0), gpt4o)))
        assertEquals(0, BigDecimal.ZERO.compareTo(CostoLlamada.usd(ModelUsage(), gpt4o)))
    }

    @Test
    fun `el mes va en hora de Guatemala`() {
        // 1 de nov 03:00 UTC = 31 de oct 21:00 en Guatemala: sigue siendo octubre.
        val m = UsageMonth.de(null, Instant.parse("2026-11-01T03:00:00Z"))
        assertEquals("2026-10", m.key)
        assertTrue(m.partial)
        assertEquals(Instant.parse("2026-10-01T06:00:00Z"), m.inicio)
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), m.fin)
        assertEquals(31, m.dias().size)
    }

    @Test
    fun `un mes cerrado trae todos sus dias y el historial va hacia atras`() {
        val m = UsageMonth.de("2026-02", Instant.parse("2026-10-19T18:00:00Z"))
        assertFalse(m.partial)
        assertEquals(28, m.dias().size)
        assertEquals(listOf("2026-02", "2026-01", "2025-12"), m.ultimos(3).map { it.toString() })
        val hoy = UsageMonth.de("2026-10", Instant.parse("2026-10-19T18:00:00Z"))
        assertEquals("2026-10-19", hoy.dias().last())
    }

    @Test
    fun `mes invalido o futuro se rechaza`() {
        val ahora = Instant.parse("2026-10-19T18:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { UsageMonth.de("2026-13", ahora) }
        assertThrows(IllegalArgumentException::class.java) { UsageMonth.de("oct", ahora) }
        assertThrows(IllegalArgumentException::class.java) { UsageMonth.de("2026-11", ahora) }
    }
}
