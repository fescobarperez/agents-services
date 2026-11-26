package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerBudget.FITS
import com.erp_maya.agent.channel.planner.PlannerBudget.OVER
import com.erp_maya.agent.channel.planner.PlannerBudget.REVIEW
import com.erp_maya.agent.channel.planner.PlannerBudget.TIGHT
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El semaforo y la sugerencia de «se pasa por», sin base de datos ni ERP. */
class PlannerBudgetTest {

    private val noventa = q("90")

    private fun estado(total: String?, presupuesto: String?, umbral: String = "90", review: Boolean = false) =
        PlannerBudget.evaluar(total?.let { q(it) }, review, presupuesto?.let { q(it) }, q(umbral))

    // ── semaforo ────────────────────────────────────────────────────────────

    @Test
    fun `el caso de la pergola`() {
        val a = estado("16400", "20000")
        assertEquals(FITS, a.status)
        assertEquals(q("3600.00"), a.remaining)
        assertNull(a.excess)
        assertEquals(q("0.8200"), a.ratio)

        val b = estado("24800", "20000")
        assertEquals(OVER, b.status)
        assertEquals(q("4800.00"), b.excess)
        assertNull(b.remaining)
        assertEquals(q("1.2400"), b.ratio, "sin tope: el widget decide hasta donde pinta")
    }

    @Test
    fun `el 90 por ciento exacto todavia encaja`() {
        assertEquals(FITS, estado("18000", "20000").status)
        assertEquals(TIGHT, estado("18000.01", "20000").status)
    }

    @Test
    fun `el monto exacto es justo y un centavo mas se pasa`() {
        val justo = estado("20000", "20000")
        assertEquals(TIGHT, justo.status)
        assertEquals(q("0.00"), justo.remaining)
        val pasa = estado("20000.01", "20000")
        assertEquals(OVER, pasa.status)
        assertEquals(q("0.01"), pasa.excess)
    }

    @Test
    fun `el umbral es de cada planificador`() {
        assertEquals(TIGHT, estado("17500", "20000", umbral = "85").status, "85 % de 20,000 = 17,000")
        assertEquals(FITS, estado("17000", "20000", umbral = "85").status)
        assertEquals(FITS, estado("20000", "20000", umbral = "100").status, "con 100 % no hay ambar")
        assertEquals(TIGHT, estado("15001", "30000", umbral = "50").status)
    }

    @Test
    fun `por confirmar gana aunque haya total o presupuesto`() {
        assertEquals(REVIEW, estado("16400", "20000", review = true).status)
        assertEquals(REVIEW, estado(null, "20000").status)
        assertEquals(REVIEW, estado("0", "20000").status)
        assertEquals(REVIEW, estado("16400", null, review = true).status, "sin presupuesto igual avisa del precio")
        assertNull(estado("16400", "20000", review = true).remaining)
    }

    @Test
    fun `sin presupuesto no hay semaforo`() {
        assertNull(estado("16400", null).status)
        assertNull(estado("16400", "0").status)
        assertNull(estado("16400", null).ratio)
    }

    @Test
    fun `presupuestos grandes y con centavos`() {
        assertEquals(FITS, estado("850000", "1000000").status)
        assertEquals(q("0.01"), estado("1000.00", "1000.01").remaining)
    }

    // ── sugerencia ──────────────────────────────────────────────────────────

    private val variantesB = listOf(
        PlannerVariant(mapOf("luces" to "sin"), "Iluminación: Sin luces", q("21300")),
        PlannerVariant(mapOf("cubierta" to "policarbonato"), "Cubierta: Policarbonato", q("19900")),
        PlannerVariant(mapOf("techo" to "una_agua"), "Techo: Una agua", q("24300")),
    )

    @Test
    fun `sugiere el cambio que encaja perdiendo lo menos`() {
        val s = PlannerBudget.sugerencia(variantesB, q("24800"), q("20000"))!!
        assertEquals("Cubierta: Policarbonato", s.variant.label)
        assertTrue(s.fits)
    }

    @Test
    fun `con otro presupuesto la sugerencia cambia sin volver a valorar`() {
        val s = PlannerBudget.sugerencia(variantesB, q("24800"), q("22000"))!!
        assertEquals("Iluminación: Sin luces", s.variant.label, "las dos encajan: se pierde menos quitando las luces")
        assertTrue(s.fits)
    }

    @Test
    fun `si ninguna encaja sugiere la que mas acerca`() {
        val s = PlannerBudget.sugerencia(variantesB, q("24800"), q("15000"))!!
        assertEquals("Cubierta: Policarbonato", s.variant.label)
        assertFalse(s.fits)
    }

    @Test
    fun `ignora variantes por confirmar, sin total o que no bajan el precio`() {
        val raras = listOf(
            PlannerVariant(mapOf("a" to "1"), "por confirmar", q("1000"), review = true),
            PlannerVariant(mapOf("a" to "2"), "sin total", null),
            PlannerVariant(mapOf("a" to "3"), "mas cara", q("30000")),
            PlannerVariant(mapOf("a" to "4"), "igual", q("24800")),
        )
        assertNull(PlannerBudget.sugerencia(raras, q("24800"), q("20000")))
    }

    @Test
    fun `si no se pasa no hay sugerencia`() {
        assertNull(PlannerBudget.sugerencia(variantesB, q("16400"), q("20000")))
        assertNull(PlannerBudget.sugerencia(variantesB, q("20000"), q("20000")))
        assertNull(PlannerBudget.sugerencia(variantesB, q("24800"), null))
        assertNull(PlannerBudget.sugerencia(emptyList(), q("24800"), q("20000")))
    }

    @Test
    fun `la vista solo sugiere cuando se pasa`() {
        val b = PlannerOptionRow("B", "A", "pergola", "B", null, OptionSpec(1), emptyList(), "priced",
            q("24800"), null, null, variantesB, q("20000"))
        val vista = PlannerService.vista(b, q("20000"), noventa)
        assertEquals(OVER, vista.budget.status)
        assertEquals(q("19900"), vista.suggestion!!.total)
        assertNull(PlannerService.vista(b, q("30000"), noventa).suggestion)
        val revisar = b.copy(pricing = "review", total = null)
        assertEquals(REVIEW, PlannerService.vista(revisar, q("20000"), noventa).budget.status)
        assertNull(PlannerService.vista(revisar, q("20000"), noventa).suggestion)
    }
}
