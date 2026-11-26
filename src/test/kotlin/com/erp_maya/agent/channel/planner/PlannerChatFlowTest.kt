package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.admin.TenantAdminService
import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_DECK
import com.erp_maya.agent.channel.planner.PlannerFixtures.PRODUCTO_PERGOLA
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.ToolInvocation
import com.erp_maya.agent.model.service.ProveedorDePruebas
import com.erp_maya.agent.render.RenderJobConsumer
import io.micronaut.context.annotation.Property
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.random.Random

/**
 * La conversacion del planificador de punta a punta: el visitante escribe, el
 * agente (modelo simulado con guion) usa las herramientas planificador.* y la
 * respuesta trae los mensajes, los botones y el estado con la opcion creada.
 */
@MicronautTest(transactional = false)
// El enlace al render que va en el WhatsApp es absoluto: necesita la direccion publica.
@Property(name = "web.public-url", value = "https://agentes.prueba")
class PlannerChatFlowTest {

    @Inject lateinit var admin: TenantAdminService
    @Inject lateinit var planner: PlannerService
    @Inject lateinit var chat: PlannerChatService
    @Inject lateinit var erp: ErpDePruebas
    @Inject lateinit var modelo: ProveedorDePruebas
    @Inject lateinit var renders: RenderJobConsumer
    @Inject lateinit var sesiones: PlannerSessionRepository
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var dataSourceResolver: DataSourceResolver

    private lateinit var llave: String
    private var canal = 0L
    private val ip = "10.${Random.nextInt(256)}.${Random.nextInt(256)}.${Random.nextInt(256)}"

    @BeforeEach
    fun preparar() {
        erp.limpiar()
        modelo.limpiar()
        erp.productos[PRODUCTO_PERGOLA] = ProductSummary(PRODUCTO_PERGOLA, "PERG", "Pérgola", null, "unid")
        erp.productos[PRODUCTO_DECK] = ProductSummary(PRODUCTO_DECK, "DECK", "Deck", q("1250.00"), "m2")
        erp.plantillas[PRODUCTO_PERGOLA] = PlannerFixtures.pergola()
        erp.tarifa = PlannerFixtures::tarifa
        admin.savePolicy(TENANT, mapOf("enabled" to true, "locale" to "es-GT"))
        // El tipo «planificador» lo siembra la migracion 21 (215) con las herramientas planificador.*.
        val tipo = consultarLargo("SELECT id FROM ai_agents WHERE code = 'planificador'")
        val agente = admin.createAgent(TENANT, mapOf("agentId" to tipo, "name" to "Planificador web")).agents.single().id
        val w = admin.createPlannerWidget(TENANT, mapOf(
            "name" to "Planificador Madera Viva",
            "allowedOrigins" to listOf(ORIGEN),
            "projectTypes" to listOf(
                mapOf("code" to "pergola", "label" to "Pérgola", "productId" to PRODUCTO_PERGOLA),
                mapOf("code" to "deck", "label" to "Deck", "productId" to PRODUCTO_DECK),
            ),
            "tenantAgentId" to agente,
        )).plannerWidgets.single()
        llave = w.publicKey
        canal = w.id
    }

    @AfterEach
    fun limpiar() {
        // La conversacion y su rastro apuntan al canal sin cascada: se borran antes.
        val conversaciones = "SELECT id FROM conversations WHERE channel_id IN (SELECT channel_id FROM planner_widgets WHERE tenant_id = $TENANT)"
        listOf("agent_runs", "messages", "render_jobs", "agent_turns").forEach { t ->
            ejecutar("DELETE FROM $t WHERE conversation_id IN ($conversaciones)")
        }
        ejecutar("DELETE FROM conversations WHERE id IN ($conversaciones)")
        ejecutar("UPDATE ai_models SET is_active = FALSE WHERE model_name = 'gpt-image-planificador'")
        ejecutar("DELETE FROM channel_agents WHERE tenant_id = $TENANT")
        ejecutar("DELETE FROM channels WHERE kind = 'planner' AND id IN (SELECT channel_id FROM planner_widgets WHERE tenant_id = $TENANT)")
        ejecutar("DELETE FROM agent_playbooks WHERE tenant_id = $TENANT")
        ejecutar("DELETE FROM tenant_agents WHERE tenant_id = $TENANT")
        ejecutar("DELETE FROM agent_tenant_policies WHERE tenant_id = $TENANT")
        modelo.limpiar()
    }

