package com.erp_maya.agent.cita

import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.channel.whatsapp.client.plantilla
import com.erp_maya.agent.channel.whatsapp.domain.WaChange
import com.erp_maya.agent.channel.whatsapp.domain.WaEntry
import com.erp_maya.agent.channel.whatsapp.domain.WaMessage
import com.erp_maya.agent.channel.whatsapp.domain.WaMetadata
import com.erp_maya.agent.channel.whatsapp.domain.WaTemplateButton
import com.erp_maya.agent.channel.whatsapp.domain.WaValue
import com.erp_maya.agent.channel.whatsapp.domain.WhatsAppWebhook
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppParser
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppQueueConsumer
import com.erp_maya.agent.playbook.domain.PlantillaMeta
import com.erp_maya.agent.playbook.domain.PlantillaRecordatorio
import com.erp_maya.agent.quote.PanelActionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** El recordatorio que manda el ERP: texto, plantilla de Meta y el camino de vuelta de sus botones. */
class RecordatorioTest {

    private val payload = RecordatorioPayload(
        appointmentId = 41, patientId = 15, patientName = "Ana María López", phone = "5555-1234",
        companyName = "Clínica Sonrisas", professionalName = "Dra. Ruiz", typeName = "Limpieza",
        startsAt = "2026-11-03T15:30:00Z", date = "martes 3 de noviembre", time = "09:30",
    )

    @Test
    fun `el texto por defecto saluda por el primer nombre y dice cuando y con quien`() {
        val texto = CitaRules.llenar(PlantillaRecordatorio().texto, RecordatorioService.valores(payload))
        assertEquals(
            "Hola Ana, te recordamos tu cita de Limpieza en Clínica Sonrisas el martes 3 de noviembre a las 09:30 " +
                "con Dra. Ruiz. ¿Nos confirmas tu asistencia?",
            texto,
        )
    }

    @Test
    fun `los botones llevan la accion y la cita`() {
        val botones = CitaService.botonesRecordatorio(41, PlantillaRecordatorio())
        assertEquals(listOf("cita:confirmar:41", "cita:cambiar:41", "cita:cancelar:41"), botones.map { it.id })
        assertEquals(listOf("Confirmar", "Cambiar fecha", "Cancelar cita"), botones.map { it.label })
    }

    @Test
    fun `sin plantilla aprobada no hay como escribir fuera de la ventana`() {
        assertNull(RecordatorioService.plantilla(PlantillaRecordatorio(), RecordatorioService.valores(payload), 41))
    }

    @Test
    fun `la plantilla de Meta lleva las variables en orden y los payloads de sus botones`() {
        val m = PlantillaRecordatorio(plantilla = PlantillaMeta("recordatorio_cita", "es", listOf("paciente", "fecha", "hora", "profesional")))
        val t = RecordatorioService.plantilla(m, RecordatorioService.valores(payload), 41)!!
        assertEquals(listOf("Ana", "martes 3 de noviembre", "09:30", "Dra. Ruiz"), t.variables)
        assertEquals(listOf("cita:confirmar:41", "cita:cambiar:41", "cita:cancelar:41"), t.respuestas)

        val envio = plantilla(t)
        assertEquals("recordatorio_cita", envio.name)
        assertEquals("es", envio.language.code)
        val componentes = envio.components!!
        assertEquals(listOf("body", "button", "button", "button"), componentes.map { it.type })
        assertEquals("Ana", componentes[0].parameters[0].text)
        assertEquals("quick_reply", componentes[2].subType)
        assertEquals("1", componentes[2].index)
        assertEquals("cita:cambiar:41", componentes[2].parameters[0].payload)
    }

    @Test
    fun `una variable vacia viaja como guion porque Meta la rechaza`() {
        val envio = plantilla(WhatsAppOutbound.Template("x", "es", listOf("Ana", "")))
        assertEquals(listOf("Ana", "-"), envio.components!!.single().parameters.map { it.text })
    }

    @Test
    fun `la respuesta rapida de una plantilla llega como el id del boton`() {
        val webhook = WhatsAppWebhook(
            listOf(WaEntry(listOf(WaChange(WaValue(
                metadata = WaMetadata(phoneNumberId = "123"),
                messages = listOf(WaMessage(id = "wamid.1", from = "50255551234", type = "button",
                    button = WaTemplateButton(payload = "cita:confirmar:41", text = "Confirmar"))),
            ))))),
        )
        val m = WhatsAppParser().extraer(webhook).single()
        assertEquals("cita:confirmar:41", m.texto)
    }

    @Test
    fun `el boton del recordatorio se vuelve accion de cita y el de cotizacion sigue igual`() {
        val cita: TurnInput = WhatsAppQueueConsumer.entrada(listOf("cita:cancelar:41"), "cita:cancelar:41")
        assertEquals("action", cita.type)
        assertEquals(PanelActionService.ACCION_CITA, cita.actionId)
        assertEquals("cita:cancelar:41", cita.payload?.get("boton"))

        val cot = WhatsAppQueueConsumer.entrada(listOf("cot:aprobar:42:3"), "cot:aprobar:42:3")
        assertEquals(PanelActionService.ACCION_DECISION, cot.actionId)
    }
}
