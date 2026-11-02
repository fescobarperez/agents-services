package com.erp_maya.agent.cita

import com.erp_maya.agent.context.domain.AgentRef
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.context.domain.ChannelRef
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.context.domain.PromptRef
import com.erp_maya.agent.context.domain.TenantPolicy
import com.erp_maya.agent.conversation.domain.ConversationDestination
import com.erp_maya.agent.conversation.repository.AgentTurnRepository
import com.erp_maya.agent.conversation.repository.ConversationRepository
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.SaludAppointment
import com.erp_maya.agent.erp.domain.SaludAppointmentType
import com.erp_maya.agent.erp.domain.SaludAppointmentWrite
import com.erp_maya.agent.erp.domain.SaludAvailability
import com.erp_maya.agent.erp.domain.SaludCandidate
import com.erp_maya.agent.erp.domain.SaludPatientClient
import com.erp_maya.agent.erp.domain.SaludPatientCreate
import com.erp_maya.agent.erp.domain.SaludPatientDetail
import com.erp_maya.agent.erp.domain.SaludPatientFromClient
import com.erp_maya.agent.erp.domain.SaludProfessional
import com.erp_maya.agent.erp.domain.SaludRescheduleWrite
import com.erp_maya.agent.erp.domain.SaludSlot
import com.erp_maya.agent.erp.domain.SaludStatusWrite
import com.erp_maya.agent.erp.domain.StockInfo
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.playbook.repository.PlaybookRepository
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.tools.domain.ToolCall
import com.erp_maya.agent.tools.domain.ToolResult
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.serde.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Las herramientas de citas y los botones del recordatorio contra un ERP de
 * mentira: quien es el paciente, que horario se reserva y que no se toca.
 */
class CitaServiceTest {

    private val telefono = "50255551234"
    private val manana: Instant = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
    private val erp = ErpFalso()
    private val service = CitaService(erp, conversaciones(telefono), playbooks())

    private fun agendar(vararg args: Pair<String, Any?>) =
        service.ejecutar(contexto, 9, ToolCall("citas.agendar", mapOf("typeId" to 1, "professionalId" to 4) + args), telefono)

    @Test
    fun `agenda al paciente del numero en un horario libre y queda solicitada`() {
        erp.candidatos[telefono] = listOf(SaludCandidate(15, "Ana López", "5555-1234", patient = true, recordNumber = "EXP-15"))
        erp.libres = listOf(SaludSlot(manana.toString(), manana.plus(Duration.ofMinutes(30)).toString(), 3))

        val r = agendar("inicio" to manana.toString(), "motivo" to "dolor de muela")

        assertInstanceOf(ToolResult.Ok::class.java, r)
        val creada = erp.creadas.single()
        assertEquals(15L, creada.patientId)
        assertEquals(3L, creada.resourceId, "el consultorio sale de la disponibilidad, no del modelo")
        assertEquals("solicitada", creada.status)
        assertEquals("pendiente de confirmar", ((r as ToolResult.Ok).data as Map<*, *>)["estado"])
    }

    @Test
    fun `un horario que ya no esta libre no se agenda y se ofrecen los libres`() {
        erp.candidatos[telefono] = listOf(SaludCandidate(15, "Ana López", patient = true))
        val otro = manana.plus(Duration.ofHours(1))
        erp.libres = listOf(SaludSlot(otro.toString(), otro.plus(Duration.ofMinutes(30)).toString()))

        val r = agendar("inicio" to manana.toString())

        assertInstanceOf(ToolResult.Failed::class.java, r)
        assertTrue((r as ToolResult.Failed).message.contains("ya no está libre"))
        assertTrue(erp.creadas.isEmpty())
    }

    @Test
    fun `la primera vez pide el nombre y luego registra al paciente con su telefono`() {
        erp.libres = listOf(SaludSlot(manana.toString(), manana.plus(Duration.ofMinutes(30)).toString()))

        val sinNombre = agendar("inicio" to manana.toString())
        assertTrue((sinNombre as ToolResult.Failed).message.contains("nombre completo"))

        val conNombre = agendar("inicio" to manana.toString(), "nombre" to "Ana López")
        assertInstanceOf(ToolResult.Ok::class.java, conNombre)
        assertEquals("Ana López", erp.altas.single().client.name)
        assertEquals("5555-1234", erp.altas.single().client.phone)
        assertEquals("whatsapp", erp.altas.single().patient.reminderChannel)
        assertEquals(500L, erp.creadas.single().patientId)
    }

