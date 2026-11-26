package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.admin.AdminException
import com.erp_maya.agent.admin.TenantAdminService
import com.erp_maya.agent.channel.planner.PlannerFixtures.q
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.erp.client.ErpDePruebas
import com.erp_maya.agent.erp.domain.CatalogCategory
import com.erp_maya.agent.erp.domain.CatalogProduct
import com.erp_maya.agent.erp.domain.ProductImageData
import com.erp_maya.agent.erp.domain.ProductSummary
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.jdbc.DataSourceResolver
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.random.Random

/**
 * El planificador por categorias del ERP: la consola elige categorias, el
 * visitante ve sus productos con foto, elige uno (tipo `p{id}`) y arma
 * opciones; un producto por m² se cotiza por el area de sus medidas.
 */
@MicronautTest(transactional = false)
@Property(name = "micronaut.server.cors.localhost-pass-through", value = "true")
class PlannerCategoriasFlowTest {

    @Inject lateinit var admin: TenantAdminService
    @Inject lateinit var planner: PlannerService
    @Inject lateinit var erp: ErpDePruebas
    @Inject lateinit var servidor: EmbeddedServer
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var dataSourceResolver: DataSourceResolver

    private val http: HttpClient by lazy { HttpClient.create(servidor.url) }
    private lateinit var llave: String
    private var canal = 0L
    private val ip = "10.${Random.nextInt(256)}.${Random.nextInt(256)}.${Random.nextInt(256)}"

    private val colonial = CatalogProduct(
        id = P_COLONIAL, sku = "MV-P1", name = "Pérgola Corredor Colonial", price = q("950.00"), unit = "m²",
        categoryId = CAT_PERGOLAS, categoryName = "Pérgolas y Terrazas", imageVersion = VERSION, status = "active",
        description = "Corredor techado a lo largo de la casa, con vigas de madera.\n\nImagine una tarde de lluvia…\n\n" +
            "Materiales: Pino tratado 6×6, teja\nEstilo: Colonial",
    )
    private val kiosco = CatalogProduct(
        id = P_KIOSCO, sku = "MV-P4", name = "Kiosco Octogonal", price = q("0"), unit = "Unidad",
        categoryId = CAT_PERGOLAS, categoryName = "Pérgolas y Terrazas", imageVersion = null, status = "active",
    )
    private val inactivo = CatalogProduct(id = 599, sku = "MV-X", name = "Retirado", status = "inactive", categoryId = CAT_PERGOLAS)

    @BeforeEach
    fun preparar() {
        erp.limpiar()
        erp.tarifa = PlannerFixtures::tarifa
        erp.categorias += listOf(
            CatalogCategory(CAT_PERGOLAS, "Pérgolas y Terrazas"),
            CatalogCategory(CAT_HOGAR, "Hogar"),
            CatalogCategory(CAT_COCINAS, "Cocinas", CAT_HOGAR),
        )
        erp.porCategoria[CAT_PERGOLAS] = listOf(colonial, kiosco, inactivo)
        erp.productos[P_COLONIAL] = ProductSummary(P_COLONIAL, "MV-P1", "Pérgola Corredor Colonial", q("950.00"), "m²")
        erp.productos[P_KIOSCO] = ProductSummary(P_KIOSCO, "MV-P4", "Kiosco Octogonal", null, "Unidad")
        erp.fotos[P_COLONIAL] = ProductImageData("image/jpeg", FOTO)
        val vista = admin.createPlannerWidget(TENANT, mapOf(
            "name" to "Planificador por categorías",
            "allowedOrigins" to listOf(ORIGEN),
            "categoryIds" to listOf(CAT_PERGOLAS),
        ))
        val w = vista.plannerWidgets.single()
        llave = w.publicKey
        canal = w.id
    }

    @AfterEach
    fun limpiar() {
        ejecutar("DELETE FROM channel_agents WHERE tenant_id = $TENANT")
        ejecutar("DELETE FROM channels WHERE kind = 'planner' AND id IN (SELECT channel_id FROM planner_widgets WHERE tenant_id = $TENANT)")
    }

