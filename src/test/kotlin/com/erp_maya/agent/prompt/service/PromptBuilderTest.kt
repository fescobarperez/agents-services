package com.erp_maya.agent.prompt.service

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.context.domain.ToolGrant
import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.conversation.domain.Direction
import com.erp_maya.agent.conversation.domain.StoredMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.prompt.domain.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** El prompt tiene que tener tamaño acotado y orden estable. */
class PromptBuilderTest {

    private val builder = PromptBuilder()

    @Test
    fun `la ventana acota el prompt por muy larga que sea la conversacion`() {
        val historia = (1..200).map {
            StoredMessage(it.toLong(), if (it % 2 == 0) Direction.OUT else Direction.IN, "mensaje $it")
        }

        val prompt = builder.build(contexto(), SessionState(), historia, "el nuevo")

        // system + 8 de ventana + el entrante. Doscientos mensajes no lo mueven.
        val conversacionales = prompt.messages.filter { it.role != Role.SYSTEM }
        assertEquals(PromptBuilder.VENTANA + 1, conversacionales.size)

        // Y son los ULTIMOS ocho, no los primeros.
        assertTrue(conversacionales.any { it.content == "mensaje 200" })
        assertFalse(conversacionales.any { it.content == "mensaje 1" })
    }

    @Test
    fun `crecer la historia no cambia el tamaño del prompt`() {
        val corta = (1..10).map { StoredMessage(it.toLong(), Direction.IN, "m$it") }
        val larga = (1..500).map { StoredMessage(it.toLong(), Direction.IN, "m$it") }

        val a = builder.build(contexto(), SessionState(), corta, "x")
        val b = builder.build(contexto(), SessionState(), larga, "x")
        assertEquals(a.messages.size, b.messages.size)
    }

    @Test
    fun `el orden va de lo mas estable a lo mas volatil`() {
        val estado = SessionState(summary = "el cliente pidio cemento", entities = mapOf("cliente_id" to "4821"))
        val prompt = builder.build(
            contexto(conHerramientas = true), estado,
            listOf(StoredMessage(1, Direction.IN, "hola")), "y el precio?",
        )

        val textos = prompt.messages.map { it.content.orEmpty() }
        val idx = { s: String -> textos.indexOfFirst { it.contains(s) } }

        // system < herramientas < resumen < entidades < mensajes: ese orden es
        // lo que permite al proveedor cachear el prefijo entre turnos.
        assertTrue(idx("asistente de ventas") < idx("Herramientas disponibles"))
        assertTrue(idx("Herramientas disponibles") < idx("Resumen de la conversacion"))
        assertTrue(idx("Resumen de la conversacion") < idx("Datos confirmados"))
        assertTrue(idx("Datos confirmados") < idx("hola"))
        assertEquals(textos.size - 1, idx("y el precio?"))
    }

    @Test
    fun `los datos duros van en entidades y no dependen del resumen`() {
        val estado = SessionState(summary = "hablamos de varias cosas", entities = mapOf("nit" to "1234567-8"))
        val prompt = builder.build(contexto(), estado, emptyList(), "confirmo")
        assertTrue(prompt.messages.any { it.content.orEmpty().contains("nit: 1234567-8") })
    }

    @Test
    fun `un canal sin markdown lo dice en el system`() {
        val prompt = builder.build(contexto(), SessionState(), emptyList(), "hola")
        val sistema = prompt.messages.first { it.role == Role.SYSTEM }.content.orEmpty()
        assertTrue(sistema.contains("no interpreta markdown"))
        assertTrue(sistema.contains("hasta 3 opciones"))
    }

    @Test
    fun `los mensajes entrantes son del usuario y los salientes del asistente`() {
        val prompt = builder.build(
            contexto(), SessionState(),
            listOf(
                StoredMessage(1, Direction.IN, "pregunta"),
                StoredMessage(2, Direction.OUT, "respuesta"),
            ),
            "otra",
        )
        assertEquals(Role.USER, prompt.messages.first { it.content == "pregunta" }.role)
        assertEquals(Role.ASSISTANT, prompt.messages.first { it.content == "respuesta" }.role)
    }

    @Test
    fun `sin herramientas no se agrega el bloque`() {
        val prompt = builder.build(contexto(conHerramientas = false), SessionState(), emptyList(), "hola")
        assertFalse(prompt.messages.any { it.content.orEmpty().contains("Herramientas disponibles") })
    }

    private fun contexto(conHerramientas: Boolean = false) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities(buttons = 3, markdown = false, maxChars = 4096)),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "Eres el asistente de ventas de la empresa."),
        tools = if (conHerramientas) {
            listOf(ToolGrant("productos.search", ToolMode.READ, true, null))
        } else {
            emptyList()
        },
        policy = TenantPolicy(1, true, BigDecimal("25000"), null, "cercano", "es-GT"),
    )
}
