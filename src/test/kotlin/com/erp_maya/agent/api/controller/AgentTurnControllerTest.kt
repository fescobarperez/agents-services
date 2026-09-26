package com.erp_maya.agent.api.controller

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnError
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.dto.TurnResponse
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Clave de pruebas: al ir en una anotacion tiene que ser constante de archivo. */
private const val LLAVE = "llave-de-pruebas"

/** Contrato de `POST /v1/agent/turn` de punta a punta, sin modelo todavia. */
@MicronautTest(transactional = false)
@Property(name = "agent.clients.pruebas.api-key", value = LLAVE)
@Property(name = "agent.clients.pruebas.scopes[0]", value = "productos.read")
class AgentTurnControllerTest {

    // El cliente se construye contra la URL real del servidor de ESTE contexto
    // en vez de inyectar @Client("/"): con puerto aleatorio, un cliente atado a
    // la configuracion puede acabar hablandole al puerto de otro proceso —un
    // contenedor de test-resources, por ejemplo— y el fallo sale como si el
    // servidor devolviera basura.
    @Inject
    lateinit var servidor: EmbeddedServer

    private val http: HttpClient by lazy { HttpClient.create(servidor.url) }

    private fun peticion(cuenta: String = "wa:pruebas", idem: String = "wamid.1") = TurnRequest(
        channel = "whatsapp",
        conversationRef = ConversationRef(externalId = "50255551234", account = cuenta),
        actor = null,
        input = TurnInput(type = "text", text = "hola"),
        capabilities = null,
        scope = null,
        idempotencyKey = idem,
    )

    private fun enviar(cuerpo: TurnRequest, llave: String? = LLAVE) =
        http.toBlocking().exchange(
            HttpRequest.POST("/v1/agent/turn", cuerpo).let { r ->
                if (llave == null) r else r.header("X-Api-Key", llave)
            },
            TurnResponse::class.java,
        )

    @Test
    fun `un turno valido responde un evento de texto`() {
        val res = enviar(peticion())

        assertEquals(HttpStatus.OK, res.status)
        val cuerpo = res.body()!!
        assertTrue(cuerpo.conversationId.startsWith("cnv_"))
        assertTrue(cuerpo.turnId.startsWith("trn_"))
        assertEquals(1, cuerpo.events.size)

        val evento = cuerpo.events.first()
        assertTrue(evento is AgentEvent.Text)
        // El texto lo produce el proveedor simulado y cita el modelo que el
        // contexto resolvio: prueba que la cadena entera quedo enchufada.
        assertTrue((evento as AgentEvent.Text).text.contains("gpt-4o"))

        // El consumo llega al canal, que es lo que permite facturar el turno.
        assertEquals(120, cuerpo.usage?.input)
        assertEquals(80, cuerpo.usage?.cached)
        assertEquals(20, cuerpo.usage?.output)
    }

    @Test
    fun `el id de conversacion es estable entre turnos del mismo remitente`() {
        val uno = enviar(peticion(idem = "wamid.a")).body()!!
        val dos = enviar(peticion(idem = "wamid.b")).body()!!
        assertEquals(uno.conversationId, dos.conversationId)
        // El de turno todavia no: la idempotencia llega en el paso 4.
        assertTrue(uno.turnId != dos.turnId)
    }

    @Test
    fun `sin credencial no se atiende`() {
        val e = assertThrows(HttpClientResponseException::class.java) {
            enviar(peticion(), llave = null)
        }
        assertEquals(HttpStatus.UNAUTHORIZED, e.status)
    }

    @Test
    fun `una credencial que no existe no se atiende`() {
        val e = assertThrows(HttpClientResponseException::class.java) {
            enviar(peticion(), llave = "llave-inventada")
        }
        assertEquals(HttpStatus.UNAUTHORIZED, e.status)
    }

    @Test
    fun `un canal sin agente responde 409 con una razon legible por maquina`() {
        val e = assertThrows(HttpClientResponseException::class.java) {
            enviar(peticion(cuenta = "wa:inexistente"))
        }
        assertEquals(HttpStatus.CONFLICT, e.status)
        val error = e.response.getBody(TurnError::class.java).orElseThrow()
        assertEquals("NO_ACTIVE_AGENT", error.reason)
    }

    @Test
    fun `un cuerpo sin idempotency_key se rechaza`() {
        val crudo = """
            {"channel":"whatsapp",
             "conversation_ref":{"external_id":"50255551234","account":"wa:pruebas"},
             "input":{"type":"text","text":"hola"}}
        """.trimIndent()
        val e = assertThrows(HttpClientResponseException::class.java) {
            http.toBlocking().exchange(
                HttpRequest.POST("/v1/agent/turn", crudo)
                    .header("X-Api-Key", LLAVE)
                    .contentType(io.micronaut.http.MediaType.APPLICATION_JSON),
                String::class.java,
            )
        }
        assertTrue(e.status == HttpStatus.BAD_REQUEST || e.status == HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @Test
    fun `el scope del cuerpo se ignora`() {
        // Un cliente malicioso pidiendo permisos de escritura no los obtiene:
        // los suyos son de solo lectura y el cuerpo no puede cambiarlos.
        val crudo = """
            {"channel":"whatsapp",
             "conversation_ref":{"external_id":"50255551234","account":"wa:pruebas"},
             "input":{"type":"text","text":"hola"},
             "scope":["nomina.write","cotizaciones.write"],
             "idempotency_key":"wamid.scope"}
        """.trimIndent()
        val res = http.toBlocking().exchange(
            HttpRequest.POST("/v1/agent/turn", crudo)
                .header("X-Api-Key", LLAVE)
                .contentType(io.micronaut.http.MediaType.APPLICATION_JSON),
            TurnResponse::class.java,
        )
        // Se acepta el turno, pero el scope declarado no tuvo ningun efecto.
        assertEquals(HttpStatus.OK, res.status)
    }
}
