package com.erp_maya.agent.summary.service

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.prompt.domain.SessionState
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/** El resumen se reescribe a tiempo y nunca tumba el turno. */
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

    @Test
    fun `no se reescribe antes de que toque`() {
        val estado = SessionState(turn = 3)
        assertEquals(estado, resumidor.refrescarSiHaceFalta(1, estado))
        assertNull(resumidor.refrescarSiHaceFalta(1, SessionState(turn = 0)).summary)
    }

    @Test
    fun `al llegar al umbral se reescribe y sube la version`() {
        val remitente = unico()
        // Seis turnos: el septimo entra con turn = 6 y dispara la reescritura.
        repeat(ConversationSummarizer.CADA) { service.handle(peticion(remitente, unico()), caller) }

        val antes = estadoDe(remitente)
        assertEquals(ConversationSummarizer.CADA, antes.turn)
        assertNull(antes.summary)

        service.handle(peticion(remitente, unico()), caller)

        val despues = estadoDe(remitente)
        assertNotNull(despues.summary)
        assertEquals(1, despues.summaryVersion)
        // El proveedor simulado devuelve el nombre del modelo ligero: confirma
        // que resumio el agente 'resumen' y no el de ventas.
        assertTrue(despues.summary!!.contains("gpt-4o-mini"))
    }

    @Test
    fun `se reescribe mientras los mensajes viejos siguen en la ventana`() {
        // CADA tiene que ser menor que la ventana; si no, cuando toque resumir
        // los mensajes mas viejos ya se habrian perdido del prompt.
        assertTrue(
            ConversationSummarizer.CADA < com.erp_maya.agent.prompt.service.PromptBuilder.VENTANA,
            "resumir cada ${ConversationSummarizer.CADA} turnos con ventana de " +
                "${com.erp_maya.agent.prompt.service.PromptBuilder.VENTANA} pierde mensajes",
        )
    }

    @Test
    fun `el resumen entra al prompt del turno siguiente`() {
        val remitente = unico()
        repeat(ConversationSummarizer.CADA + 1) { service.handle(peticion(remitente, unico()), caller) }
        val resumen = estadoDe(remitente).summary
        assertNotNull(resumen)

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

    private fun unico() = UUID.randomUUID().toString()

    private fun <T> consulta(sql: String, leer: (java.sql.ResultSet) -> T): T? =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                if (rs.next()) leer(rs) else null
            }
        }
}
