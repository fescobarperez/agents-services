package com.erp_maya.agent.summary.service

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.prompt.service.PromptBuilder
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/** El resumen se escribe por inactividad o, en conversaciones largas, antes de perder mensajes. */
@MicronautTest(transactional = false)
class ConversationSummarizerTest {

    @Inject
    lateinit var service: AgentTurnService

    @Inject
    lateinit var resumidor: ConversationSummarizer

    @Inject
    lateinit var json: ObjectMapper

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var dataSourceResolver: DataSourceResolver

    private val caller = CallerCredentials("pruebas", null, setOf("productos.read"))

    @Inject
    lateinit var job: InactivitySummaryJob

    @Test
    fun `con pocos mensajes pendientes el turno no resume`() {
        val remitente = unico()
        repeat(2) { service.handle(peticion(remitente, unico()), caller) }

        val estado = estadoDe(remitente)
        assertNull(estado.summary)
        assertEquals(0L, estado.summarizedThrough)
    }

    @Test
    fun `el umbral de respaldo cabe en la ventana del prompt`() {
        // Con un pendiente menos que el umbral, todo lo que no es de este turno
        // todavia esta dentro de la ventana: nada se cae sin resumir.
        assertTrue(ConversationSummarizer.UMBRAL_RESPALDO - 1 <= PromptBuilder.VENTANA)
    }

    @Test
    fun `el respaldo resume antes de que un mensaje salga de la ventana`() {
        val remitente = unico()
        // Cada turno deja un entrante y un saliente. Al abrir el turno k hay
        // 2(k-1)+1 pendientes: el quinto es el primero que llega al umbral.
        val turnosAntes = (ConversationSummarizer.UMBRAL_RESPALDO - 1) / 2
        repeat(turnosAntes) { service.handle(peticion(remitente, unico()), caller) }
        assertNull(estadoDe(remitente).summary)

        service.handle(peticion(remitente, unico()), caller)

        val despues = estadoDe(remitente)
        assertNotNull(despues.summary)
        assertEquals(1, despues.summaryVersion)
        assertTrue(despues.summarizedThrough > 0)
        // El proveedor simulado devuelve el nombre del modelo ligero: confirma
        // que resumio el agente 'resumen' y no el de ventas.
        assertTrue(despues.summary!!.contains("gpt-4o-mini"))
    }

    @Test
    fun `la inactividad resume una conversacion corta sin pisar el resto del estado`() {
        val remitente = unico()
        repeat(2) { service.handle(peticion(remitente, unico()), caller) }
        val antes = estadoDe(remitente)
        assertNull(antes.summary)

        assertTrue(resumidor.resumirPorInactividad(idDe(remitente)))

        val despues = estadoDe(remitente)
        assertNotNull(despues.summary)
        assertEquals(1, despues.summaryVersion)
        assertEquals(ultimoMensaje(remitente), despues.summarizedThrough)
        // Merge, no reescritura: el contador del turno sigue intacto.
        assertEquals(antes.turn, despues.turn)

        // Sin mensajes nuevos no hay nada que resumir.
        assertFalse(resumidor.resumirPorInactividad(idDe(remitente)))
    }

    @Test
    fun `el job solo toma conversaciones quietas`() {
        val quieta = unico()
        val activa = unico()
        service.handle(peticion(quieta, unico()), caller)
        service.handle(peticion(activa, unico()), caller)
        ejecutar("UPDATE conversations SET updated_at = now() - interval '30 minutes' WHERE external_ref = '$quieta'")

        job.ejecutar()

        assertNotNull(estadoDe(quieta).summary)
        assertNull(estadoDe(activa).summary)
    }

    @Test
    fun `el resumen entra al prompt del turno siguiente`() {
        val remitente = unico()
        repeat((ConversationSummarizer.UMBRAL_RESPALDO + 1) / 2) { service.handle(peticion(remitente, unico()), caller) }
        assertNotNull(estadoDe(remitente).summary)

        // El siguiente turno lo ve reflejado en la respuesta que devuelve el
        // canal, via summary_version.
        val res = service.handle(peticion(remitente, unico()), caller)
        assertEquals(1, res.state?.summaryVersion)
    }

    private fun peticion(remitente: String, idem: String) = TurnRequest(
        channel = "whatsapp",
        conversationRef = ConversationRef(externalId = remitente, account = "wa:pruebas"),
        actor = null,
        input = TurnInput(type = "text", text = "mensaje ${idem.take(6)}"),
        capabilities = null,
        scope = null,
        idempotencyKey = idem,
    )

    private fun estadoDe(remitente: String): SessionState {
        val crudo = consulta(
            "SELECT state::text FROM conversations WHERE external_ref = '$remitente'",
        ) { it.getString(1) } ?: "{}"
        return requireNotNull(json.readValue(crudo, SessionState::class.java))
    }

    private fun idDe(remitente: String): Long =
        consulta("SELECT id FROM conversations WHERE external_ref = '$remitente'") { it.getLong(1) }!!

    private fun ultimoMensaje(remitente: String): Long = consulta(
        "SELECT max(m.id) FROM messages m JOIN conversations c ON c.id = m.conversation_id " +
            "WHERE c.external_ref = '$remitente'",
    ) { it.getLong(1) }!!

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private fun unico() = UUID.randomUUID().toString()

    private fun <T> consulta(sql: String, leer: (java.sql.ResultSet) -> T): T? =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                if (rs.next()) leer(rs) else null
            }
        }
}