    @Test
    fun `con el numero de la mama se agenda al hijo como paciente aparte`() {
        erp.candidatos[telefono] = listOf(SaludCandidate(15, "Ana López", "5555-1234", patient = true))
        erp.libres = listOf(SaludSlot(manana.toString(), manana.plus(Duration.ofMinutes(30)).toString()))

        agendar("inicio" to manana.toString(), "nombre" to "Juan Pérez López")

        assertEquals("Juan Pérez López", erp.altas.single().client.name)
        assertEquals(500L, erp.creadas.single().patientId)
    }

    @Test
    fun `un cliente que aun no es paciente se convierte`() {
        erp.candidatos[telefono] = listOf(SaludCandidate(22, "Luis Gómez", "5555-1234", patient = false))
        erp.libres = listOf(SaludSlot(manana.toString(), manana.plus(Duration.ofMinutes(30)).toString()))

        agendar("inicio" to manana.toString())

        assertEquals(listOf(22L), erp.convertidos)
        assertEquals(22L, erp.creadas.single().patientId)
    }

    @Test
    fun `no se cancela la cita de otro numero`() {
        erp.citas[41] = cita(41, telefono = "4444-0000")
        val r = service.ejecutar(contexto, 9, ToolCall("citas.cancelar", mapOf("citaId" to 41)), telefono)
        assertTrue((r as ToolResult.Failed).message.contains("no es de quien escribe"))
        assertTrue(erp.estados.isEmpty())
    }

    @Test
    fun `mis citas trae solo las vigentes de este numero`() {
        erp.citas[1] = cita(1)
        erp.citas[2] = cita(2, status = "cancelada")
        erp.citas[3] = cita(3, telefono = "4444-0000")
        erp.citas[4] = cita(4, status = "solicitada")

        val r = service.ejecutar(contexto, 9, ToolCall("citas.mis_citas", emptyMap()), telefono) as ToolResult.Ok

        assertEquals(listOf(1L, 4L), (r.data as List<*>).map { (it as Map<*, *>)["citaId"] })
    }

    @Test
    fun `reprogramar toma el consultorio libre del nuevo horario`() {
        erp.citas[41] = cita(41)
        val nuevo = manana.plus(Duration.ofDays(1))
        erp.libres = listOf(SaludSlot(nuevo.toString(), nuevo.plus(Duration.ofMinutes(30)).toString(), 8))

        val r = service.ejecutar(contexto, 9, ToolCall("citas.reprogramar", mapOf("citaId" to 41, "inicio" to nuevo.toString())), telefono)

        assertInstanceOf(ToolResult.Ok::class.java, r)
        val (id, cambio) = erp.movidas.single()
        assertEquals(41L, id)
        assertEquals(8L, cambio.resourceId)
        assertEquals(4L, cambio.professionalId)
    }

    @Test
    fun `si el dia pedido esta lleno ofrece el siguiente con horarios`() {
        erp.tipos = listOf(SaludAppointmentType(1, "Consulta", 30))
        val dia = Instant.now().plus(2, ChronoUnit.DAYS)
        erp.libresPorDia = { fecha -> if (fecha == CitaRules.dia(dia.toString())) emptyList() else listOf(SaludSlot("${fecha}T15:00:00Z", "${fecha}T15:30:00Z")) }

        val r = service.ejecutar(
            contexto, 9,
            ToolCall("citas.disponibilidad", mapOf("typeId" to 1, "fecha" to CitaRules.dia(dia.toString()))), telefono,
        ) as ToolResult.Ok

        val datos = r.data as Map<*, *>
        assertEquals(false, datos["pedida"])
        assertEquals(CitaRules.dia(dia.plus(1, ChronoUnit.DAYS).toString()), datos["fecha"])
    }

    // ── Botones del recordatorio ─────────────────────────────────────────

