package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_PERGOLA
import com.erp_maya.agent.channel.planner.PlannerFixtures.pergola
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reglas de una opcion: combinar, validar, detalles, titulo y variantes. */
class PlannerSpecsTest {

    private val tipo = ProjectType("pergola", "Pérgola", PRODUCTO_PERGOLA)
    private val p = pergola()

    private fun opcionA() = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, null, OptionChanges(choices = mapOf("techo" to "dos_aguas")))

    private fun error(spec: OptionSpec, plantilla: com.erp_maya.agent.erp.domain.ProductTemplate? = p) =
        assertThrows(PlannerSpecException::class.java) { PlannerSpecs.validar(spec, plantilla) }

    // ── combinar ────────────────────────────────────────────────────────────

    @Test
    fun `la primera opcion lleva la plantilla y cantidad uno`() {
        val a = opcionA()
        assertEquals(PlannerFixtures.PLANTILLA_PERGOLA, a.templateId)
        assertEquals(mapOf("techo" to "dos_aguas"), a.choices)
        assertEquals(q("1"), a.quantity)
        assertNull(a.preset)
    }

    @Test
    fun `una opcion derivada parte de su padre y solo cambia lo pedido`() {
        val a = opcionA().copy(params = mapOf("ancho" to q("4"), "profundidad" to q("3")))
        val b = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, a, OptionChanges(choices = mapOf("cubierta" to "teja", "luces" to "led")))
        assertEquals(mapOf("techo" to "dos_aguas", "cubierta" to "teja", "luces" to "led"), b.choices)
        assertEquals(a.params, b.params)
        val c = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, b, OptionChanges(params = mapOf("ancho" to q("5"))))
        assertEquals(q("5"), c.params["ancho"])
        assertEquals(q("3"), c.params["profundidad"])
    }

    @Test
    fun `el paquete se pone y se quita`() {
        val conPaquete = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, opcionA(), OptionChanges(preset = "premium"))
        assertEquals("premium", conPaquete.preset)
        assertEquals("premium", PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, conPaquete, OptionChanges()).preset)
        assertNull(PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, conPaquete, OptionChanges(preset = "")).preset)
    }

    @Test
    fun `el padre de otro producto no se hereda`() {
        val otro = opcionA().copy(productId = 999)
        assertTrue(PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, otro, OptionChanges()).choices.isEmpty())
    }

    // ── validar ─────────────────────────────────────────────────────────────

    @Test
    fun `una opcion completa pasa`() {
        PlannerSpecs.validar(opcionA().copy(params = mapOf("ancho" to q("4"), "profundidad" to q("3"))), p)
        PlannerSpecs.validar(opcionA().copy(preset = "premium"), p)
    }

    @Test
    fun `medidas desconocidas, en cero o fuera de rango`() {
        assertEquals("params.largo", error(opcionA().copy(params = mapOf("largo" to q("3")))).field)
        assertEquals("params.ancho", error(opcionA().copy(params = mapOf("ancho" to q("0")))).field)
        assertEquals("Ancho no puede pasar de 8 m", error(opcionA().copy(params = mapOf("ancho" to q("9")))).message)
        assertEquals("Alto debe ser al menos 2 m", error(opcionA().copy(params = mapOf("alto" to q("1.5")))).message)
    }

    @Test
    fun `una medida obligatoria sin valor por defecto hay que darla`() {
        val sinDefecto = p.copy(params = p.params.map { if (it.code == "ancho") it.copy(defaultValue = null) else it })
        assertEquals("Falta Ancho", error(opcionA(), sinDefecto).message)
    }

    @Test
    fun `elecciones y paquetes que la plantilla no tiene`() {
        assertEquals("choices.color", error(opcionA().copy(choices = mapOf("color" to "nogal"))).field)
        assertEquals("Cubierta no tiene «vidrio»", error(opcionA().copy(choices = mapOf("cubierta" to "vidrio"))).message)
        assertEquals("preset", error(opcionA().copy(preset = "lujo")).field)
    }

    @Test
    fun `una opcion obligatoria sin eleccion por defecto hay que elegirla`() {
        val sinDefecto = p.copy(options = p.options.map { o ->
            if (o.code == "techo") o.copy(choices = o.choices.map { it.copy(isDefault = false) }) else o
        })
        val spec = PlannerSpecs.combinar(PRODUCTO_PERGOLA, sinDefecto, null, OptionChanges())
        assertEquals("Falta elegir Techo", error(spec, sinDefecto).message)
        PlannerSpecs.validar(spec.copy(choices = mapOf("techo" to "dos_aguas")), sinDefecto)
    }

    @Test
    fun `cantidad entre 1 y 1000`() {
        assertEquals("quantity", error(opcionA().copy(quantity = q("0"))).field)
        assertEquals("quantity", error(opcionA().copy(quantity = q("1001"))).field)
    }

    @Test
    fun `sin plantilla solo se aceptan medidas`() {
        val deck = OptionSpec(PlannerFixtures.PRODUCTO_DECK, params = mapOf("ancho" to q("5"), "profundidad" to q("4")))
        PlannerSpecs.validar(deck, null)
        assertEquals("params.color", error(deck.copy(params = mapOf("color" to q("1"))), null).field)
        assertEquals("choices", error(deck.copy(choices = mapOf("techo" to "x")), null).field)
    }

    // ── detalles, titulo y resumen ──────────────────────────────────────────

    @Test
    fun `los detalles llevan medidas por defecto y una fila por opcion`() {
        val d = PlannerSpecs.detalles(opcionA(), p, null)
        assertEquals(listOf("medidas", "techo", "cubierta", "luces"), d.map { it.key })
        assertEquals("4 × 3 × 2.4 m", d[0].value)
        assertEquals(PlannerDetail.SIZE, d[0].kind)
        assertEquals("Dos aguas", d[1].value)
        assertEquals("Policarbonato", d[2].value, "la eleccion por defecto tambien se anota")
        assertTrue(d.none { it.changed }, "la primera opcion no resalta nada")
    }

    @Test
    fun `se resalta solo lo que cambio respecto del padre`() {
        val a = opcionA()
        val da = PlannerSpecs.detalles(a, p, null)
        val b = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, a, OptionChanges(choices = mapOf("cubierta" to "teja", "luces" to "led")))
        val db = PlannerSpecs.detalles(b, p, da)
        assertEquals(listOf("cubierta", "luces"), db.filter { it.changed }.map { it.key })
    }

    @Test
    fun `la cantidad solo se anota si es mas de una`() {
        val d = PlannerSpecs.detalles(opcionA().copy(quantity = q("2")), p, null)
        assertEquals("2", d.first { it.key == "cantidad" }.value)
    }

    @Test
    fun `titulo con las dos primeras elecciones y resumen con el resto y las medidas`() {
        val d = PlannerSpecs.detalles(opcionA(), p, null)
        assertEquals("Pérgola · Dos aguas, Policarbonato", PlannerSpecs.titulo(tipo, d, null))
        assertEquals("Sin luces · 4 × 3 × 2.4 m", PlannerSpecs.resumen(d))
        assertEquals("Pérgola de la terraza", PlannerSpecs.titulo(tipo, d, "  Pérgola de la terraza "))
        assertEquals(120, PlannerSpecs.titulo(tipo, d, "x".repeat(300)).length)
    }

    @Test
    fun `sin plantilla el titulo es el tipo`() {
        val d = PlannerSpecs.detalles(OptionSpec(1, params = mapOf("ancho" to q("5"), "profundidad" to q("4"))), null, null)
        assertEquals("Pérgola", PlannerSpecs.titulo(tipo, d, null))
        assertEquals("5 × 4 m", PlannerSpecs.resumen(d))
    }

    // ── variantes ───────────────────────────────────────────────────────────

    @Test
    fun `las variantes cambian una eleccion y empiezan por los extras`() {
        val b = PlannerSpecs.combinar(PRODUCTO_PERGOLA, p, opcionA(), OptionChanges(choices = mapOf("cubierta" to "teja", "luces" to "led")))
        val v = PlannerSpecs.variantes(b, p, 8)
        assertEquals(listOf(mapOf("luces" to "sin"), mapOf("techo" to "una_agua"), mapOf("cubierta" to "policarbonato")), v.map { it.change })
        assertEquals("Iluminación: Sin luces", v[0].label)
        assertTrue(v.none { it.total != null }, "aun sin valorar")
    }

    @Test
    fun `las variantes respetan el tope y nunca repiten la eleccion actual`() {
        assertEquals(1, PlannerSpecs.variantes(opcionA(), p, 1).size)
        assertTrue(PlannerSpecs.variantes(opcionA(), p, 0).isEmpty())
        assertFalse(PlannerSpecs.variantes(opcionA(), p, 8).any { it.change == mapOf("techo" to "dos_aguas") })
    }

    @Test
    fun `con paquete la eleccion vigente sale del paquete`() {
        val conPaquete = opcionA().copy(preset = "premium")
        val v = PlannerSpecs.variantes(conPaquete, p, 8)
        assertTrue(v.any { it.change == mapOf("luces" to "sin") })
        assertFalse(v.any { it.change == mapOf("luces" to "led") })
    }

    @Test
    fun `letras`() {
        assertEquals("A", PlannerSpecs.letra(0))
        assertEquals("B", PlannerSpecs.letra(1))
        assertEquals("Z", PlannerSpecs.letra(25))
    }

    @Test
    fun `un producto por m2 toma el area de sus medidas como cantidad`() {
        assertTrue(PlannerSpecs.porArea("m²"))
        assertTrue(PlannerSpecs.porArea(" M2 "))
        assertFalse(PlannerSpecs.porArea("Unidad"))
        assertFalse(PlannerSpecs.porArea(null))
        val a = PlannerSpecs.combinar(9, null, null, OptionChanges(params = mapOf("ancho" to q("4"), "profundidad" to q("3.5"))), porArea = true)
        assertEquals(0, q("14").compareTo(a.quantity))
        // Cambia solo el ancho: se recalcula con el fondo de la opcion padre.
        val b = PlannerSpecs.combinar(9, null, a, OptionChanges(params = mapOf("ancho" to q("5"))), porArea = true)
        assertEquals(0, q("17.5").compareTo(b.quantity))
        // Sin tocar las medidas se conserva; sin fondo no hay area.
        assertEquals(0, q("17.5").compareTo(PlannerSpecs.combinar(9, null, b, OptionChanges(), porArea = true).quantity))
        val sinFondo = PlannerSpecs.combinar(9, null, null, OptionChanges(params = mapOf("ancho" to q("4"))), porArea = true)
        assertEquals(0, q("1").compareTo(sinFondo.quantity))
        // Por unidad, la cantidad es la que piden aunque haya medidas.
        val porUnidad = PlannerSpecs.combinar(9, null, null, OptionChanges(params = mapOf("ancho" to q("4"), "profundidad" to q("3")), quantity = q("2")))
        assertEquals(0, q("2").compareTo(porUnidad.quantity))
    }

    @Test
    fun `la cantidad lleva su unidad si el producto tiene una propia`() {
        val area = OptionSpec(9, params = mapOf("ancho" to q("4"), "profundidad" to q("3")), quantity = q("12"))
        assertEquals(PlannerDetail("cantidad", "Área", "12 m²", PlannerDetail.QUANTITY), PlannerSpecs.detalles(area, null, null, "m²").last())
        val uno = OptionSpec(9, quantity = q("1"))
        assertTrue(PlannerSpecs.detalles(uno, null, null, "Unidad").none { it.key == "cantidad" }, "«1 Unidad» no se escribe")
        assertEquals("1 Metro lineal", PlannerSpecs.detalles(uno, null, null, "Metro lineal").last().value)
        assertEquals("Cantidad", PlannerSpecs.detalles(uno, null, null, "Metro lineal").last().label)
    }
}
