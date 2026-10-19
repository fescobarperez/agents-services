package com.erp_maya.agent.quote

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.playbook.domain.CotizacionConfig
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.playbook.domain.ProductosNuevos
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.QuoteDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Ficha de diseño y productos propuestos, sin red ni base. */
class DisenoServiceTest {

    private val erp = ErpDePruebas()
    private val servicio = DisenoService(erp)

    private val pergola = Dimensions(d("2.5"), d("3"), d("4.5"))

    @Test
    fun `medidas de una foto quedan sin confirmar y las del cliente confirmadas`() {
        val deFoto = servicio.actualizar(null, "Pergola", null, null, null, pergola, "foto", null).borrador!!.diseno!!
        assertFalse(deFoto.medidasConfirmadas)
        assertTrue(deFoto.medidasSinConfirmar())

        val delCliente = servicio.actualizar(null, "Pergola", null, null, null, pergola, "cliente", null).borrador!!.diseno!!
        assertTrue(delCliente.medidasConfirmadas)
    }

    @Test
    fun `sin origen las medidas nuevas se tratan como de foto`() {
        val f = servicio.actualizar(null, null, null, null, null, pergola, null, null).borrador!!.diseno!!
        assertEquals(FichaDiseno.ORIGEN_FOTO, f.origenMedidas)
        assertFalse(f.medidasConfirmadas)
    }

    @Test
    fun `cambiar las medidas vuelve a pedir confirmacion; lo demas se conserva`() {
        val confirmada = conFicha(pergola, confirmadas = true, material = "pino")
        val otra = Dimensions(d("2.5"), d("3"), d("5"))
        val f = servicio.actualizar(confirmada, null, null, null, "caoba", otra, "foto", null).borrador!!.diseno!!
        assertFalse(f.medidasConfirmadas)
        assertEquals("pino", f.material)
        assertEquals("caoba", f.acabado)
    }

    @Test
    fun `confirmar medidas exige que el cliente haya dicho que si`() {
        val b = conFicha(pergola, confirmadas = false)
        assertThrows(IllegalArgumentException::class.java) { servicio.confirmarMedidas(b, confirmado = false) }
        assertTrue(servicio.confirmarMedidas(b, confirmado = true).borrador!!.diseno!!.medidasConfirmadas)
    }

    @Test
    fun `no se agregan a la cotizacion las medidas de la foto sin confirmar`() {
        val b = conFicha(pergola, confirmadas = false)
        assertThrows(IllegalArgumentException::class.java) { DisenoService.validarAgregar(b, pergola) }
        // Otras medidas (las que el cliente dijo) si pasan, y sin medidas tambien.
        DisenoService.validarAgregar(b, Dimensions(d("2"), d("3"), d("4")))
        DisenoService.validarAgregar(b, null)
        DisenoService.validarAgregar(conFicha(pergola, confirmadas = true), pergola)
    }

    @Test
    fun `detalles se suman y un valor vacio los borra`() {
        val m = DisenoService.mezclar(mapOf("deck" to "si"), "aguas=1; Cubierta: teja; deck=")
        assertEquals(mapOf("aguas" to "1", "cubierta" to "teja"), m)
    }

    @Test
    fun `producto nuevo solo si el playbook lo permite y en sus lineas`() {
        val b = conFicha(pergola, confirmadas = true)
        assertThrows(IllegalArgumentException::class.java) {
            servicio.productoNuevo(contexto(), 1, b, Playbook(), "Pergola a 1 agua", "Pergolas")
        }
        val conLineas = playbook(ProductosNuevos(permitido = true, lineas = listOf("Pérgolas", "Decks")))
        assertThrows(IllegalArgumentException::class.java) {
            servicio.productoNuevo(contexto(), 1, b, conLineas, "Lampara", "Iluminacion")
        }
        assertTrue(erp.propuestas.isEmpty())

        val paso = servicio.productoNuevo(contexto(), 1, b, conLineas, "Pergola a 1 agua", "pergolas")
        assertEquals("pendiente_revision", paso.datos["estado"])
        val propuesta = erp.propuestas.single()
        assertEquals(0, d("4.5").compareTo(propuesta.depth))
        assertTrue(propuesta.notes!!.contains("Pergola"))
        val f = paso.borrador!!.diseno!!
        assertTrue(f.productoPendiente)
        assertEquals(paso.datos["productId"], f.productoId)
    }

    @Test
    fun `no se propone un producto con medidas sin confirmar`() {
        val conLineas = playbook(ProductosNuevos(permitido = true))
        assertThrows(IllegalArgumentException::class.java) {
            servicio.productoNuevo(contexto(), 1, conFicha(pergola, confirmadas = false), conLineas, "Pergola", "Pergolas")
        }
        assertTrue(erp.propuestas.isEmpty())
    }

    @Test
    fun `sin lineas configuradas vale cualquier categoria`() {
        assertTrue(DisenoService.enLineas("Lo que sea", emptyList()))
        assertTrue(DisenoService.enLineas("PERGOLAS", listOf("Pérgolas")))
        assertFalse(DisenoService.enLineas("Sillas", listOf("Pérgolas")))
    }

    @Test
    fun `cerrar la cotizacion olvida la ficha`() {
        assertNull(conFicha(pergola, confirmadas = true).cerrar("enviada").diseno)
    }

    private fun conFicha(m: Dimensions, confirmadas: Boolean, material: String? = null) = QuoteDraft(
        diseno = FichaDiseno(tipo = "Pergola", material = material, medidas = m,
            origenMedidas = FichaDiseno.ORIGEN_FOTO, medidasConfirmadas = confirmadas),
    )

    private fun playbook(p: ProductosNuevos) = Playbook(cotizacion = CotizacionConfig(productosNuevos = p))

    private fun contexto() = ExecutionContext(
        tenantId = 1,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities()),
        agent = AgentRef(1, "ventas", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "..."),
        tools = emptyList(),
        policy = TenantPolicy(1, true, null, null, null, "es-GT"),
    )

    private fun d(v: String) = BigDecimal(v)
}
