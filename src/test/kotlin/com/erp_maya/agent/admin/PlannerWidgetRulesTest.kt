package com.erp_maya.agent.admin

import com.erp_maya.agent.channel.planner.PlannerService
import com.erp_maya.agent.channel.planner.ProjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Lo que la consola puede guardar de un planificador. */
class PlannerWidgetRulesTest {

    private val tipos = listOf(mapOf("code" to "pergola", "label" to "Pérgola", "productId" to 501))
    private val valido = mapOf(
        "name" to "Planificador Madera Viva",
        "allowedOrigins" to listOf("https://www.maderaviva.com"),
        "projectTypes" to tipos,
    )

    private fun errores(cambios: Map<String, Any?>) = PlannerWidgetRules.validar(valido + cambios, null).second

    @Test
    fun `uno nuevo con lo minimo toma los valores por defecto`() {
        val (d, f) = PlannerWidgetRules.validar(valido, null)
        assertTrue(f.isEmpty(), f.toString())
        d!!
        assertEquals(listOf(ProjectType("pergola", "Pérgola", 501)), d.projectTypes)
        assertEquals(BigDecimal("90"), d.fitThresholdPct)
        assertEquals(7, d.retentionDays)
        assertEquals(10, d.maxOptions)
        assertEquals("es", d.lang)
    }

    @Test
    fun `uno nuevo necesita nombre, dominio y al menos un tipo`() {
        val (d, f) = PlannerWidgetRules.validar(emptyMap(), null)
        assertNull(d)
        assertEquals(setOf("name", "allowedOrigins", "categoryIds"), f.keys)
        assertNotNull(errores(mapOf("allowedOrigins" to listOf("http://maderaviva.com")))["allowedOrigins"])
        assertEquals("Elige al menos una categoría", errores(mapOf("projectTypes" to emptyList<Any>()))["categoryIds"])
    }

    @Test
    fun `tipos de proyecto con codigo valido, unico y producto`() {
        assertNotNull(errores(mapOf("projectTypes" to listOf(mapOf("code" to "Pérgola", "label" to "P", "productId" to 1))))["projectTypes"])
        assertNotNull(errores(mapOf("projectTypes" to listOf(mapOf("code" to "deck", "label" to "Deck"))))["projectTypes"])
        assertNotNull(errores(mapOf("projectTypes" to listOf(mapOf("code" to "deck", "label" to "", "productId" to 1))))["projectTypes"])
        assertEquals(
            "El código «deck» está repetido",
            errores(mapOf("projectTypes" to listOf(
                mapOf("code" to "deck", "label" to "Deck", "productId" to 1),
                mapOf("code" to "deck", "label" to "Deck 2", "productId" to 2),
            )))["projectTypes"],
        )
        val muchos = (1..13).map { mapOf("code" to "t$it", "label" to "T$it", "productId" to it) }
        assertNotNull(errores(mapOf("projectTypes" to muchos))["projectTypes"])
    }

    @Test
    fun `umbral y limites en sus rangos`() {
        assertNotNull(errores(mapOf("fitThresholdPct" to 49))["fitThresholdPct"])
        assertNotNull(errores(mapOf("fitThresholdPct" to 101))["fitThresholdPct"])
        assertNotNull(errores(mapOf("fitThresholdPct" to "85.555"))["fitThresholdPct"])
        assertTrue(errores(mapOf("fitThresholdPct" to "85.5")).isEmpty())
        assertNotNull(errores(mapOf("retentionDays" to 0))["retentionDays"])
        assertNotNull(errores(mapOf("maxOptions" to 27))["maxOptions"])
        assertNotNull(errores(mapOf("maxOptions" to 2.5))["maxOptions"])
        assertNotNull(errores(mapOf("accentColor" to "cafe"))["accentColor"])
        assertNotNull(errores(mapOf("lang" to "fr"))["lang"])
    }

    @Test
    fun `al editar se conserva lo que no viene`() {
        val (actual, _) = PlannerWidgetRules.validar(valido + mapOf("fitThresholdPct" to 85, "accentColor" to "#8b6549"), null)
        assertEquals("#8B6549", actual!!.accentColor)
        val (d, f) = PlannerWidgetRules.validar(mapOf("maxOptions" to 6), actual)
        assertTrue(f.isEmpty())
        assertEquals(6, d!!.maxOptions)
        assertEquals(BigDecimal("85"), d.fitThresholdPct)
        assertEquals(actual.projectTypes, d.projectTypes)
        assertEquals("#8B6549", d.accentColor)
    }

    @Test
    fun `el WhatsApp del asesor queda en digitos con codigo de pais`() {
        val (d, f) = PlannerWidgetRules.validar(valido + mapOf("contactWhatsapp" to "+502 1234-5678"), null)
        assertTrue(f.isEmpty(), f.toString())
        assertEquals("50212345678", d!!.contactWhatsapp)
        assertNotNull(errores(mapOf("contactWhatsapp" to "llámame"))["contactWhatsapp"])
        assertNotNull(errores(mapOf("contactWhatsapp" to "1234"))["contactWhatsapp"])
        // Vacio lo quita (sin boton de contacto).
        val (sin, _) = PlannerWidgetRules.validar(mapOf("contactWhatsapp" to ""), d)
        assertNull(sin!!.contactWhatsapp)
        assertNull(PlannerWidgetRules.validar(valido, null).first!!.contactWhatsapp)
    }

    @Test
    fun `la llave tiene el formato que acepta la entrada publica`() {
        val llave = PlannerWidgetRules.nuevaLlave()
        assertTrue(PlannerService.LLAVE.matches(llave), llave)
        assertTrue(llave.startsWith("pk_"))
    }

    @Test
    fun `basta con categorias del ERP, sin repetir y con tope`() {
        val soloCategorias = mapOf("name" to "Por categorías", "allowedOrigins" to listOf("https://www.maderaviva.com"), "categoryIds" to listOf(21, "22"))
        val (d, f) = PlannerWidgetRules.validar(soloCategorias, null)
        assertTrue(f.isEmpty(), f.toString())
        assertEquals(listOf(21L, 22L), d!!.categoryIds)
        assertTrue(d.projectTypes.isEmpty())
        assertNotNull(errores(mapOf("categoryIds" to listOf(21, 21)))["categoryIds"])
        assertNotNull(errores(mapOf("categoryIds" to listOf(0)))["categoryIds"])
        assertNotNull(errores(mapOf("categoryIds" to "21"))["categoryIds"])
        assertNotNull(errores(mapOf("categoryIds" to (1..PlannerWidgetRules.MAX_CATEGORIAS + 1).toList()))["categoryIds"])
        // Al editar se conservan si no vienen; y se pueden quitar los tipos si quedan categorias.
        val (sinTipos, f2) = PlannerWidgetRules.validar(mapOf("projectTypes" to emptyList<Any>()), d.copy(projectTypes = listOf(ProjectType("deck", "Deck", 1))))
        assertTrue(f2.isEmpty(), f2.toString())
        assertEquals(listOf(21L, 22L), sinTipos!!.categoryIds)
    }
}
