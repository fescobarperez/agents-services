package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.planner.PlannerFixtures.pergola
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Lo que el agente del planificador ve en cada turno y como se leen sus respuestas rapidas. */
class PlannerPromptTest {

    private val w = PlannerWidget(
        channelId = 1, tenantId = 7, publicKey = "pk_x", active = true, allowedOrigins = emptyList(), name = "MV",
        title = null, accentColor = null, lang = "es", locale = "es-GT",
        projectTypes = listOf(ProjectType("pergola", "Pérgola", 501), ProjectType("deck", "Deck", 502)),
        fitThresholdPct = q("90"), retentionDays = 7, maxOptions = 10,
    )

    private fun sesion(budget: String?, activa: String? = null, notas: Map<String, String> = emptyMap()) =
        PlannerSessionRow("ps_x", 1, 7, budget?.let { q(it) }, "pergola", notas, activa, Instant.now(), Instant.now())

    private val variantes = listOf(
        PlannerVariant(mapOf("luces" to "sin"), "Iluminación: Sin luces", q("21300.00")),
        PlannerVariant(mapOf("cubierta" to "policarbonato"), "Cubierta: Policarbonato", q("19900.00")),
    )

    private fun opcion(letra: String, total: String?, padre: String? = null) = PlannerOptionRow(
        letra, padre, "pergola", "Pérgola · Dos aguas, Teja", "Luces LED · 4 × 3 × 2.4 m",
        OptionSpec(501, 77, mapOf("ancho" to q("4")), mapOf("techo" to "dos_aguas", "cubierta" to "teja")),
        listOf(PlannerDetail("medidas", "Medidas", "4 × 3 × 2.4 m", PlannerDetail.SIZE), PlannerDetail("techo", "Techo", "Dos aguas")),
        if (total == null) "review" else "priced", total?.let { q(it) }, null, null, variantes, q("20000"),
    )

    @Test
    fun `el agente ve el presupuesto, las opciones con su semaforo y los codes`() {
        val t = PlannerPrompt.texto(w, sesion("20000", "B", mapOf("uso" to "Patio trasero")),
            listOf(opcion("A", "16400.00"), opcion("B", "24800.00", "A")), pergola(), "GTQ")
        assertTrue(t.contains("Presupuesto: Q 20,000"), t)
        assertTrue(t.contains("Pérgola (pergola)"), t)
        assertTrue(t.contains("Notas: uso=Patio trasero"), t)
        assertTrue(t.contains("activa: B"), t)
        assertTrue(t.contains("A · Pérgola · Dos aguas, Teja — Q 16,400 · encaja, sobran Q 3,600"), t)
        assertTrue(t.contains("B · Pérgola · Dos aguas, Teja (desde A) — Q 24,800 · se pasa por Q 4,800 · sugerencia: Cubierta: Policarbonato = Q 19,900"), t)
        assertTrue(t.contains("codes: ancho=4, techo=dos_aguas, cubierta=teja"), t)
        assertTrue(t.contains("techo (Techo, obligatoria): una_agua=Una agua [por defecto], dos_aguas=Dos aguas"), t)
        assertTrue(t.contains("ancho (Ancho, m, min 1, max 8, por defecto 4)"), t)
        assertTrue(t.contains("Paquetes: premium=Premium"), t)
        assertTrue(t.contains("planificador.opcion"), "explica como usar las herramientas")
    }

    @Test
    fun `sin presupuesto ni opciones lo dice y no inventa semaforo`() {
        val t = PlannerPrompt.texto(w, sesion(null), emptyList(), null, "GTQ")
        assertTrue(t.contains("Presupuesto: aun no lo indica"), t)
        assertTrue(t.contains("Opciones: ninguna todavia."), t)
        assertFalse(t.contains("Plantilla del tipo activo"), t)
    }

    @Test
    fun `un precio por confirmar se dice asi`() {
        val l = PlannerPrompt.linea(opcion("A", null), q("20000"), q("90"), "GTQ")
        assertTrue(l.contains("— por confirmar · precio por confirmar (lo confirma un asesor)"), l)
    }

    @Test
    fun `montos con simbolo y centavos solo si los hay`() {
        assertEquals("Q 16,400", PlannerPrompt.dinero(q("16400.00"), "GTQ"))
        assertEquals("Q 1,250.50", PlannerPrompt.dinero(q("1250.5"), "GTQ"))
        assertEquals("USD 99", PlannerPrompt.dinero(q("99"), "USD"))
    }

    @Test
    fun `el monto que dice el modelo se lee con la coma de miles`() {
        assertEquals(q("25000"), PlannerTools.monto("Q 25,000"))
        assertEquals(q("25000"), PlannerTools.monto(25000))
        assertEquals(q("1250.50"), PlannerTools.monto("1,250.50"))
        assertNull(PlannerTools.monto("mucho"))
    }

    @Test
    fun `medidas y elecciones del modelo se leen como pares`() {
        assertEquals(mapOf("ancho" to "4", "profundidad" to "3,5"), PlannerTools.pares("Ancho=4; profundidad = 3,5"))
        assertEquals(mapOf("techo" to "dos_aguas"), PlannerTools.pares("techo=dos_aguas;;=x;luces="))
        assertTrue(PlannerTools.pares(null).isEmpty())
    }

    @Test
    fun `las respuestas rapidas se leen igual en memoria que releidas del JSON`() {
        val enMemoria = mapOf("items" to listOf(
            mapOf("label" to "Teja", "text" to null, "change" to mapOf("cubierta" to "teja")),
            mapOf("label" to "Sí, para el patio", "text" to "Sí, para el patio", "change" to null),
            mapOf("label" to "", "text" to "x"),
            "basura",
        ))
        val r = PlannerTools.respuestas(enMemoria)
        assertEquals(2, r.size)
        assertEquals(mapOf("cubierta" to "teja"), r[0].change)
        assertNull(r[1].change)
        assertEquals("Sí, para el patio", r[1].text)
        assertTrue(PlannerTools.respuestas(emptyMap()).isEmpty())
    }

    @Test
    fun `con un producto de la lista el agente ve su ficha, su unidad y las categorias`() {
        val producto = ProjectType("p501", "Pérgola Corredor Colonial", 501, "m²",
            "Corredor techado con vigas de madera.\n\nMateriales: Pino tratado 6×6, teja")
        val t = PlannerPrompt.texto(w.copy(projectTypes = emptyList(), categoryIds = listOf(21)),
            sesion("15000").copy(projectType = "p501"), emptyList(), null, "GTQ",
            tipoActivo = producto, categorias = listOf(PlannerCategoryView(21, "Pérgolas y Terrazas")))
        assertTrue(t.contains("Proyecto elegido: Pérgola Corredor Colonial (p501), se vende por m²"), t)
        assertTrue(t.contains("Categorias (elige producto de la lista o con planificador.productos): Pérgolas y Terrazas (21)"), t)
        assertTrue(t.contains("De que se trata (ficha del producto): Corredor techado con vigas de madera. Materiales: Pino tratado 6×6, teja"), t)
        assertTrue(t.contains("Si el producto se vende por m², pide el largo y el fondo"), t)
        assertFalse(t.contains("Tipos fijos"), t)
    }
}
