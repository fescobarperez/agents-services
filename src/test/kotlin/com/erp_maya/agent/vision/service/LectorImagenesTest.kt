package com.erp_maya.agent.vision.service

import com.erp_maya.agent.api.dto.InputImage
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppDePruebas
import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.model.domain.ImagenParaModelo
import com.erp_maya.agent.model.domain.ModelCallException
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.ToolSchema
import com.erp_maya.agent.model.service.ModelProvider
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.playbook.domain.ImagenesConfig
import com.erp_maya.agent.playbook.domain.Playbook
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** La lectura de fotos, sin red ni base: fuente y proveedor de mentira. */
@MicronautTest(startApplication = false)
class LectorImagenesTest {

    @Inject
    lateinit var json: ObjectMapper

    /** Proveedor que responde con lo que se le programe, o falla. */
    private class Vision(var respuesta: String, var falla: Boolean = false) : ModelProvider {
        override val providerCode = "openai"
        val imagenesPorLlamada = mutableListOf<Int>()
        val modelos = mutableListOf<String>()

        override fun complete(model: ModelRef, prompt: ModelPrompt, tools: List<ToolSchema>, options: ModelOptions) =
            error("la lectura no usa el chat con herramientas")

        override fun completeWithImages(
            model: ModelRef,
            instrucciones: String,
            imagenes: List<ImagenParaModelo>,
            options: ModelOptions,
        ): ModelCompletion {
            modelos += model.modelName
            imagenesPorLlamada += imagenes.size
            if (falla) throw ModelCallException("caido")
            return ModelCompletion(respuesta, ModelUsage(500, 0, 40))
        }
    }

    private class Fuente(var falla: Boolean = false) : ImageSource {
        override val source = InputImage.SOURCE_WHATSAPP
        val pedidas = mutableListOf<String>()
        override fun descargar(ref: String, maxBytes: Long): ByteArray {
            pedidas += ref
            if (falla) error("Meta no la entrego")
            return WhatsAppDePruebas.PNG_DE_PRUEBA
        }
    }

    private val pergola = """
        {"fotos":[{"relevante":true,"producto":"Pergola","descripcion":"Pergola de madera sobre deck",
          "material":"pino tratado","acabado":"barniz caoba",
          "medidas_estimadas":{"alto":2.6,"ancho":6.5,"profundidad":3.5,"unidad":"m"},
          "referencia_escala":"una persona de pie","confianza":"media"}]}
    """.trimIndent()

    private val conFotos = Playbook(imagenes = ImagenesConfig(aceptar = true, maxPorTurno = 3))

    @Test
    fun `lee la foto y la deja como texto con medidas sin confirmar`() {
        val vision = Vision(pergola)
        val r = lector(vision).leer(contexto(), conFotos, fotos(1), "la quiero asi")

        assertEquals(listOf(1), vision.imagenesPorLlamada)
        assertNotNull(r.corrida)
        val texto = r.lecturas.single().texto
        assertTrue(texto.startsWith(LectorImagenes.MARCA_FOTO), texto)
        assertTrue(texto.contains("Pergola"), texto)
        assertTrue(texto.contains("Medidas estimadas (sin confirmar): alto 2.6 m, ancho 6.5 m, profundidad 3.5 m"), texto)
        assertEquals("wamid.0", r.lecturas.single().messageId)
        assertTrue(r.notaParaModelo.contains("confirmalas con el cliente"), r.notaParaModelo)
    }

    @Test
    fun `con el playbook apagado no baja ni lee nada`() {
        val vision = Vision(pergola)
        val fuente = Fuente()
        val r = lector(vision, fuente).leer(contexto(), Playbook(), fotos(2), null)

        assertTrue(vision.imagenesPorLlamada.isEmpty())
        assertTrue(fuente.pedidas.isEmpty())
        assertNull(r.corrida)
        assertEquals(2, r.lecturas.size)
        assertTrue(r.notaParaModelo.contains("no recibe fotos"), r.notaParaModelo)
    }

    @Test
    fun `si ningun modelo ve imagenes no se llama al proveedor`() {
        val vision = Vision(pergola)
        val r = lector(vision).leer(contexto(vision = false), conFotos, fotos(1), null)

        assertTrue(vision.imagenesPorLlamada.isEmpty())
        assertTrue(r.notaParaModelo.contains("no puedes verlas"), r.notaParaModelo)
    }

    @Test
    fun `si solo el respaldo ve imagenes, lee con el respaldo`() {
        val vision = Vision(pergola)
        lector(vision).leer(contexto(vision = false, respaldoVe = true), conFotos, fotos(1), null)
        assertEquals(listOf("gpt-4o-mini"), vision.modelos)
    }

    @Test
    fun `de mas de tres fotos lee tres y avisa de las demas`() {
        val vision = Vision("""{"fotos":[{},{},{}]}""")
        val r = lector(vision).leer(contexto(), conFotos, fotos(5), null)

        assertEquals(listOf(3), vision.imagenesPorLlamada)
        assertEquals(5, r.lecturas.size)
        assertTrue(r.lecturas.last().texto.contains("pasa del maximo"), r.lecturas.last().texto)
        assertTrue(r.notaParaModelo.contains("2 foto(s) mas que no se leyeron"), r.notaParaModelo)
    }

    @Test
    fun `un documento personal no se transcribe`() {
        val vision = Vision("""{"fotos":[{"documento_personal":true,"descripcion":"DPI de Juan Perez 1234"}]}""")
        val texto = lector(vision).leer(contexto(), conFotos, fotos(1), null).lecturas.single().texto
        assertTrue(texto.contains("Documento personal"), texto)
        assertFalse(texto.contains("1234"), texto)
    }

    @Test
    fun `si el proveedor falla el turno sigue con una nota`() {
        val vision = Vision(pergola, falla = true)
        val r = lector(vision).leer(contexto(), conFotos, fotos(1), null)
        assertNull(r.corrida)
        assertTrue(r.notaParaModelo.contains("no se pudieron leer"), r.notaParaModelo)
    }

    @Test
    fun `si Meta no entrega la foto no se llama al modelo`() {
        val vision = Vision(pergola)
        val r = lector(vision, Fuente(falla = true)).leer(contexto(), conFotos, fotos(1), null)
        assertTrue(vision.imagenesPorLlamada.isEmpty())
        assertTrue(r.lecturas.single().texto.contains("no se pudo abrir"))
    }

    @Test
    fun `si el modelo no respeta el JSON se usa su texto`() {
        val vision = Vision("Es una pergola de madera de unos 3 por 4 metros")
        val texto = lector(vision).leer(contexto(), conFotos, fotos(1), null).lecturas.single().texto
        assertTrue(texto.contains("3 por 4 metros"), texto)
    }

    private fun lector(vision: Vision, fuente: Fuente = Fuente()) =
        LectorImagenes(ModelRouter(listOf(vision)), listOf(fuente), ImagenService(), json)

    private fun fotos(n: Int) = (0 until n).map { InputImage(InputImage.SOURCE_WHATSAPP, "media-$it", "image/png", "wamid.$it") }

    private fun contexto(vision: Boolean = true, respaldoVe: Boolean = false) = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true, supportsVision = vision),
        fallbackModel = ModelRef(2, "openai", "gpt-4o-mini", 128000, true, true, supportsVision = respaldoVe),
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
    )
}