    @Test
    fun `la consola guarda categorias que existen y muestra el catalogo para elegir`() {
        val vista = admin.view(TENANT)
        assertEquals(listOf(CAT_PERGOLAS), vista.plannerWidgets.single().categoryIds)
        assertTrue(vista.plannerWidgets.single().projectTypes.isEmpty(), "ya no hace falta un tipo fijo")
        assertEquals(listOf("Pérgolas y Terrazas", "Hogar", "Cocinas"), vista.catalogCategories!!.map { it.name })
        assertEquals(CAT_HOGAR, vista.catalogCategories!!.last().parentId)

        val noExiste = assertThrows(AdminException::class.java) {
            admin.updatePlannerWidget(TENANT, canal, mapOf("categoryIds" to listOf(CAT_PERGOLAS, 999)))
        }
        assertTrue(noExiste.fields!!["categoryIds"]!!.contains("999"))
        val nada = assertThrows(AdminException::class.java) {
            admin.updatePlannerWidget(TENANT, canal, mapOf("categoryIds" to emptyList<Long>()))
        }
        assertEquals("Elige al menos una categoría", nada.fields!!["categoryIds"])

        admin.updatePlannerWidget(TENANT, canal, mapOf("categoryIds" to listOf(CAT_HOGAR, CAT_PERGOLAS)))
        assertEquals(listOf(CAT_HOGAR, CAT_PERGOLAS), admin.view(TENANT).plannerWidgets.single().categoryIds)
    }

    @Test
    fun `el visitante ve las categorias y sus productos con foto`() {
        val config = planner.config(llave, ORIGEN)
        assertEquals(listOf(PlannerCategoryView(CAT_PERGOLAS, "Pérgolas y Terrazas")), config.categories)
        assertEquals("https://corvex.com.gt", config.poweredByUrl, "la firma enlaza a Corvex (web.powered-by-url)")

        val lista = planner.productos(llave, ORIGEN, ip, CAT_PERGOLAS)
        assertEquals(listOf("p$P_COLONIAL", "p$P_KIOSCO"), lista.map { it.type }, "sin los inactivos")
        val p = lista.first()
        assertEquals("Pérgola Corredor Colonial", p.name)
        assertEquals("Corredor techado a lo largo de la casa, con vigas de madera.", p.summary, "solo el primer parrafo")
        assertEquals(q("950.00"), p.price)
        assertEquals("m²", p.unit)
        assertEquals(PlannerService.PRICING_OK, p.pricing)
        assertEquals("/$llave/products/$P_COLONIAL/image?size=thumb&v=$VERSION", p.image)
        val sinPrecio = lista.last()
        assertNull(sinPrecio.price)
        assertEquals(PlannerService.PRICING_REVIEW, sinPrecio.pricing)
        assertNull(sinPrecio.image)

        val otra = assertThrows(WebException::class.java) { planner.productos(llave, ORIGEN, ip, CAT_HOGAR) }
        assertEquals(404, otra.status)
    }

    @Test
    fun `elige un producto por m2 y la cantidad es el area de sus medidas`() {
        val s = planner.crearSesion(llave, ORIGEN, ip, PlannerSessionIn(q("15000"), "p$P_COLONIAL"))
        assertEquals("p$P_COLONIAL", s.projectType)

        val a = planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(params = mapOf("ancho" to q("4"), "profundidad" to q("3"))))
        val oa = a.state.options.single()
        assertEquals("Pérgola Corredor Colonial", oa.title)
        assertEquals(q("11400.00"), oa.total, "950 × 12 m²")
        assertEquals(PlannerBudget.FITS, oa.budget.status)
        assertEquals(PlannerDetail("cantidad", "Área", "12 m²", PlannerDetail.QUANTITY), oa.details.last())
        assertEquals("4 × 3 m", oa.details.first { it.kind == PlannerDetail.SIZE }.value)