    private fun herramienta(nombre: String, vararg args: Pair<String, Any?>) =
        ModelCompletion(null, ModelUsage(100, 0, 20), listOf(ToolInvocation("call_${Random.nextInt(1_000_000)}", nombre, mapOf(*args))))

    private fun texto(t: String) = ModelCompletion(t, ModelUsage(100, 0, 20))

    /** Como los genera el widget: 22 caracteres al azar. */
    private fun id() = "msg_" + (1..22).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

    @Test
    fun `la idea crea la opcion A con precio y semaforo, y el agente pregunta con botones`() {
        assertTrue(planner.config(llave, ORIGEN).chat, "con agente asignado hay chat")
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))

        modelo.programar(
            herramienta("planificador.opcion", "medidas" to "ancho=4; profundidad=3", "opciones" to "techo=dos_aguas"),
            herramienta("planificador.preguntar", "opcion" to "cubierta"),
            texto("Listo, armé la opción A: Q 16,400, encaja en tu presupuesto. ¿Policarbonato o teja?"),
        )
        val r = chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "Quiero una pérgola de 4 por 3, a dos aguas"))

        assertEquals(listOf("Listo, armé la opción A: Q 16,400, encaja en tu presupuesto. ¿Policarbonato o teja?"), r.messages)
        val a = r.state.options.single()
        assertEquals("A", a.letter)
        assertEquals(q("16400.00"), a.total)
        assertEquals(PlannerBudget.FITS, a.budget.status)
        assertEquals(listOf("Policarbonato", "Teja"), r.replies.map { it.label })
        assertEquals(mapOf("cubierta" to "teja"), r.replies[1].change, "el boton crea la opcion sin pasar por el modelo")

        // Lo que vio el modelo: el estado del planificador y solo sus herramientas.
        val prompt = modelo.recibidos.first().messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(prompt.contains("Planificador por presupuesto"), prompt)
        assertTrue(prompt.contains("Presupuesto: Q 20,000"), prompt)
        assertTrue(prompt.contains("cubierta (Cubierta, obligatoria)"), prompt)
        val herramientas = modelo.esquemasVistos.first().map { it.name }
        assertTrue(herramientas.containsAll(listOf("planificador.opcion", "planificador.preguntar", "planificador.presupuesto")), "$herramientas")
        assertTrue(herramientas.none { it.startsWith("cotizacion") }, "$herramientas")
    }

    @Test
    fun `un cambio crea B desde A sin borrar A y el turno siguiente ya la ve`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        modelo.programar(herramienta("planificador.opcion", "opciones" to "techo=dos_aguas"), texto("Listo, opción A."))
        chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "Una pérgola a dos aguas"))

        modelo.programar(
            herramienta("planificador.opcion", "desde" to "A", "opciones" to "cubierta=teja; luces=led"),
            herramienta("planificador.anotar", "clave" to "uso", "valor" to "Patio trasero"),
            texto("Armé la opción B: se pasa por Q 4,800. Con policarbonato queda en Q 19,900."),
        )
        val r = chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "¿Y si fuera de teja con luces? Es para el patio"))
        val (a, b) = r.state.options
        assertEquals(q("16400.00"), a.total)
        assertEquals("A", b.parent)
        assertEquals(q("24800.00"), b.total)
        assertEquals(PlannerBudget.OVER, b.budget.status)
        assertEquals("B", r.state.activeOption)
        assertEquals(listOf(PlannerNoteView("uso", "Patio trasero")), r.state.notes)

        val segundo = modelo.recibidos[2].messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(segundo.contains("A · Pérgola · Dos aguas"), "el segundo turno ve la opcion A: $segundo")
    }

    @Test
    fun `el presupuesto dicho en el chat se guarda`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(null, "pergola"))
        modelo.programar(herramienta("planificador.presupuesto", "monto" to "Q 25,000"), texto("Anotado: Q 25,000."))
        val r = chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "Tengo unos 25 mil"))
        assertEquals(q("25000.00"), r.state.budget)
    }

    @Test
    fun `lo que la plantilla no admite vuelve al modelo como error y no crea nada`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        modelo.programar(
            herramienta("planificador.opcion", "medidas" to "ancho=20"),
            texto("Esa medida no se puede: el ancho llega hasta 8 m."),
        )
        val r = chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "Una de 20 metros"))
        assertTrue(r.state.options.isEmpty())
        val resultado = modelo.recibidos[1].messages.last().content.orEmpty()
        assertTrue(resultado.contains("Ancho no puede pasar de 8 m"), resultado)
    }

    @Test
    fun `reintentar el mismo mensaje no corre otro turno y conserva los botones`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        modelo.programar(herramienta("planificador.preguntar", "respuestas" to "Sí; No, gracias"), texto("¿Lo quieres con luces?"))
        val mensaje = PlannerMessageIn(id(), "Hola")
        val primero = chat.enviar(llave, ORIGEN, ip, s.session, mensaje)
        val llamadas = modelo.recibidos.size
        val segundo = chat.enviar(llave, ORIGEN, ip, s.session, mensaje)
        assertEquals(llamadas, modelo.recibidos.size, "la misma llave no vuelve a llamar al modelo")
        assertEquals(primero.messages, segundo.messages)
        assertEquals(listOf("Sí", "No, gracias"), segundo.replies.map { it.label })
        assertEquals("No, gracias", segundo.replies[1].text)
    }

    @Test
    fun `sin agente asignado no hay chat pero las opciones siguen funcionando`() {
        admin.updatePlannerWidget(TENANT, canal, mapOf("tenantAgentId" to null))
        assertFalse(planner.config(llave, ORIGEN).chat)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        val e = assertThrows(WebException::class.java) { chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "Hola")) }
        assertEquals("UNAVAILABLE", e.code)
        assertEquals(1, planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn()).state.options.size)
    }

    @Test
    fun `el tope de renders es el del playbook del agente, contado por diseño`() {
        // Sin generar_renders en el playbook ni modelo de imagenes: no hay renders.
        assertEquals(0, planner.config(llave, ORIGEN).rendersMax)

        ejecutar(
            """
            INSERT INTO ai_models (provider_id, model_name, context_window, supports_tools, generates_images, image_cost)
            SELECT provider_id, 'gpt-image-planificador', 1000, FALSE, TRUE, 0.04 FROM ai_models
            WHERE model_name = 'gpt-4o' AND NOT EXISTS (SELECT 1 FROM ai_models WHERE model_name = 'gpt-image-planificador')
            """.trimIndent(),
        )
        ejecutar("UPDATE ai_models SET is_active = TRUE WHERE model_name = 'gpt-image-planificador'")
        val imagenes = consultarLargo("SELECT id FROM ai_models WHERE model_name = 'gpt-image-planificador'")
        val agente = admin.view(TENANT).agents.single().id
        admin.updateAgent(TENANT, agente, mapOf("imageModelId" to imagenes))
        // Con modelo de imagenes pero sin generar_renders, sigue en 0.
        assertEquals(0, planner.config(llave, ORIGEN).rendersMax)

        // La misma configuracion que las «imagenes de ejemplo por cotizacion».
        admin.savePlaybook(TENANT, agente, mapOf("config" to mapOf(
            "imagenes" to mapOf("aceptar" to true, "max_por_turno" to 3, "generar_renders" to true, "renders_max" to 2),
        )), "prueba")
        assertEquals(2, planner.config(llave, ORIGEN).rendersMax)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        assertEquals(PlannerRendersView(0, 2), s.renders, "cada diseño (sesion) tiene su tope")
    }

    /** Modelo de imagenes en el agente y renders en su playbook, como en la consola. */
    private fun habilitarRenders(maximo: Int) {
        ejecutar(
            """
            INSERT INTO ai_models (provider_id, model_name, context_window, supports_tools, generates_images, image_cost)
            SELECT provider_id, 'gpt-image-planificador', 1000, FALSE, TRUE, 0.04 FROM ai_models
            WHERE model_name = 'gpt-4o' AND NOT EXISTS (SELECT 1 FROM ai_models WHERE model_name = 'gpt-image-planificador')
            """.trimIndent(),
        )
        ejecutar("UPDATE ai_models SET is_active = TRUE WHERE model_name = 'gpt-image-planificador'")
        val imagenes = consultarLargo("SELECT id FROM ai_models WHERE model_name = 'gpt-image-planificador'")
        val agente = admin.view(TENANT).agents.single().id
        admin.updateAgent(TENANT, agente, mapOf("imageModelId" to imagenes))
        admin.savePlaybook(TENANT, agente, mapOf("config" to mapOf(
            "imagenes" to mapOf("aceptar" to true, "max_por_turno" to 3, "generar_renders" to true, "renders_max" to maximo),
        )), "prueba")
    }

    /** Una foto real (PNG) para la foto del espacio: el render la decodifica y la reduce. */
    private fun fotoPng(): String {
        val img = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `ver render encola, la cola lo genera y queda listo en su opcion`() {
        habilitarRenders(2)
        assertTrue(planner.config(llave, ORIGEN).features.renders)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(choices = mapOf("techo" to "dos_aguas")))

        val pedido = planner.pedirRender(llave, ORIGEN, ip, s.session, "A")
        assertEquals("pending", pedido.options.single().render?.status)
        assertEquals(PlannerRendersView(1, 2), pedido.renders)
        // Otro toque mientras esta en camino no gasta otro render.
        assertEquals(PlannerRendersView(1, 2), planner.pedirRender(llave, ORIGEN, ip, s.session, "A").renders)

        assertEquals(1, renders.procesar())
        val (editada, instrucciones) = modelo.rendersPedidos.single()
        assertFalse(editada, "sin foto del espacio se genera desde cero")
        assertTrue(instrucciones.contains("Techo: Dos aguas"), instrucciones)

        val listo = planner.leer(llave, ORIGEN, s.session).options.single().render!!
        assertEquals("ready", listo.status)
        val id = listo.url!!.substringAfterLast('/')
        assertTrue(listo.url!!.startsWith("/$llave/media/wm_"), listo.url)
        assertTrue(planner.archivo(llave, id).bytes.contentEquals(ProveedorDePruebas.RENDER_FALSO))
        assertEquals("NOT_FOUND", assertThrows(WebException::class.java) { planner.archivo("pk_otroOtroOtroOtroOtro12", id) }.code)
    }

    @Test
    fun `el tope del diseño corta y un render fallido no cuenta`() {
        habilitarRenders(2)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn())
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(from = "A", choices = mapOf("cubierta" to "teja")))
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(from = "B", choices = mapOf("luces" to "led")))

        // A falla en la cola: queda fallido y no cuenta.
        modelo.falloRender = "sin saldo"
        planner.pedirRender(llave, ORIGEN, ip, s.session, "A")
        ejecutar("UPDATE render_jobs SET attempts = 5 WHERE status = 'pending' AND account_ref = 'planner:$llave'")
        renders.procesar()
        val fallido = planner.leer(llave, ORIGEN, s.session)
        assertEquals("failed", fallido.options[0].render?.status)
        assertEquals(PlannerRendersView(0, 2), fallido.renders)

        modelo.falloRender = null
        planner.pedirRender(llave, ORIGEN, ip, s.session, "B")
        planner.pedirRender(llave, ORIGEN, ip, s.session, "C")
        val tope = assertThrows(WebException::class.java) { planner.pedirRender(llave, ORIGEN, ip, s.session, "A") }
        assertEquals("RENDER_LIMIT", tope.code)
        assertEquals(409, tope.status)
    }

    @Test
    fun `con foto del espacio el render sale sobre ella`() {
        habilitarRenders(3)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        val conFoto = planner.subirFoto(llave, ORIGEN, ip, s.session, PlannerPhotoIn(fotoPng(), "patio.png"))
        assertEquals("patio.png", conFoto.photo?.name)
        assertEquals("BAD_IMAGE", assertThrows(WebException::class.java) {
            planner.subirFoto(llave, ORIGEN, ip, s.session, PlannerPhotoIn(java.util.Base64.getEncoder().encodeToString("no es foto".toByteArray())))
        }.code)

        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn())
        planner.pedirRender(llave, ORIGEN, ip, s.session, "A")
        renders.procesar()
        val (editada, instrucciones) = modelo.rendersPedidos.single()
        assertTrue(editada, "con foto se edita la foto")
        assertTrue(instrucciones.startsWith("Edita esta foto real del espacio"), instrucciones)
        assertEquals("ready", planner.leer(llave, ORIGEN, s.session).options.single().render?.status)

        // Quitar la foto: el siguiente render sale sin ella.
        assertEquals(null, planner.actualizar(llave, ORIGEN, s.session, PlannerUpdateIn(removePhoto = true)).photo)
    }

    @Test
    fun `sin renders en el playbook no hay ver render ni foto`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn())
        assertFalse(planner.config(llave, ORIGEN).features.renders)
        assertEquals("RENDERS_OFF", assertThrows(WebException::class.java) { planner.pedirRender(llave, ORIGEN, ip, s.session, "A") }.code)
        assertEquals("RENDERS_OFF", assertThrows(WebException::class.java) {
            planner.subirFoto(llave, ORIGEN, ip, s.session, PlannerPhotoIn(fotoPng()))
        }.code)
    }

    @Test
    fun `el agente pide el render cuando el visitante lo pide en el chat`() {
        habilitarRenders(2)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn())
        modelo.programar(herramienta("planificador.render", "letra" to "A"), texto("Listo, en unos segundos lo ves."))
        val r = chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "¿Me muestras cómo se ve?"))
        assertEquals("pending", r.state.options.single().render?.status)
        val prompt = modelo.recibidos.first().messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(prompt.contains("Renders de este diseño: 0 de 2"), prompt)
        assertTrue(prompt.contains("planificador.render"), prompt)
    }

    @Test
    fun `contactar a un asesor abre WhatsApp con el diseño y su ultimo render, sin crear cotizacion`() {
        assertFalse(planner.config(llave, ORIGEN).features.contact, "sin numero no hay boton")
        admin.updatePlannerWidget(TENANT, canal, mapOf("contactWhatsapp" to "+502 1234 5678"))
        assertTrue(planner.config(llave, ORIGEN).features.contact)
        habilitarRenders(2)
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        assertEquals(null, s.contact, "sin opciones no hay nada que contar")

        planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(choices = mapOf("techo" to "dos_aguas")))
        planner.pedirRender(llave, ORIGEN, ip, s.session, "A")
        renders.procesar()
        val b = planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(from = "A", choices = mapOf("cubierta" to "teja"))).state
        val enlace = b.contact!!.whatsappUrl
        assertTrue(enlace.startsWith("https://wa.me/50212345678?text="), enlace)
        val texto = java.net.URLDecoder.decode(enlace.substringAfter("text="), Charsets.UTF_8)
        assertTrue(texto.contains("Opción elegida: B · Pérgola · Dos aguas, Teja — Q 21,300 (se pasa por Q 1,300)"), texto)
        assertTrue(texto.contains("Otras opciones que vi: A · Q 16,400"), texto)
        val render = planner.leer(llave, ORIGEN, s.session).options.first { it.letter == "A" }.render!!.url!!
        assertTrue(texto.contains("Render (A): https://agentes.prueba/v1/planner$render"), texto)

        planner.contactar(llave, ORIGEN, s.session, PlannerContactIn("B"))
        assertEquals("contact_requested", sesiones.eventos(s.session).last())
        assertTrue(erp.emisiones.isEmpty(), "no se crea cotizacion en el ERP")
    }

    @Test
    fun `el mensaje se valida antes de gastar el modelo`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("20000"), "pergola"))
        assertEquals("MESSAGE_ID", assertThrows(WebException::class.java) { chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn("x", "Hola")) }.code)
        assertEquals("EMPTY", assertThrows(WebException::class.java) { chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "  ")) }.code)
        assertEquals("TOO_LONG", assertThrows(WebException::class.java) { chat.enviar(llave, ORIGEN, ip, s.session, PlannerMessageIn(id(), "x".repeat(601))) }.code)
        assertEquals("ORIGIN", assertThrows(WebException::class.java) { chat.enviar(llave, "https://otro.com", ip, s.session, PlannerMessageIn(id(), "Hola")) }.code)
        assertTrue(modelo.recibidos.isEmpty())
    }

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con -> con.createStatement().use { it.execute(sql) } }
    }

    private fun consultarLargo(sql: String): Long =
        dataSourceResolver.resolve(dataSource).connection.use { con ->
            con.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

    private companion object {
        const val TENANT = 7701L
        const val ORIGEN = "https://www.maderaviva.com"
    }
}
