package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.admin.AdminException
import com.erp_maya.agent.admin.TenantAdminService
import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_DECK
import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_PERGOLA
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.erp.domain.ProductSummary
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.random.Random

/**
 * El planificador de punta a punta contra un Postgres real (Liquibase aplica
 * la migracion 21) y el ERP simulado: alta en la consola, sesion, opciones
 * A/B con precios y semaforo, cambio de presupuesto sin volver al ERP, y lo
 * que protege la entrada publica.
 */
@MicronautTest(transactional = false)
// Micronaut rechaza un Origin ajeno contra localhost (ataque drive-by-localhost). En produccion
// el Host es el dominio publico y no aplica; aqui el servidor de pruebas es localhost.
@Property(name = "micronaut.server.cors.localhost-pass-through", value = "true")
class PlannerFlowTest {

    @Inject lateinit var admin: TenantAdminService
    @Inject lateinit var planner: PlannerService
    @Inject lateinit var sesiones: PlannerSessionRepository
    @Inject lateinit var erp: ErpDePruebas
    @Inject lateinit var servidor: EmbeddedServer
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var dataSourceResolver: DataSourceResolver

    private val http: HttpClient by lazy { HttpClient.create(servidor.url) }

    private lateinit var llave: String
    private var canal = 0L
    // Cada test con su IP: los limites por minuto son del singleton y no se reinician entre tests.
    private val ip = "10.${Random.nextInt(256)}.${Random.nextInt(256)}.${Random.nextInt(256)}"

    @BeforeEach
    fun preparar() {
        erp.limpiar()
        erp.productos[PRODUCTO_PERGOLA] = ProductSummary(PRODUCTO_PERGOLA, "PERG", "Pérgola", null, "unid")
        erp.productos[PRODUCTO_DECK] = ProductSummary(PRODUCTO_DECK, "DECK", "Deck", q("1250.00"), "m2")
        erp.plantillas[PRODUCTO_PERGOLA] = PlannerFixtures.pergola()
        erp.tarifa = PlannerFixtures::tarifa
        val vista = admin.createPlannerWidget(TENANT, mapOf(
            "name" to "Planificador Madera Viva",
            "allowedOrigins" to listOf(ORIGEN),
            "accentColor" to "#8B6549",
            "projectTypes" to listOf(
                mapOf("code" to "pergola", "label" to "Pérgola", "productId" to PRODUCTO_PERGOLA),
                mapOf("code" to "deck", "label" to "Deck", "productId" to PRODUCTO_DECK),
            ),
        ))
        val w = vista.plannerWidgets.single()
        llave = w.publicKey
        canal = w.id
        erp.tenantsVistos.clear()
    }

    @AfterEach
    fun limpiar() {
        // Las asignaciones no caen en cascada; borrar el canal se lleva el planificador, sus sesiones, opciones y eventos.
        ejecutar("DELETE FROM channel_agents WHERE tenant_id = $TENANT")
        ejecutar("DELETE FROM channels WHERE kind = 'planner' AND id IN (SELECT channel_id FROM planner_widgets WHERE tenant_id = $TENANT)")
        ejecutar("DELETE FROM tenant_agents WHERE tenant_id = $TENANT")
    }

