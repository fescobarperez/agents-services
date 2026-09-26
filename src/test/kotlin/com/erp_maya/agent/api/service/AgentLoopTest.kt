package com.erp_maya.agent.api.service

import com.erp_maya.agent.api.dto.ConversationRef
import com.erp_maya.agent.api.dto.TurnInput
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.ToolInvocation
import com.erp_maya.agent.model.service.ProveedorDePruebas
import com.erp_maya.agent.prompt.domain.SessionState
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * El bucle modelo ↔ herramientas, de punta a punta.
 *
 * El caso que de verdad importa es el ultimo: que una emision sin preview
 * previo NO llegue al ERP.
 */
@MicronautTest(transactional = false)
class AgentLoopTest {

    @Inject lateinit var service: AgentTurnService
    @Inject lateinit var modelo: ProveedorDePruebas
    @Inject lateinit var erp: ErpDePruebas
    @Inject lateinit var json: ObjectMapper
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var dataSourceResolver: DataSourceResolver

    private val caller = CallerCredentials("pruebas", null, setOf("cotizaciones.write"))

    @BeforeEach
    fun limpiar() {
        modelo.limpiar()
        erp.limpiar()
    }

    @Test
    fun `una consulta de lectura ejecuta la herramienta y contesta`() {
        modelo.programar(
            pideHerramienta("productos.search", mapOf("query" to "cemento")),
            texto("Tenemos cemento gris de 42.5 kg a Q90 el saco."),
        )

        val res = service.handle(peticion(unico(), unico(), "tienen cemento?"), caller)

        assertTrue((res.events.first() as com.erp_maya.agent.api.dto.AgentEvent.Text).text.contains("Q90"))
        // La empresa viaja en cada llamada al ERP: es el error mas caro posible.
        assertEquals(listOf(1L), erp.tenantsVistos.distinct())
        assertEquals("[\"productos.search\"]", toolsDe(res.turnId))
    }

    @Test
    fun `preview, confirmacion y emision atraviesan la compuerta en ese orden`() {
        val remitente = unico()

        // Turno 1: el modelo previsualiza y le muestra el total al cliente.
        modelo.programar(
            pideHerramienta("cotizaciones.preview", lineas()),
            texto("Le quedaria en Q1,800.00. ¿Confirmo la cotizacion?"),
        )
        service.handle(peticion(remitente, unico(), "cotizame 20 sacos"), caller)

        val pendiente = estadoDe(remitente).pendingWrite
        assertNotNull(pendiente)
        assertEquals(BigDecimal("1800.00").compareTo(pendiente!!.total), 0)
        assertTrue(erp.emisiones.isEmpty(), "no debio emitir nada todavia")

        // Turno 2: el cliente confirma y ahora si se emite.
        modelo.programar(
            pideHerramienta("cotizaciones.issue", lineas() + ("total" to 1800.00)),
            texto("Listo, la cotizacion COT-2026-0418 va en camino."),
        )
        service.handle(peticion(remitente, unico(), "confirmo"), caller)

        assertEquals(1, erp.emisiones.size)
        assertEquals(0, BigDecimal("1800.00").compareTo(erp.emisiones.first().second))
        // Y el pendiente ya no queda colgando para un tercer turno.
        assertNotNull(estadoDe(remitente))
    }

    @Test
    fun `emitir sin preview previo NO llega al ERP`() {
        modelo.programar(
            pideHerramienta("cotizaciones.issue", lineas() + ("total" to 1800.00)),
            texto("emitida"),
        )

        val res = service.handle(peticion(unico(), unico(), "emiteme la cotizacion ya"), caller)

        assertTrue(erp.emisiones.isEmpty(), "la compuerta dejo pasar una emision sin preview")
        // Y el cliente recibe un mensaje de escalamiento, no un error crudo.
        val texto = (res.events.first() as com.erp_maya.agent.api.dto.AgentEvent.Text).text
        assertTrue(texto.contains("compañero"), "se esperaba mensaje de escalamiento, llego: $texto")
    }

    @Test
    fun `confirmar en el mismo turno del preview tampoco emite`() {
        // El modelo intenta previsualizar y emitir de una sola vez.
        modelo.programar(
            ModelCompletion(
                text = null,
                usage = ModelUsage(10, 0, 5),
                toolCalls = listOf(
                    ToolInvocation("1", "cotizaciones.preview", lineas()),
                    ToolInvocation("2", "cotizaciones.issue", lineas() + ("total" to 1800.00)),
                ),
            ),
            texto("listo"),
        )

        service.handle(peticion(unico(), unico(), "cotiza y emite"), caller)
        assertTrue(erp.emisiones.isEmpty(), "preview y emision en el mismo turno no puede emitir")
    }

    @Test
    fun `un total por encima del tope de la empresa se escala`() {
        val remitente = unico()
        erp.totalPreview = BigDecimal("90000.00")   // el tope semilla es 25000

        modelo.programar(pideHerramienta("cotizaciones.preview", lineas()), texto("son Q90,000"))
        service.handle(peticion(remitente, unico(), "cotizame mil sacos"), caller)

        modelo.programar(
            pideHerramienta("cotizaciones.issue", lineas() + ("total" to 90000.00)),
            texto("emitida"),
        )
        val res = service.handle(peticion(remitente, unico(), "confirmo"), caller)

        assertTrue(erp.emisiones.isEmpty(), "se emitio por encima del tope de la empresa")
        assertTrue(
            (res.events.first() as com.erp_maya.agent.api.dto.AgentEvent.Text).text.contains("compañero"),
        )
    }

    @Test
    fun `al agente se le declaran solo las herramientas que tiene concedidas`() {
        service.handle(peticion(unico(), unico(), "hola"), caller)
        val declaradas = modelo.esquemasVistos.first().map { it.name }.toSet()
        // Las seis de la semilla del agente 'ventas', ni una mas.
        assertEquals(6, declaradas.size)
        assertTrue("cotizaciones.issue" in declaradas)
        assertTrue("nomina.read" !in declaradas)
    }

    // ── utilidades ──────────────────────────────────────────────────────────

    private fun lineas() = mapOf(
        "customerId" to 4821,
        "lines" to listOf(mapOf("productId" to 1, "quantity" to 20)),
    )

    private fun pideHerramienta(nombre: String, args: Map<String, Any?>) = ModelCompletion(
        text = null,
        usage = ModelUsage(100, 40, 10),
        toolCalls = listOf(ToolInvocation(UUID.randomUUID().toString().take(8), nombre, args)),
    )

    private fun texto(t: String) = ModelCompletion(t, ModelUsage(120, 80, 20))

    private fun peticion(remitente: String, idem: String, texto: String) = TurnRequest(
        channel = "whatsapp",
        conversationRef = ConversationRef(externalId = remitente, account = "wa:pruebas"),
        actor = null,
        input = TurnInput(type = "text", text = texto),
        capabilities = null,
        scope = null,
        idempotencyKey = idem,
    )

    private fun estadoDe(remitente: String): SessionState =
        requireNotNull(
            json.readValue(
                consulta("SELECT state::text FROM conversations WHERE external_ref = '$remitente'")
                    { it.getString(1) } ?: "{}",
                SessionState::class.java,
            ),
        )

    private fun toolsDe(turnId: String): String? {
        val id = turnId.removePrefix("trn_").trimStart('0')
        return consulta("SELECT tools_called::text FROM agent_runs WHERE turn_id = $id") { it.getString(1) }
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