    @Test
    fun `confirmar desde el boton confirma la cita`() {
        erp.citas[41] = cita(41, status = "solicitada")
        val paso = service.boton(contexto, 9, null, "cita:confirmar:41")
        assertEquals(listOf(41L to "confirmada"), erp.estados.map { it.first to it.second.status })
        assertTrue(paso.mensaje.contains("quedó confirmada"))
    }

    @Test
    fun `cancelar desde el boton primero pregunta`() {
        erp.citas[41] = cita(41)
        val paso = service.boton(contexto, 9, null, "cita:cancelar:41")
        assertTrue(erp.estados.isEmpty())
        assertEquals(listOf("cita:cancelar_si:41", "cita:mantener:41"), paso.opciones.map { it.id })

        service.boton(contexto, 9, null, "cita:cancelar_si:41")
        assertEquals("cancelada", erp.estados.single().second.status)
    }

    @Test
    fun `el boton de una cita ajena o ya atendida no hace nada`() {
        erp.citas[41] = cita(41, telefono = "4444-0000")
        erp.citas[42] = cita(42, status = "completada")
        assertEquals(Playbook().mensajes.recordatorioCita.noEsTuya, service.boton(contexto, 9, null, "cita:confirmar:41").mensaje)
        assertTrue(service.boton(contexto, 9, null, "cita:confirmar:42").mensaje.contains("ya fue atendida"))
        assertTrue(erp.estados.isEmpty())
    }

    @Test
    fun `el historial guarda el id para que el modelo pueda reprogramar`() {
        assertTrue(service.describir("cita:cambiar:41").contains("citaId 41"))
    }

    @Test
    fun `mismo telefono por los ultimos ocho digitos`() {
        assertTrue(CitaService.mismoTelefono("5555-1234", "50255551234"))
        assertTrue(!CitaService.mismoTelefono("5555-1235", "50255551234"))
        assertTrue(!CitaService.mismoTelefono(null, "50255551234"))
        assertEquals("5555-1234", CitaService.telefonoLegible("50255551234"))
        assertEquals("+13055551234", CitaService.telefonoLegible("13055551234"))
        assertTrue(CitaService.pareceElMismo("Ana María López", "ana lopez"))
        assertTrue(!CitaService.pareceElMismo("Ana María López", "Juan López"))
    }

    // ── Infraestructura de mentira ───────────────────────────────────────

    private fun cita(id: Long, telefono: String = "5555-1234", status: String = "confirmada") = SaludAppointment(
        id = id, patientId = 15, patientName = "Ana López", patientPhone = telefono,
        professionalId = 4, professionalName = "Dra. Ruiz", typeId = 1, typeName = "Consulta",
        startsAt = manana.plus(Duration.ofDays(id)).toString(), endsAt = manana.plus(Duration.ofDays(id)).plus(Duration.ofMinutes(30)).toString(),
        status = status,
    )

    private val contexto = ExecutionContext(
        tenantId = 7,
        channel = ChannelRef(1, "whatsapp", "wa:x", Capabilities(buttons = 3)),
        agent = AgentRef(1, "clinica", BigDecimal("0.2"), 5, 30000),
        model = ModelRef(1, "openai", "gpt-4o", 128000, true, true),
        fallbackModel = null,
        prompt = PromptRef(1, 1, "Eres el asistente de la clinica."),
        tools = emptyList(),
        policy = TenantPolicy(7, true, null, null, null, "es-GT"),
    )

