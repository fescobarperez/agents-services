package com.erp_maya.agent.quote

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.playbook.domain.ImagenesConfig
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.FotoCliente
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.render.ColaRenders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Pedir un render: reglas y lo que se le manda al modelo de imagenes. */
class RenderServiceTest {

    private class Cola : ColaRenders {
        data class Pedido(val accountRef: String, val recipient: String, val baseMediaId: String, val prompt: String, val n: Int, val max: Int)
        val pedidos = mutableListOf<Pedido>()
        override fun encolar(
            conversationId: Long, tenantId: Long, accountRef: String, recipient: String, modelId: Long,
            baseMediaId: String, prompt: String, renderNumber: Int, renderMax: Int,
        ): Long {
            pedidos += Pedido(accountRef, recipient, baseMediaId, prompt, renderNumber, renderMax)
            return pedidos.size.toLong()
        }
    }

    private val cola = Cola()
    private val servicio = RenderService(cola)

    private val conRenders = Playbook(imagenes = ImagenesConfig(aceptar = true, generarRenders = true, rendersMax = 3))
    private val fotos = listOf(
        FotoCliente("m-1", "image/jpeg", "w1", "[Foto del cliente] Casa con ventanal; zona marcada en verde frente al ventanal"),
        FotoCliente("m-2", "image/jpeg", "w2", "[Foto del cliente] Otra vista"),
    )
    private val ficha = FichaDiseno(
        tipo = "Pérgola de madera", material = "pino tratado", acabado = "barniz caoba",
        medidas = Dimensions(BigDecimal("2.5"), BigDecimal("3"), BigDecimal("4.5")),
        detalles = mapOf("aguas" to "1", "cubierta" to "teja"),
    )

    @Test
    fun `encola el render sobre la foto mas reciente y cuenta uno`() {
        val paso = servicio.solicitar(contexto(), 7, QuoteDraft(diseno = ficha), conRenders, fotos, "50255551234", null, null)
        val pedido = cola.pedidos.single()
        assertEquals("pruebas", pedido.accountRef)
        assertEquals("m-2", pedido.baseMediaId)
        assertEquals(1, pedido.n)
        assertEquals(3, pedido.max)
        assertEquals(1, paso.borrador!!.diseno!!.renders)
        assertEquals("m-2", paso.borrador!!.diseno!!.fotoRender)
    }

    @Test
    fun `los ajustes salen sobre la misma foto del render anterior`() {
        val segunda = QuoteDraft(diseno = ficha.copy(renders = 1, fotoRender = "m-1"))
        servicio.solicitar(contexto(), 7, segunda, conRenders, fotos, "502", null, "más oscura")
        assertEquals("m-1", cola.pedidos.single().baseMediaId)
        assertTrue(cola.pedidos.single().prompt.contains("más oscura"))
    }

    @Test
    fun `no pasa del limite de renders`() {
        val usados = QuoteDraft(diseno = ficha.copy(renders = 3))
        assertThrows(IllegalArgumentException::class.java) {
            servicio.solicitar(contexto(), 7, usados, conRenders, fotos, "502", null, null)
        }
        assertTrue(cola.pedidos.isEmpty())
    }

    @Test
    fun `sin playbook, sin modelo de imagenes o sin foto no se encola`() {
        val b = QuoteDraft(diseno = ficha)
        assertThrows(IllegalArgumentException::class.java) { servicio.solicitar(contexto(), 7, b, Playbook(), fotos, "502", null, null) }
        assertThrows(IllegalArgumentException::class.java) { servicio.solicitar(contexto(conModelo = false), 7, b, conRenders, fotos, "502", null, null) }
        assertThrows(IllegalArgumentException::class.java) { servicio.solicitar(contexto(), 7, b, conRenders, emptyList(), "502", null, null) }
        assertTrue(cola.pedidos.isEmpty())
    }

    @Test
    fun `pide completar la ficha antes del primer render`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            servicio.solicitar(contexto(), 7, QuoteDraft(diseno = FichaDiseno(tipo = "Pérgola")), conRenders, fotos, "502", null, null)
        }
        assertTrue(e.message!!.contains("medidas"))
    }

    @Test
    fun `las instrucciones salen solo de la ficha y prohiben decoracion`() {
        val texto = RenderService.instrucciones(ficha, fotos[0].lectura, null)
        assertTrue(texto.contains("Pérgola de madera"))
        assertTrue(texto.contains("alto 2.5 m, ancho 3 m, profundidad 4.5 m"))
        assertTrue(texto.contains("aguas: 1; cubierta: teja"))
        assertTrue(texto.contains("zona marcada en verde"))
        assertTrue(texto.contains("No agregues muebles"))
        assertFalse(texto.contains("[Foto del cliente]"))
    }

    @Test
    fun `se puede pedir una foto por numero`() {
        assertEquals("m-1", RenderService.elegirFoto(fotos, 1, null).mediaId)
        assertThrows(IllegalArgumentException::class.java) { RenderService.elegirFoto(fotos, 5, null) }
    }

    private fun contexto(conModelo: Boolean = true) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:pruebas", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
        imageModel = if (conModelo) ModelRef(5, "openai", "gpt-image-1", 0, false, false) else null,
    )
}
