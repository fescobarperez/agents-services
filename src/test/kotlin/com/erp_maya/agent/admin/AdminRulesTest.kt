package com.erp_maya.agent.admin

import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.tools.domain.ToolDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Las mismas reglas que `src/lib/ai.js` de la consola. */
class AdminRulesTest {

    private val modelos = listOf(
        AdminRules.ModelInfo(1, active = true, supportsTools = true, generatesImages = false),
        AdminRules.ModelInfo(2, active = true, supportsTools = false, generatesImages = false),
        AdminRules.ModelInfo(3, active = true, supportsTools = false, generatesImages = true),
        AdminRules.ModelInfo(4, active = false, supportsTools = true, generatesImages = false),
    )

    private fun agente(
        modelId: Long? = 1, fallback: Long? = null, imagen: Long? = null,
        temp: String = "0.2", loops: Int = 5, ms: Int = 30_000, tools: List<ToolGrantView> = emptyList(),
    ) = AdminRules.AgentDraft(modelId, fallback, imagen, BigDecimal(temp), loops, ms, tools)

    private val unaTool = listOf(ToolGrantView("productos.search", "read", true, null))

    @Test
    fun `un agente valido no tiene errores`() {
        assertTrue(AdminRules.validateAgent(agente(fallback = 2, imagen = 3, tools = unaTool), modelos).isEmpty())
    }

    @Test
    fun `el modelo debe estar activo y usar herramientas si el agente las tiene`() {
        assertEquals("Elige un modelo activo", AdminRules.validateAgent(agente(modelId = 4), modelos)["modelId"])
        assertEquals("Ese modelo no usa herramientas", AdminRules.validateAgent(agente(modelId = 2, tools = unaTool), modelos)["modelId"])
        assertNull(AdminRules.validateAgent(agente(modelId = 2), modelos)["modelId"])
    }

    @Test
    fun `respaldo distinto e imagenes con un modelo que las edite`() {
        assertEquals("El respaldo debe ser otro modelo", AdminRules.validateAgent(agente(fallback = 1), modelos)["fallbackModelId"])
        assertEquals("Elige un modelo activo que edite imágenes", AdminRules.validateAgent(agente(imagen = 2), modelos)["imageModelId"])
    }

    @Test
    fun `rangos de temperatura, vueltas y tiempo`() {
        val f = AdminRules.validateAgent(agente(temp = "1.5", loops = 11, ms = 1_000), modelos)
        assertEquals(setOf("temperature", "maxToolLoops", "responseTimeoutMs"), f.keys)
    }

    @Test
    fun `modelo nuevo`() {
        val ok = AdminRules.ModelDraft("gpt-4o", 128_000, BigDecimal("0.0025"), null, BigDecimal("0.01"), false, null)
        assertTrue(AdminRules.validateModel(ok).isEmpty())
        val mal = AdminRules.ModelDraft(" ", 500, BigDecimal("-1"), BigDecimal("-1"), null, true, null)
        assertEquals(setOf("modelName", "contextWindow", "inputCost1k", "cachedCost1k", "outputCost1k", "imageCost"), AdminRules.validateModel(mal).keys)
    }

    @Test
    fun `herramientas contra el catalogo del codigo`() {
        val catalogo = listOf(
            ToolDefinition("productos.search", ToolMode.READ, "Busca"),
            ToolDefinition("cotizaciones.preview", ToolMode.READ, "Vista previa"),
            ToolDefinition("cotizaciones.issue", ToolMode.WRITE, "Emite"),
        )
        val (grants, errores) = AdminRules.resolveTools(
            listOf(
                mapOf("pattern" to "productos.search", "maxAmount" to 100, "mode" to "write"),
                mapOf("pattern" to "cotizaciones.*", "autoApprove" to false, "maxAmount" to 25000),
            ),
            catalogo,
        )
        assertTrue(errores.isEmpty())
        // El modo lo dicta el codigo y las de lectura no llevan tope.
        assertEquals(ToolGrantView("productos.search", "read", true, null), grants[0])
        assertEquals(ToolGrantView("cotizaciones.*", "write", false, BigDecimal(25000)), grants[1])

        val (_, malas) = AdminRules.resolveTools(listOf(mapOf("pattern" to "inventada.borrar")), catalogo)
        assertEquals("Herramienta desconocida: inventada.borrar", malas["tools"])
    }

    @Test
    fun `proveedor - la llave nunca, solo el nombre de la variable`() {
        val (ok, sinErrores) = AdminRules.validateProvider("OpenAI", "https://api.openai.com/v1/", "bearer", "OPENAI_API_KEY")
        assertTrue(sinErrores.isEmpty())
        assertEquals("https://api.openai.com/v1", ok?.baseUrl)

        val (_, f) = AdminRules.validateProvider("", "ftp://x", "basic", "sk-proj-123abc")
        assertEquals(setOf("name", "baseUrl", "authType", "credentialRef"), f.keys)
    }

    @Test
    fun `codigo de tipo de agente`() {
        assertTrue(AdminRules.CODE.matches("ventas_web"))
        assertTrue(!AdminRules.CODE.matches("Ventas"))
        assertTrue(!AdminRules.CODE.matches("_tino"))
    }

    @Test
    fun `lectura tolerante de numeros`() {
        assertEquals(5, Parse.int(5.0))
        assertNull(Parse.int(2.5))
        assertEquals(0, BigDecimal("0.0001").compareTo(Parse.decimal(1.0E-4)))
        assertNull(Parse.decimal("abc"))
    }
}