    private inline fun <reified T> nada(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as T

    private fun conversaciones(externalRef: String) =
        object : ConversationService(ConversationRepository(nada<JdbcOperations>()), AgentTurnRepository(nada<JdbcOperations>())) {
            override fun destino(conversationId: Long) =
                ConversationDestination(conversationId, 7, "whatsapp", "wa:x", externalRef, null)
        }

    private fun playbooks() = object : PlaybookService(PlaybookRepository(nada<JdbcOperations>()), nada<ObjectMapper>()) {
        override fun para(contexto: ExecutionContext) = Playbook()
    }

    private class ErpFalso : ErpClient {
        val candidatos = mutableMapOf<String, List<SaludCandidate>>()
        val citas = sortedMapOf<Long, SaludAppointment>()
        var tipos = listOf(SaludAppointmentType(1, "Consulta", 30))
        var libres = emptyList<SaludSlot>()
        var libresPorDia: ((String) -> List<SaludSlot>)? = null
        val creadas = mutableListOf<SaludAppointmentWrite>()
        val altas = mutableListOf<SaludPatientCreate>()
        val convertidos = mutableListOf<Long>()
        val movidas = mutableListOf<Pair<Long, SaludRescheduleWrite>>()
        val estados = mutableListOf<Pair<Long, SaludStatusWrite>>()

        override fun saludTypes(tenantId: Long, conversationId: Long) = tipos
        override fun saludProfessionals(tenantId: Long, conversationId: Long) = listOf(
            SaludProfessional(4, "Dra. Ruiz", configured = true, active = true),
            SaludProfessional(9, "Recepción", configured = false, active = false),
        )
        override fun saludAvailability(tenantId: Long, conversationId: Long, professionalId: Long, date: String, typeId: Long) =
            SaludAvailability(date, professionalId, typeId, 30, libresPorDia?.invoke(date) ?: libres)
        override fun saludAppointments(tenantId: Long, conversationId: Long, patientId: Long?, from: String, to: String) =
            citas.values.toList()
        override fun saludAppointment(tenantId: Long, conversationId: Long, appointmentId: Long) = citas[appointmentId]
        override fun saludCreateAppointment(tenantId: Long, conversationId: Long, cita: SaludAppointmentWrite): SaludAppointment {
            creadas += cita
            return SaludAppointment(
                id = 100, patientId = cita.patientId, patientName = "Paciente", professionalId = cita.professionalId,
                professionalName = "Dra. Ruiz", typeId = cita.typeId, typeName = "Consulta", resourceId = cita.resourceId,
                startsAt = cita.startsAt, endsAt = cita.startsAt, status = "solicitada",
            )
        }
        override fun saludReschedule(tenantId: Long, conversationId: Long, appointmentId: Long, cambio: SaludRescheduleWrite): SaludAppointment {
            movidas += appointmentId to cambio
            return citas.getValue(appointmentId).copy(startsAt = cambio.startsAt, status = "solicitada")
        }
        override fun saludChangeStatus(tenantId: Long, conversationId: Long, appointmentId: Long, cambio: SaludStatusWrite): SaludAppointment {
            estados += appointmentId to cambio
            return citas.getValue(appointmentId).copy(status = cambio.status)
        }
        override fun saludCandidates(tenantId: Long, conversationId: Long, query: String) = candidatos[query].orEmpty()
        override fun saludCreatePatient(tenantId: Long, conversationId: Long, alta: SaludPatientCreate): SaludPatientDetail {
            altas += alta
            return SaludPatientDetail(SaludPatientClient(500, alta.client.name, alta.client.phone))
        }
        override fun saludPatientFromClient(tenantId: Long, conversationId: Long, clientId: Long, alta: SaludPatientFromClient): SaludPatientDetail {
            convertidos += clientId
            return SaludPatientDetail(SaludPatientClient(clientId, "Cliente"))
        }

        override fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int): List<ProductSummary> = TODO()
        override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo? = TODO()
        override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary? = TODO()
        override fun previewQuote(tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>): QuotePreview = TODO()
        override fun issueQuote(
            tenantId: Long, conversationId: Long, customerId: Long?, lines: List<QuoteLineRequest>,
            expectedTotal: BigDecimal, idempotencyKey: String,
        ): IssuedQuote = TODO()
        override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo? = TODO()
    }

    @Test
    fun `los tipos inactivos no se ofrecen`() {
        erp.tipos = listOf(SaludAppointmentType(1, "Consulta", 30), SaludAppointmentType(2, "Vieja", 30, active = false))
        val r = service.ejecutar(contexto, 9, ToolCall("citas.opciones", emptyMap()), telefono) as ToolResult.Ok
        val datos = r.data as Map<*, *>
        assertEquals(1, (datos["tipos"] as List<*>).size)
        assertEquals(1, (datos["profesionales"] as List<*>).size)
        assertNull((datos["tipos"] as List<*>).firstOrNull { (it as Map<*, *>)["nombre"] == "Vieja" })
    }
}
