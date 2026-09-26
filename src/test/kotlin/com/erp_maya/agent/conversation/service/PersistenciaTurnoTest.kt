package com.erp_maya.agent.conversation.service

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.dto.TurnResponse
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

/** Paso 4: conversacion, mensajes e idempotencia contra Postgres real. */
@MicronautTest(transactional = false)
class PersistenciaTurnoTest {

    @Inject
    lateinit var service: AgentTurnService

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var dataSourceResolver: DataSourceResolver

    private val caller = CallerCredentials("pruebas", null, setOf("productos.read"))

    private fun peticion(remitente: String, idem: String) = TurnRequest(
        channel = "whatsapp",
        conversationRef = ConversationRef(externalId = remitente, account = "wa:pruebas"),
        actor = null,
        input = TurnInput(type = "text", text = "hola"),
        capabilities = null,
        scope = null,
        idempotencyKey = idem,
    )

    @Test
    fun `dos turnos del mismo remitente comparten conversacion`() {
        val remitente = unico()
        val uno = service.handle(peticion(remitente, unico()), caller)
        val dos = service.handle(peticion(remitente, unico()), caller)

        assertEquals(uno.conversationId, dos.conversationId)
        assertNotEquals(uno.turnId, dos.turnId)
        assertEquals(1, contar("SELECT count(*) FROM conversations WHERE external_ref = '$remitente'"))
    }

    @Test
    fun `la misma llave devuelve el mismo turno y no ejecuta dos veces`() {
        val remitente = unico()
        val llave = unico()

        val primera = service.handle(peticion(remitente, llave), caller)
        val segunda = service.handle(peticion(remitente, llave), caller)

        assertEquals(primera.turnId, segunda.turnId)
        assertEquals(primera.events, segunda.events)

        // Un solo turno, y un solo par de mensajes: la segunda llamada se
        // contesto desde lo guardado sin volver a ejecutar nada.
        assertEquals(1, contar("SELECT count(*) FROM agent_turns WHERE idempotency_key = '$llave'"))
        assertEquals(2, contar(MENSAJES.format(remitente)))
    }

    @Test
    fun `el turno queda registrado con su respuesta y su estado`() {
        val llave = unico()
        val res = service.handle(peticion(unico(), llave), caller)

        assertEquals("done", texto("SELECT status FROM agent_turns WHERE idempotency_key = '$llave'"))
        assertTrue(texto("SELECT completed_at::text FROM agent_turns WHERE idempotency_key = '$llave'").isNotBlank())

        val guardada = texto("SELECT response::text FROM agent_turns WHERE idempotency_key = '$llave'")
        assertTrue(guardada.contains(res.turnId))
    }

    @Test
    fun `se guardan el mensaje entrante y el saliente`() {
        val remitente = unico()
        val llave = unico()
        service.handle(peticion(remitente, llave), caller)

        assertEquals(1, contar(MENSAJES.format(remitente) + " AND m.direction = 'in'"))
        assertEquals(1, contar(MENSAJES.format(remitente) + " AND m.direction = 'out'"))
        // El entrante conserva el id del canal; el saliente aun no existe alla.
        assertEquals(llave, texto(MENSAJE_EXT.format(remitente)))
    }

    @Test
    fun `dos llamadas simultaneas con la misma llave solo ejecutan una vez`() {
        val remitente = unico()
        val llave = unico()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tarea = Callable { runCatching { service.handle(peticion(remitente, llave), caller) } }
            val resultados = pool.invokeAll(listOf(tarea, tarea)).map { it.get() }

            // Una gana; la otra o reenvia la respuesta o avisa que esta en
            // proceso. Lo que nunca puede pasar es que se ejecuten las dos.
            val motivos = resultados.mapNotNull { it.exceptionOrNull() }
                .joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            assertTrue(resultados.any { it.isSuccess }, "ninguna llamada tuvo exito -> $motivos")
            assertEquals(1, contar("SELECT count(*) FROM agent_turns WHERE idempotency_key = '$llave'"))
            assertTrue(contar(MENSAJES.format(remitente)) <= 2)

            val exitosas: List<TurnResponse> = resultados.mapNotNull { it.getOrNull() }
            if (exitosas.size == 2) assertEquals(exitosas[0].turnId, exitosas[1].turnId)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `cada turno deja su corrida auditada con tokens y latencia`() {
        val llave = unico()
        service.handle(peticion(unico(), llave), caller)

        val fila = CORRIDA.format(llave)
        assertEquals(1, contar("SELECT count(*) FROM ($fila) x"))
        assertEquals("120", texto("SELECT tokens_in::text FROM ($fila) x"))
        assertEquals("80", texto("SELECT tokens_cached::text FROM ($fila) x"))
        assertEquals("20", texto("SELECT tokens_out::text FROM ($fila) x"))
        assertEquals("false", texto("SELECT fallback_used::text FROM ($fila) x"))
        // La corrida apunta al modelo que de verdad respondio y a la version
        // exacta del prompt: sin eso no se puede reconstruir un turno viejo.
        assertEquals("gpt-4o", texto("SELECT m.model_name FROM ($fila) x JOIN ai_models m ON m.id = x.model_id"))
        assertTrue(texto("SELECT latency_ms::text FROM ($fila) x").isNotBlank())
    }

    @Test
    fun `un turno fallido tambien queda registrado`() {
        // Canal inexistente: falla antes de resolver contexto, asi que no hay
        // turno que registrar. Lo que se comprueba es que no se cuela basura.
        val llave = unico()
        runCatching {
            service.handle(
                peticion(unico(), llave).copy(
                    conversationRef = ConversationRef(externalId = unico(), account = "wa:no-existe"),
                ),
                caller,
            )
        }
        assertEquals(0, contar("SELECT count(*) FROM agent_turns WHERE idempotency_key = '$llave'"))
    }

    private fun unico() = UUID.randomUUID().toString()

    private fun contar(sql: String): Int = consulta(sql) { it.getInt(1) } ?: 0

    private fun texto(sql: String): String = consulta(sql) { it.getString(1) } ?: ""

    private fun <T> consulta(sql: String, leer: (java.sql.ResultSet) -> T): T? =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                if (rs.next()) leer(rs) else null
            }
        }

    private companion object {
        const val CORRIDA = """
            SELECT r.* FROM agent_runs r
            JOIN agent_turns t ON t.id = r.turn_id
            WHERE t.idempotency_key = '%s'
        """

        const val MENSAJES = """
            SELECT count(*) FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.external_ref = '%s'
        """
        const val MENSAJE_EXT = """
            SELECT m.external_id FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.external_ref = '%s' AND m.direction = 'in'
        """
    }
}
