package com.erp_maya.agent.quote

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppQueueConsumer
import com.erp_maya.agent.playbook.domain.PlantillaDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Botones de decision del cliente: formato del id, conversion a accion y plantillas. */
class QuoteDecisionButtonsTest {

    @Test
    fun `el id lleva accion, cotizacion y version`() {
        val id = QuoteDecisionService.id(QuoteDecisionService.APROBAR, 42, 3)
        assertEquals("cot:aprobar:42:3", id)
        assertTrue(QuoteDecisionService.esBoton(id))
        assertTrue(QuoteDecisionService.esBoton("cot:confirmar_rechazar:7:1"))
        // WhatsApp limita el id a 256 y el titulo a 20: el id es corto.
        assertTrue(id.length < 256)
    }

    @Test
    fun `un texto del cliente no es un boton`() {
        assertFalse(QuoteDecisionService.esBoton("si apruebo la cotizacion"))
        assertFalse(QuoteDecisionService.esBoton("cot:aprobar:42"))
        assertFalse(QuoteDecisionService.esBoton("cot:aprobar:x:1"))
        assertFalse(QuoteDecisionService.esBoton(null))
    }

    @Test
    fun `el boton se convierte en accion y el texto sigue siendo texto`() {
        val accion = WhatsAppQueueConsumer.entrada(listOf("cot:aprobar:42:3"), "cot:aprobar:42:3")
        assertEquals("action", accion.type)
        assertEquals(PanelActionService.ACCION_DECISION, accion.actionId)
        assertEquals("cot:aprobar:42:3", accion.payload?.get("boton"))

        val texto = WhatsAppQueueConsumer.entrada(listOf("hola"), "hola")
        assertEquals("text", texto.type)
        assertEquals("hola", texto.text)
    }

    @Test
    fun `las plantillas se llenan sin huecos`() {
        val t = PlantillaDecision()
        assertEquals(
            "¿Confirmas que apruebas la cotización COT-0012 por Q 1,250.00?",
            QuoteDecisionService.llenar(t.confirmarAprobar, "Ana López", "COT-0012", "Q 1,250.00", 2),
        )
        val gracias = QuoteDecisionService.llenar(t.aprobada, "Ana López", "COT-0012", null, 2)
        assertTrue(gracias.startsWith("¡Gracias por preferirnos, Ana!"), gracias)
        val sinNombre = QuoteDecisionService.llenar(t.aprobada, null, "COT-0012", null, 2)
        assertTrue(sinNombre.startsWith("¡Gracias por preferirnos!"), sinNombre)
        assertTrue(QuoteDecisionService.llenar(t.versionAnterior, null, "COT-0012", null, 3).contains("versión 3"))
    }

    @Test
    fun `los titulos caben en WhatsApp`() {
        listOf("✅ Aprobar", "💬 Comentarios", "❌ Rechazar", "Sí, apruebo", "Sí, rechazar", "No")
            .forEach { assertTrue(it.length <= 20, it) }
    }
}
