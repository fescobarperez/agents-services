package com.erp_maya.agent.cita

import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppQueueConsumer
import com.erp_maya.agent.playbook.domain.PlantillaControl
import com.erp_maya.agent.playbook.domain.PlantillaMeta
import com.erp_maya.agent.quote.PanelActionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El recordatorio de control: texto sin datos clinicos, botones y el camino de vuelta de la respuesta. */
class ControlTest {

    private val payload = ControlPayload(
        followUpId = 9, patientId = 15, patientName = "Ana María López", phone = "5555-1234",
        companyName = "Clínica Central", professionalId = 21, professionalName = "Dra. Ruiz", typeId = 3,
        typeName = "Control", dueOn = "2026-11-13", date = "viernes 13 de noviembre",
    )

    @Test
    fun `el texto pregunta como sigue sin decir de que es el control`() {
        val texto = CitaRules.llenar(PlantillaControl().texto, ControlService.valores(payload))
        assertEquals(
            "Hola Ana, se acerca tu control con Dra. Ruiz (antes del viernes 13 de noviembre). ¿Cómo te has sentido?",
            texto,
        )
    }

    @Test
    fun `los botones llevan la respuesta y el control`() {
        val botones = ControlService.botones(9, PlantillaControl())
        assertEquals(listOf("ctl:mejor:9", "ctl:igual:9", "ctl:peor:9"), botones.map { it.id })
        assertEquals(listOf("Mejor", "Igual", "Peor"), botones.map { it.label })
    }

    @Test
    fun `solo reconoce sus tres respuestas`() {
        assertEquals("peor" to 9L, ControlRules.leerBoton(" ctl:peor:9 "))
        assertNull(ControlRules.leerBoton("ctl:regular:9"))
        assertFalse(ControlRules.esBoton("cita:confirmar:9"))
        assertTrue(ControlRules.esBoton("ctl:mejor:12"))
    }

    @Test
    fun `fuera de la ventana sale la plantilla con sus respuestas rapidas`() {
        assertNull(ControlService.plantilla(PlantillaControl(), ControlService.valores(payload), 9))
        val m = PlantillaControl(plantilla = PlantillaMeta("recordatorio_control", "es", listOf("paciente", "profesional", "fecha")))
        val t = ControlService.plantilla(m, ControlService.valores(payload), 9)!!
        assertEquals(listOf("Ana", "Dra. Ruiz", "viernes 13 de noviembre"), t.variables)
        assertEquals(listOf("ctl:mejor:9", "ctl:igual:9", "ctl:peor:9"), t.respuestas)
    }

    @Test
    fun `el boton se vuelve accion de control`() {
        val entrada: TurnInput = WhatsAppQueueConsumer.entrada(listOf("ctl:igual:9"), "ctl:igual:9")
        assertEquals("action", entrada.type)
        assertEquals(PanelActionService.ACCION_CONTROL, entrada.actionId)
        assertEquals("ctl:igual:9", entrada.payload?.get("boton"))
    }

    @Test
    fun `sin nombre no quedan comas sueltas`() {
        val texto = CitaRules.llenar(PlantillaControl().mejor, mapOf("paciente" to "", "profesional" to "Dra. Ruiz"))
        assertEquals(
            "¡Qué bueno! Igual conviene que Dra. Ruiz te revise. ¿Qué día te queda bien para tu control? " +
                "Te digo qué horarios hay libres.",
            ControlService.limpiar(texto),
        )
    }
}
