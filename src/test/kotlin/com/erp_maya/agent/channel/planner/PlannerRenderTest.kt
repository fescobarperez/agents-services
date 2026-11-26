package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_PERGOLA
import com.erp_maya.agent.channel.planner.PlannerFixtures.pergola
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Las instrucciones del render salen solo de la opcion: lo que se ve es lo que se cotiza. */
class PlannerRenderTest {

    private val tipo = ProjectType("pergola", "Pérgola", PRODUCTO_PERGOLA)

    private fun opcion(choices: Map<String, String>, plantilla: com.erp_maya.agent.erp.domain.ProductTemplate = pergola()): PlannerOptionRow {
        val spec = OptionSpec(PRODUCTO_PERGOLA, PlannerFixtures.PLANTILLA_PERGOLA, mapOf("ancho" to q("4"), "profundidad" to q("3")), choices)
        return PlannerOptionRow("B", "A", "pergola", "B", null, spec, PlannerSpecs.detalles(spec, plantilla, null),
            "priced", q("24800.00"), null, null, emptyList(), q("20000"))
    }

    @Test
    fun `con foto edita la foto real y no toca lo que ya existe`() {
        val t = PlannerRender.instrucciones(tipo, opcion(mapOf("techo" to "dos_aguas", "cubierta" to "teja")), pergola(),
            mapOf("uso" to "Patio trasero"), conFoto = true)
        assertTrue(t.startsWith("Edita esta foto real del espacio del cliente"), t)
        assertTrue(t.contains("Qué mostrar: Pérgola."), t)
        assertTrue(t.contains("Diseño: Techo: Dos aguas; Cubierta: Teja; Iluminación: Sin luces."), t)
        assertTrue(t.contains("Medidas aproximadas (ancho × fondo × alto): 4 × 3 × 2.4 m."), t)
        assertTrue(t.contains("Contexto del cliente: uso: Patio trasero."), t)
        assertTrue(t.contains("No cambies la casa"), t)
        assertTrue(t.contains("borra las marcas"), t)
    }

    @Test
    fun `sin foto genera una escena neutra`() {
        val t = PlannerRender.instrucciones(tipo, opcion(emptyMap()), pergola(), emptyMap(), conFoto = false)
        assertTrue(t.startsWith("Genera una imagen fotorrealista del proyecto terminado"), t)
        assertFalse(t.contains("Edita esta foto"), t)
        assertFalse(t.contains("borra las marcas"), t)
        assertFalse(t.contains("Contexto del cliente"), t)
        assertTrue(t.contains("Sin textos, logotipos ni marcas de agua."), t)
    }

    @Test
    fun `cada eleccion usa el texto de render que escribio la empresa`() {
        val p = pergola().let { t ->
            t.copy(options = t.options.map { o ->
                if (o.code != "cubierta") o else o.copy(choices = o.choices.map {
                    if (it.code == "teja") it.copy(renderText = "cubierta de teja de barro rojo sobre las vigas") else it
                })
            })
        }
        val e = PlannerRender.elecciones(opcion(mapOf("cubierta" to "teja"), p), p)
        assertEquals(listOf("Techo: Una agua", "cubierta de teja de barro rojo sobre las vigas", "Iluminación: Sin luces"), e)
    }

    @Test
    fun `sin plantilla describe el tipo y las medidas`() {
        val spec = OptionSpec(502, params = mapOf("ancho" to q("5"), "profundidad" to q("4")), quantity = q("2"))
        val o = PlannerOptionRow("A", null, "deck", "Deck", null, spec, PlannerSpecs.detalles(spec, null, null),
            "priced", q("2500.00"), null, null, emptyList(), null)
        val t = PlannerRender.instrucciones(ProjectType("deck", "Deck", 502), o, null, emptyMap(), conFoto = false)
        assertTrue(t.contains("Qué mostrar: Deck."), t)
        assertTrue(t.contains("5 × 4 m"), t)
        assertTrue(t.contains("Cantidad: 2."), t)
        assertFalse(t.contains("Diseño:"), t)
    }

    @Test
    fun `un producto de la lista se dibuja con su ficha, sin beneficios ni precios`() {
        val ficha = "Corredor techado a lo largo de la casa.\n\nImagine una tarde de lluvia.\n\n" +
            "Materiales: Pino tratado 6×6, teja\nEstilo: Colonial\nPrecio de referencia: Q700–Q1,200/m²"
        val spec = OptionSpec(501, params = mapOf("ancho" to q("4"), "profundidad" to q("3")), quantity = q("12"))
        val o = PlannerOptionRow("A", null, "p501", "Pérgola Corredor Colonial", null, spec, PlannerSpecs.detalles(spec, null, null, "m²"),
            "priced", q("11400.00"), null, null, emptyList(), null)
        val t = PlannerRender.instrucciones(ProjectType("p501", "Pérgola Corredor Colonial", 501, "m²", ficha), o, null, emptyMap(), conFoto = false)
        assertTrue(t.contains("Cómo es (ficha del producto): Corredor techado a lo largo de la casa. Materiales: Pino tratado 6×6, teja Estilo: Colonial"), t)
        assertTrue(t.contains("Área: 12 m²."), t)
        assertFalse(t.contains("Imagine"), t)
        assertFalse(t.contains("Q700"), t)
    }
}
