package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.channel.whatsapp.client.WhatsAppDePruebas
import io.micronaut.context.annotation.Property
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * El circuito de WhatsApp de punta a punta contra Postgres real: ingesta,
 * consumidor en proceso y envio (con la Graph API simulada).
 */
@MicronautTest(transactional = false)
// Sin ventana de rafaga: los eventos quedan disponibles en el acto.
@Property(name = "whatsapp.burst-window-seconds", value = "0")
class WhatsAppQueueConsumerTest {

    @Inject
    lateinit var entrada: WhatsAppInboundService

    @Inject
    lateinit var consumidor: WhatsAppQueueConsumer

    @Inject
    lateinit var meta: WhatsAppDePruebas

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var dataSourceResolver: DataSourceResolver

    @BeforeEach
    fun limpiar() {
        meta.limpiar()
        // Eventos de otros tests de esta clase que hayan quedado en la cola.
        ejecutar("UPDATE inbound_events SET status = 'done' WHERE status IN ('pending', 'processing')")
    }

    @Test
    fun `el entrante queda en messages al llegar, antes de tener turno`() {
        val remitente = unico()
        val wamid = "wamid.${unico()}"
        entrada.encolar(webhook(remitente, wamid to "hola"))

        assertEquals("hola", texto("SELECT body FROM messages WHERE external_id = '$wamid'"))
        assertNull(texto("SELECT turn_id::text FROM messages WHERE external_id = '$wamid'"))
    }

    @Test
    fun `el reintento de Meta no duplica el mensaje ni el evento`() {
        val remitente = unico()
        val wamid = "wamid.${unico()}"
        val cuerpo = webhook(remitente, wamid to "hola")

        entrada.encolar(cuerpo)
        entrada.encolar(cuerpo)

        assertEquals(1, contar("SELECT count(*) FROM messages WHERE external_id = '$wamid'"))
        assertEquals(1, contar("SELECT count(*) FROM inbound_events WHERE external_id = '$wamid'"))
    }

    @Test
    fun `una rafaga se atiende en un solo turno y se entrega`() {
        val remitente = unico()
        val w1 = "wamid.${unico()}"
        val w2 = "wamid.${unico()}"
        entrada.encolar(webhook(remitente, w1 to "hola", w2 to "quiero cotizar"))

        assertEquals(1, consumidor.procesar())

        // Los dos entrantes comparten el mismo turno.
        val t1 = texto("SELECT turn_id::text FROM messages WHERE external_id = '$w1'")
        val t2 = texto("SELECT turn_id::text FROM messages WHERE external_id = '$w2'")
        assertNotNull(t1)
        assertEquals(t1, t2)
        assertEquals(1, contar("SELECT count(*) FROM agent_turns WHERE id = $t1 AND status = 'done'"))

        // Se entrego al remitente por la cuenta del canal, una sola vez.
        assertEquals(1, meta.envios.size)
        assertEquals("pruebas", meta.envios.single().phoneNumberId)
        assertEquals(remitente, meta.envios.single().to)

        // El saliente quedo sellado con el wamid que devolvio Meta.
        val sellado = texto("SELECT external_id FROM messages WHERE turn_id = $t1 AND direction = 'out'")
        assertTrue(sellado!!.startsWith("wamid.salida-"))

        assertEquals(2, contar("SELECT count(*) FROM inbound_events WHERE external_id IN ('$w1', '$w2') AND status = 'done'"))
    }

    @Test
    fun `si el envio falla vuelve a la cola y el reintento no reejecuta el turno`() {
        val remitente = unico()
        val wamid = "wamid.${unico()}"
        entrada.encolar(webhook(remitente, wamid to "hola"))

        meta.fallar = true
        consumidor.procesar()
        assertEquals("pending", texto("SELECT status FROM inbound_events WHERE external_id = '$wamid'"))
        assertEquals(0, meta.envios.size)

        meta.fallar = false
        ejecutar("UPDATE inbound_events SET available_at = now() WHERE external_id = '$wamid'")
        consumidor.procesar()

        assertEquals("done", texto("SELECT status FROM inbound_events WHERE external_id = '$wamid'"))
        assertEquals(1, meta.envios.size)
        // Un solo turno y un solo saliente: el reintento contesto desde lo
        // guardado y solo reenvio.
        assertEquals(1, contar("SELECT count(*) FROM agent_turns WHERE idempotency_key = '$wamid'"))
        assertEquals(1, contar(SALIENTES.format(remitente)))
    }

    @Test
    fun `un mensaje sin texto se descarta sin abrir turno`() {
        val remitente = unico()
        val wamid = "wamid.${unico()}"
        entrada.encolar(webhook(remitente, wamid to null))

        consumidor.procesar()

        assertEquals("failed", texto("SELECT status FROM inbound_events WHERE external_id = '$wamid'"))
        assertEquals(0, contar("SELECT count(*) FROM agent_turns WHERE idempotency_key = '$wamid'"))
        assertEquals(0, meta.envios.size)
    }

    /** Cuerpo del webhook de Meta con uno o varios mensajes del mismo remitente. */
    private fun webhook(remitente: String, vararg mensajes: Pair<String, String?>): ByteArray {
        val lista = mensajes.joinToString(",") { (wamid, cuerpo) ->
            if (cuerpo == null) {
                """{"id":"$wamid","from":"$remitente","timestamp":"1","type":"image","image":{"id":"m1"}}"""
            } else {
                """{"id":"$wamid","from":"$remitente","timestamp":"1","type":"text","text":{"body":"$cuerpo"}}"""
            }
        }
        return """
            {"entry":[{"changes":[{"value":{
              "metadata":{"display_phone_number":"50200000000","phone_number_id":"pruebas"},
              "contacts":[{"wa_id":"$remitente","profile":{"name":"Cliente"}}],
              "messages":[$lista]
            }}]}]}
        """.trimIndent().toByteArray()
    }

    private fun unico() = UUID.randomUUID().toString()

    private fun contar(sql: String): Int = consulta(sql) { it.getInt(1) } ?: 0

    private fun texto(sql: String): String? = consulta(sql) { it.getString(1) }

    private fun <T> consulta(sql: String, leer: (java.sql.ResultSet) -> T): T? =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st ->
                val rs = st.executeQuery(sql)
                if (rs.next()) leer(rs) else null
            }
        }

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private companion object {
        const val SALIENTES = """
            SELECT count(*) FROM messages m JOIN conversations c ON c.id = m.conversation_id
            WHERE c.external_ref = '%s' AND m.direction = 'out'
        """
    }
}
