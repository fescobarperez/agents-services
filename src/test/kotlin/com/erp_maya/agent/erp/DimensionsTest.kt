package com.erp_maya.agent.erp

import com.erp_maya.agent.erp.domain.Dimensions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DimensionsTest {

    private fun d(h: String?, w: String?, p: String?) =
        Dimensions(h?.let(::BigDecimal), w?.let(::BigDecimal), p?.let(::BigDecimal))

    @Test
    fun `compara por valor numerico`() {
        assertTrue(d("1.8", "1.5", "0.5").igualA(d("1.80", "1.500", "0.5")))
        assertFalse(d("2.0", "1.5", "0.6").igualA(d("1.8", "1.5", "0.5")))
        assertFalse(d("1.8", "1.5", null).igualA(d("1.8", "1.5", "0.5")))
    }

    @Test
    fun `sin medidas no hay dimensiones`() {
        assertNull(Dimensions.de(null, null, null))
        assertTrue(Dimensions().vacia())
    }

    @Test
    fun `texto legible`() {
        assertEquals("2 × 1.5 × 0.6 m", d("2.00", "1.50", "0.60").texto())
        assertEquals("2 × 1.5 cm", d("2", "1.5", null).texto("cm"))
    }
}