        // B: mas ancho; el area se recalcula sola.
        val b = planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(from = "A", params = mapOf("ancho" to q("5"))))
        val ob = b.state.options.last()
        assertEquals(q("14250.00"), ob.total, "950 × 15 m²")
        assertEquals(PlannerBudget.TIGHT, ob.budget.status)

        // Uno por unidad y sin precio: queda para el asesor.
        val c = planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(projectType = "p$P_KIOSCO"))
        assertEquals(PlannerService.PRICING_REVIEW, c.state.options.last().pricing)
        assertTrue(c.state.options.last().details.none { it.key == "cantidad" }, "una unidad no se escribe")

        val ajeno = assertThrows(WebException::class.java) {
            planner.crearOpcion(llave, ORIGEN, ip, s.session, PlannerOptionIn(projectType = "p12345"))
        }
        assertEquals(400, ajeno.status)
    }

    @Test
    fun `la foto solo de productos de sus categorias y la miniatura queda en memoria`() {
        val (foto, version) = planner.fotoProducto(llave, P_COLONIAL, miniatura = true)
        assertArrayEquals(FOTO, foto.bytes)
        assertEquals(VERSION, version)
        // La primera pudo salir de memoria si otro test ya la pidio: se cuenta desde aqui.
        val pedidas = erp.fotosPedidas
        planner.fotoProducto(llave, P_COLONIAL, miniatura = true)
        assertEquals(pedidas, erp.fotosPedidas, "la miniatura sale de memoria")
        planner.fotoProducto(llave, P_COLONIAL, miniatura = false)
        assertEquals(pedidas + 1, erp.fotosPedidas, "la completa no se guarda")

        assertEquals(404, assertThrows(WebException::class.java) { planner.fotoProducto(llave, P_KIOSCO, true) }.status, "sin foto")
        assertEquals(404, assertThrows(WebException::class.java) { planner.fotoProducto(llave, 12345, true) }.status, "de otra categoria")
        assertEquals(404, assertThrows(WebException::class.java) { planner.fotoProducto("pk_" + "x".repeat(24), P_COLONIAL, true) }.status)
    }

    @Test
    fun `por HTTP la lista responde con CORS y la foto con cache por version`() {
        val lista = http.toBlocking().exchange(
            HttpRequest.GET<Any>("/v1/planner/$llave/categories/$CAT_PERGOLAS/products").header("Origin", ORIGEN), String::class.java,
        )
        assertEquals(HttpStatus.OK, lista.status)
        assertEquals(ORIGEN, lista.header("Access-Control-Allow-Origin"))
        assertTrue(lista.body()!!.contains("\"type\":\"p$P_COLONIAL\""))

        val foto = http.toBlocking().exchange(
            HttpRequest.GET<Any>("/v1/planner/$llave/products/$P_COLONIAL/image?size=thumb&v=$VERSION"), ByteArray::class.java,
        )
        assertEquals(HttpStatus.OK, foto.status)
        assertEquals("image/jpeg", foto.contentType.get().toString())
        assertEquals("public, max-age=31536000, immutable", foto.header("Cache-Control"))
        val vieja = http.toBlocking().exchange(
            HttpRequest.GET<Any>("/v1/planner/$llave/products/$P_COLONIAL/image?size=thumb&v=1"), ByteArray::class.java,
        )
        assertEquals("public, max-age=300", vieja.header("Cache-Control"))

        val ajena = assertThrows(HttpClientResponseException::class.java) {
            http.toBlocking().exchange(HttpRequest.GET<Any>("/v1/planner/$llave/products/12345/image"), ByteArray::class.java)
        }
        assertEquals(HttpStatus.NOT_FOUND, ajena.status)
    }

    private fun ejecutar(sql: String) {
        dataSourceResolver.resolve(dataSource).connection.use { con -> con.createStatement().use { it.execute(sql) } }
    }

    private companion object {
        const val TENANT = 7702L
        const val ORIGEN = "https://www.maderaviva.com"
        const val CAT_PERGOLAS = 21L
        const val CAT_HOGAR = 22L
        const val CAT_COCINAS = 23L
        const val P_COLONIAL = 501L
        const val P_KIOSCO = 504L
        const val VERSION = 1795403834821L
        val FOTO = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    }
}
