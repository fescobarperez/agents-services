package com.erp_maya.agent.quote

import com.erp_maya.agent.playbook.domain.CotizacionConfig
import com.erp_maya.agent.playbook.domain.DatoRequerido
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.QuoteDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El checklist lo calcula el codigo: es la compuerta de `cotizacion.enviar`. */
class QuoteChecklistTest {

    private val playbook = Playbook(
        cotizacion = CotizacionConfig(
            datosRequeridos = listOf(
                DatoRequerido(DatoRequerido.CLIENTE, "Cliente", DatoRequerido.ORIGEN_SISTEMA),
                DatoRequerido(DatoRequerido.LINEAS, "Productos", DatoRequerido.ORIGEN_SISTEMA),
                DatoRequerido("direccion_entrega", "Direccion de entrega"),
                DatoRequerido("fecha_entrega", "Fecha deseada", opcional = true),
            ),
        ),
    )

    @Test
    fun `sin nada faltan cliente, productos y direccion pero no lo opcional`() {
        val faltan = QuoteChecklist.faltantes(null, playbook).map { it.clave }
        assertEquals(listOf("cliente", "lineas", "direccion_entrega"), faltan)
    }

    @Test
    fun `con cliente, cotizacion y direccion esta completo`() {
        val borrador = QuoteDraft(
            customerId = 1, customerName = "Cliente", quoteId = 9, quoteNumber = "COT-9",
            datos = mapOf("direccion_entrega" to "Zona 10"),
        )
        assertTrue(QuoteChecklist.faltantes(borrador, playbook).isEmpty())
    }

    @Test
    fun `las lineas en espera cuentan como productos`() {
        val borrador = QuoteDraft(customerId = 1, pendientes = listOf(
            com.erp_maya.agent.prompt.domain.DraftLine(5, "SKU", "Coca Cola", java.math.BigDecimal.TEN),
        ))
        assertEquals(listOf("direccion_entrega"), QuoteChecklist.faltantes(borrador, playbook).map { it.clave })
    }

    @Test
    fun `cerrar conserva al cliente y suelta la cotizacion y sus datos`() {
        val borrador = QuoteDraft(
            customerId = 1, customerName = "Cliente", quoteId = 9, quoteNumber = "COT-9",
            datos = mapOf("direccion_entrega" to "Zona 10"), telefonoRevisado = true,
        )
        val cerrada = borrador.cerrar("PDF enviado")
        assertEquals(1L, cerrada.customerId)
        assertNull(cerrada.quoteId)
        assertTrue(cerrada.datos.isEmpty())
        assertEquals("COT-9 (PDF enviado)", cerrada.ultimaCerrada)
        assertTrue(cerrada.telefonoRevisado)
    }
}