    private fun nuevaSesion(budget: String? = "20000", tipo: String? = "pergola") =
        planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(budget?.let { q(it) }, tipo))

    private fun opcion(sid: String, cuerpo: PlannerOptionIn) = planner.crearOpcion(llave, ORIGEN, ip, sid, cuerpo)

    private fun webError(bloque: () -> Unit): WebException = assertThrows(WebException::class.java) { bloque() }

    // ── el flujo de las imagenes ────────────────────────────────────────────

    @Test
    fun `A encaja, B se pasa con sugerencia y cambiar el presupuesto no vuelve al ERP`() {
        val s = nuevaSesion()
        assertEquals(q("20000.00"), s.budget)
        assertEquals("GTQ", s.currency)
        assertTrue(s.options.isEmpty())

        // A: la idea, con techo a dos aguas.
        val a = opcion(s.session, PlannerOptionIn(choices = mapOf("techo" to "dos_aguas")))
        assertEquals("A", a.created)
        val va = a.state.options.single()
        assertEquals(q("16400.00"), va.total)
        assertEquals(PlannerBudget.FITS, va.budget.status)
        assertEquals(q("3600.00"), va.budget.remaining)
        assertEquals("Pérgola · Dos aguas, Policarbonato", va.title)
        assertNull(va.suggestion)
        assertEquals(1, erp.lotes.size, "una llamada al ERP por opcion")

        // B: desde A, con teja y luces.
        val b = opcion(s.session, PlannerOptionIn(from = "A", choices = mapOf("cubierta" to "teja", "luces" to "led")))
        assertEquals("B", b.created)
        assertEquals("B", b.state.activeOption)
        val (oa, ob) = b.state.options
        assertEquals(q("16400.00"), oa.total, "A no se toca")
        assertEquals("A", ob.parent)
        assertEquals(q("24800.00"), ob.total)
        assertEquals(PlannerBudget.OVER, ob.budget.status)
        assertEquals(q("4800.00"), ob.budget.excess)
        assertEquals(listOf("cubierta", "luces"), ob.details.filter { it.changed }.map { it.key })
        assertEquals("Cubierta: Policarbonato", ob.suggestion!!.label)
        assertEquals(q("19900.00"), ob.suggestion!!.total)
        assertTrue(ob.suggestion!!.fits)
        assertEquals(2, erp.lotes.size)

        // Sube el presupuesto: la sugerencia cambia sin llamar al ERP.
        val subido = planner.actualizar(llave, ORIGEN, s.session, PlannerUpdateIn(budget = q("22000"), notes = mapOf("uso" to "Patio trasero")))
        assertEquals(2, erp.lotes.size, "el semaforo sale de lo guardado")
        val ob2 = subido.options[1]
        assertEquals(PlannerBudget.OVER, ob2.budget.status)
        assertEquals("Iluminación: Sin luces", ob2.suggestion!!.label)
        assertEquals(q("21300.00"), ob2.suggestion!!.total)
        assertEquals(listOf(PlannerNoteView("uso", "Patio trasero")), subido.notes)

        // Vuelve a A.
        assertEquals("A", planner.activar(llave, ORIGEN, s.session, "a").activeOption)

        assertEquals(
            listOf("session_started", "option_created", "option_created", "budget_changed", "note_changed", "option_activated"),
            sesiones.eventos(s.session),
        )
        assertTrue(erp.tenantsVistos.all { it == TENANT }, "la empresa sale del planificador: ${erp.tenantsVistos}")
    }

    @Test
    fun `lo que se puede elegir de un tipo sale de su plantilla sin costos`() {
        val t = planner.tipo(llave, ORIGEN, "pergola")
        assertEquals(listOf("ancho", "profundidad", "alto"), t.params.map { it.code })
        assertEquals(listOf("techo", "cubierta", "luces"), t.options.map { it.code })
        assertEquals("Más fresca y silenciosa con lluvia", t.options[1].choices[1].benefit)
        assertEquals(listOf("premium"), t.presets.map { it.code })
        val deck = planner.tipo(llave, ORIGEN, "deck")
        assertTrue(deck.params.isEmpty() && deck.options.isEmpty(), "sin plantilla no hay elecciones")
        val config = planner.config(llave, ORIGEN)
        assertEquals(listOf("pergola", "deck"), config.projectTypes.map { it.code })
        assertEquals("#8B6549", config.accent)
        assertEquals(q("90.00"), config.fitThresholdPct)
    }

    @Test
    fun `un producto sin plantilla se valora por precio de lista y medidas`() {
        // El deck se vende por m2: la cantidad es el area de sus medidas (5 × 4 = 20), no la que venga.
        val s = nuevaSesion(tipo = "deck")
        val r = opcion(s.session, PlannerOptionIn(params = mapOf("ancho" to q("5"), "profundidad" to q("4")), quantity = q("2")))
        val linea = erp.lotes.single().single().lines.single()
        assertEquals(q("25000.00"), linea.lineTotal)
        assertEquals(0, q("20").compareTo(linea.quantity))
        assertEquals(q("5"), linea.width)
        assertEquals(q("25000.00"), r.state.options.single().total)
        assertEquals("deck", r.state.projectType)
        assertEquals("20 m2", r.state.options.single().details.last().value)
    }

    @Test
    fun `si un asesor confirma el precio la opcion queda en gris y sin total`() {
        erp.tarifa = { null }
        val s = nuevaSesion()
        val o = opcion(s.session, PlannerOptionIn()).state.options.single()
        assertEquals("review", o.pricing)
        assertNull(o.total)
        assertEquals(PlannerBudget.REVIEW, o.budget.status)
        assertNull(o.suggestion)
    }

    // ── lo que protege la entrada ───────────────────────────────────────────

    @Test
    fun `dominio, llave y sesion`() {
        assertEquals("ORIGIN", webError { planner.config(llave, "https://otro.com") }.code)
        assertEquals(403, webError { planner.config(llave, null) }.status)
        assertEquals("NOT_FOUND", webError { planner.config("pk_noexiste00000000000000", ORIGEN) }.code)
        assertEquals("NOT_FOUND", webError { planner.config("wk_esdelchat0000000000000", ORIGEN) }.code)
        assertEquals("SESSION", webError { planner.leer(llave, ORIGEN, "ps_" + "x".repeat(32)) }.code)
        assertEquals("SESSION", webError { planner.leer(llave, ORIGEN, "../../etc") }.code)
    }

    @Test
    fun `errores del visitante no llegan al ERP`() {
        val s = nuevaSesion()
        assertEquals("BAD_SPEC", webError { opcion(s.session, PlannerOptionIn(choices = mapOf("cubierta" to "vidrio"))) }.code)
        assertEquals("BAD_SPEC", webError { opcion(s.session, PlannerOptionIn(params = mapOf("ancho" to q("20")))) }.code)
        assertEquals("BAD_SPEC", webError { opcion(s.session, PlannerOptionIn(projectType = "closet")) }.code)
        assertEquals("OPTION", webError { opcion(s.session, PlannerOptionIn(from = "C")) }.code)
        assertEquals("BUDGET", webError { planner.actualizar(llave, ORIGEN, s.session, PlannerUpdateIn(budget = q("-5"))) }.code)
        assertEquals("NOTES", webError { planner.actualizar(llave, ORIGEN, s.session, PlannerUpdateIn(notes = mapOf("Uso Final" to "x"))) }.code)
        assertTrue(erp.lotes.isEmpty())
        assertTrue(planner.leer(llave, ORIGEN, s.session).options.isEmpty())
    }

    @Test
    fun `sin tipo de proyecto no se puede crear la opcion`() {
        val s = nuevaSesion(tipo = null)
        assertEquals("BAD_SPEC", webError { opcion(s.session, PlannerOptionIn()) }.code)
    }

    @Test
    fun `el ERP caido o rechazando no deja opciones a medias`() {
        val s = nuevaSesion()
        erp.caido = true
        val caido = webError { opcion(s.session, PlannerOptionIn()) }
        assertEquals(502, caido.status)
        assertEquals("PRICING", caido.code)
        erp.caido = false
        erp.rechazo = "La plantilla «Pérgola a la medida» está apagada"
        assertEquals("La plantilla «Pérgola a la medida» está apagada", webError { opcion(s.session, PlannerOptionIn()) }.message)
        assertTrue(planner.leer(llave, ORIGEN, s.session).options.isEmpty())
    }

    @Test
    fun `el maximo de opciones es del planificador`() {
        admin.updatePlannerWidget(TENANT, canal, mapOf("maxOptions" to 2))
        val s = nuevaSesion()
        opcion(s.session, PlannerOptionIn())
        opcion(s.session, PlannerOptionIn(from = "A", choices = mapOf("luces" to "led")))
        val e = webError { opcion(s.session, PlannerOptionIn(from = "B", choices = mapOf("cubierta" to "teja"))) }
        assertEquals("MAX_OPTIONS", e.code)
        assertEquals(409, e.status)
    }

    @Test
    fun `un planificador apagado no responde`() {
        admin.updatePlannerWidget(TENANT, canal, mapOf("active" to false))
        assertEquals("UNAVAILABLE", webError { planner.config(llave, ORIGEN) }.code)
    }

    @Test
    fun `la sesion vencida se borra con sus opciones`() {
        val s = nuevaSesion()
        opcion(s.session, PlannerOptionIn())
        ejecutar("UPDATE planner_sessions SET expires_at = now() - interval '1 minute' WHERE id = '${s.session}'")
        assertEquals("SESSION", webError { planner.leer(llave, ORIGEN, s.session) }.code)
        assertTrue(sesiones.limpiar() >= 1)
        assertTrue(sesiones.opciones(s.session).isEmpty())
        assertTrue(sesiones.eventos(s.session).isEmpty())
    }

    // ── consola ─────────────────────────────────────────────────────────────

    @Test
    fun `la consola verifica que el producto exista en el ERP de la empresa`() {
        val e = assertThrows(AdminException::class.java) {
            admin.createPlannerWidget(TENANT, mapOf(
                "name" to "Otro",
                "allowedOrigins" to listOf(ORIGEN),
                "projectTypes" to listOf(mapOf("code" to "closet", "label" to "Closet", "productId" to 999)),
            ))
        }
        assertEquals("VALIDATION", e.code)
        assertNotNull(e.fields?.get("projectTypes"))
        assertEquals(1, admin.view(TENANT).plannerWidgets.size)
    }

    private fun agenteDeVentas(nombre: String): Long {
        val tipo = consultarLargo("SELECT id FROM ai_agents WHERE code = 'ventas'")
        return admin.createAgent(TENANT, mapOf("agentId" to tipo, "name" to nombre)).agents.single { it.name == nombre }.id
    }

    @Test
    fun `el planificador se asigna a un agente de la empresa como el chat web`() {
        val agente = agenteDeVentas("Ventas Planificador")
        var v = admin.updatePlannerWidget(TENANT, canal, mapOf("tenantAgentId" to agente))
        assertEquals(agente, v.plannerWidgets.single().tenantAgentId)
        // Es un canal de la empresa: aparece en la lista y en los canales del agente.
        val c = v.channels.single { it.id == canal }
        assertEquals("planner", c.kind)
        assertEquals(agente, c.tenantAgentId)
        assertEquals("Planificador Madera Viva", c.name)
        assertTrue(v.agents.single { it.id == agente }.channelIds.contains(canal))

        // Editar otra cosa no toca la asignacion.
        v = admin.updatePlannerWidget(TENANT, canal, mapOf("maxOptions" to 5))
        assertEquals(agente, v.plannerWidgets.single().tenantAgentId)

        // null lo deja sin agente; las opciones y precios siguen funcionando.
        v = admin.updatePlannerWidget(TENANT, canal, mapOf("tenantAgentId" to null))
        assertNull(v.plannerWidgets.single().tenantAgentId)
        assertNull(v.channels.single { it.id == canal }.tenantAgentId)
        assertEquals("pergola", planner.config(llave, ORIGEN).projectTypes.first().code)
    }

    @Test
    fun `se puede crear ya asignado y no acepta agentes de otra empresa`() {
        val agente = agenteDeVentas("Ventas Web")
        val v = admin.createPlannerWidget(TENANT, mapOf(
            "name" to "Planificador decks",
            "allowedOrigins" to listOf(ORIGEN),
            "projectTypes" to listOf(mapOf("code" to "deck", "label" to "Deck", "productId" to PRODUCTO_DECK)),
            "tenantAgentId" to agente,
        ))
        assertEquals(agente, v.plannerWidgets.single { it.name == "Planificador decks" }.tenantAgentId)

        val e = assertThrows(AdminException::class.java) {
            admin.updatePlannerWidget(TENANT, canal, mapOf("tenantAgentId" to 99_999_999))
        }
        assertEquals("VALIDATION", e.code)
        assertNotNull(e.fields?.get("tenantAgentId"))
        assertNull(admin.view(TENANT).plannerWidgets.single { it.id == canal }.tenantAgentId, "un error no cambia nada")
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    @Test
    fun `por HTTP acepta JSON como text plain y responde con CORS del origen`() {
        val r = http.toBlocking().exchange(
            HttpRequest.POST("/v1/planner/$llave/sessions", """{"budget":20000,"projectType":"pergola"}""")
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("Origin", ORIGEN)
                .header("X-Forwarded-For", ip),
            String::class.java,
        )
        assertEquals(HttpStatus.OK, r.status)
        assertEquals(ORIGEN, r.headers.get("Access-Control-Allow-Origin"))
        assertTrue(r.body()!!.contains("\"session\":\"ps_"), r.body())
        assertTrue(!r.body()!!.contains("subtotal"), "el visitante no ve subtotal ni IVA")

        val prohibido = assertThrows(HttpClientResponseException::class.java) {
            http.toBlocking().exchange(
                HttpRequest.GET<Any>("/v1/planner/$llave/config").header("Origin", "https://otro.com"),
                String::class.java,
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, prohibido.status)
        assertEquals("https://otro.com", prohibido.response.headers.get("Access-Control-Allow-Origin"))
    }

    @Test
    fun `por HTTP un cuerpo invalido es 400`() {
        val sid = nuevaSesion().session
        val e = assertThrows(HttpClientResponseException::class.java) {
            http.toBlocking().exchange(
                HttpRequest.POST("/v1/planner/$llave/sessions/$sid/options", "no es json")
                    .contentType(MediaType.TEXT_PLAIN_TYPE)
                    .header("Origin", ORIGEN)
                    .header("X-Forwarded-For", ip),
                String::class.java,
            )
        }
        assertEquals(HttpStatus.BAD_REQUEST, e.status)
    }

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con -> con.createStatement().use { it.execute(sql) } }
    }

    private fun consultarLargo(sql: String): Long =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

    private companion object {
        const val TENANT = 7700L
        const val ORIGEN = "https://www.maderaviva.com"
    }
}
