package com.erp_maya.agent.model.service

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.model.domain.ModelCallException
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.model.domain.ToolSchema
import com.erp_maya.agent.model.domain.UnknownProviderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** El respaldo del modelo, sin red ni base de datos. */
class ModelRouterTest {

    /** Proveedor de mentira: responde, o falla, segun se le pida. */
    private class Falso(
        override val providerCode: String,
        private val fallaCon: Exception? = null,
    ) : ModelProvider {
        val llamadas = mutableListOf<String>()

        override fun complete(
            model: ModelRef,
            prompt: ModelPrompt,
            tools: List<ToolSchema>,
            options: ModelOptions,
        ): ModelCompletion {
            llamadas += model.modelName
            fallaCon?.let { throw it }
            return ModelCompletion("respondio ${model.modelName}", ModelUsage(10, 4, 5))
        }
    }

    private val prompt = ModelPrompt(listOf(PromptMessage(Role.USER, "hola")))

    @Test
    fun `usa el modelo principal cuando responde`() {
        val proveedor = Falso("openai")
        val salida = ModelRouter(listOf(proveedor)).complete(contexto(), prompt)

        assertFalse(salida.fallbackUsed)
        assertEquals("gpt-4o", salida.model.modelName)
        assertEquals(listOf("gpt-4o"), proveedor.llamadas)
        assertEquals(10, salida.completion.usage.input)
        assertEquals(4, salida.completion.usage.cached)
    }

    @Test
    fun `cae al respaldo cuando el principal falla y lo deja marcado`() {
        // Un solo proveedor que siempre falla no sirve para distinguir: se usan
        // dos codigos distintos, uno roto y otro sano.
        val roto = Falso("openai", ModelCallException("timeout"))
        val sano = Falso("otro")

        val salida = ModelRouter(listOf(roto, sano)).complete(
            contexto(respaldo = ModelRef(2, "otro", "modelo-respaldo", 8000, false, false)),
            prompt,
        )

        assertTrue(salida.fallbackUsed)
        assertEquals("modelo-respaldo", salida.model.modelName)
        // Un solo reintento, y contra el respaldo: repetir contra el caido solo
        // suma latencia mientras el usuario espera.
        assertEquals(listOf("gpt-4o"), roto.llamadas)
        assertEquals(listOf("modelo-respaldo"), sano.llamadas)
    }

    @Test
    fun `sin respaldo el fallo se propaga`() {
        val roto = Falso("openai", ModelCallException("cuota agotada"))
        val e = assertThrows(ModelCallException::class.java) {
            ModelRouter(listOf(roto)).complete(contexto(respaldo = null), prompt)
        }
        assertEquals("cuota agotada", e.message)
    }

    @Test
    fun `un proveedor sin implementacion se reporta claro`() {
        val e = assertThrows(UnknownProviderException::class.java) {
            ModelRouter(listOf(Falso("openai"))).complete(
                contexto(principal = ModelRef(9, "proveedor-inventado", "x", 1000, false, false), respaldo = null),
                prompt,
            )
        }
        assertTrue(e.message!!.contains("proveedor-inventado"))
    }

    @Test
    fun `si el respaldo tambien falla se propaga el fallo del respaldo`() {
        val roto = Falso("openai", ModelCallException("principal caido"))
        val tambienRoto = Falso("otro", ModelCallException("respaldo caido"))

        val e = assertThrows(ModelCallException::class.java) {
            ModelRouter(listOf(roto, tambienRoto)).complete(
                contexto(respaldo = ModelRef(2, "otro", "modelo-respaldo", 8000, false, false)),
                prompt,
            )
        }
        assertEquals("respaldo caido", e.message)
    }

    private fun contexto(
        principal: ModelRef = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        respaldo: ModelRef? = ModelRef(2, "openai", "gpt-4o-mini", 128000, false, true),
    ) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = principal,
        fallbackModel = respaldo,
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
    )
}
