package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.QuoteLineWrite
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.StockInfo
import io.micronaut.core.annotation.Nullable
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/** Pagina de Micronaut Data tal como la serializa el ERP; solo interesa el contenido. */
@Serdeable
data class ErpPage<T>(val content: List<T> = emptyList())

/** Producto como lo devuelve GET /api/products. Solo lo que el agente usa: sin costos. */
@Serdeable
data class ErpProduct(
    val id: Long,
    val sku: String,
    val name: String,
    @Nullable val price: BigDecimal? = null,
    @Nullable val unit: String? = null,
    @Nullable val status: String? = null,
)

/** Fila de existencias de GET /api/stock: un producto en una sucursal/lote. */
@Serdeable
data class ErpStockRow(
    val productId: Long,
    @Nullable val branchName: String? = null,
    @Nullable val quantity: BigDecimal? = null,
)

/** POST /api/clients (ClientDtos.Request del ERP). */
@Serdeable
data class CreateCustomerBody(
    val name: String,
    @Nullable val nit: String?,
    @Nullable val phone: String?,
    // client_type rige impuestos y precios en el ERP: no se adivina desde el
    // chat. Sin valor, el ERP aplica su default ('CF').
    val status: String = "active",
)

/** POST /api/quotes (QuoteDtos.Request del ERP). */
@Serdeable
data class CreateQuoteBody(
    val partyType: String,
    val clientId: Long,
    @Nullable val clientName: String?,
    @Nullable val clientNit: String?,
    @Nullable val clientEmail: String?,
    val validUntil: String,
    val createdBy: String,
    val items: List<CreateQuoteItem>,
    val origin: String,
    val channel: String,
    val conversationRef: String,
)

@Serdeable
data class CreateQuoteItem(
    @Nullable val productId: Long?,
    @Nullable val itemName: String?,
    @Nullable val uom: String?,
    val quantity: BigDecimal,
    @Nullable val unitPrice: BigDecimal?,
    @Nullable val discount: BigDecimal?,
)

/** POST /api/quotes/{id}/notes (QuoteDtos.NoteRequest del ERP). */
@Serdeable
data class NoteBody(val note: String, @Nullable val actor: String?)

/** PUT /api/quotes/{id} (QuoteDtos.UpdateRequest del ERP). */
@Serdeable
data class UpdateQuoteBody(
    val validUntil: String,
    @Nullable val notes: String?,
    @Nullable val profitCalcType: String?,
    @Nullable val profitValue: BigDecimal?,
    val items: List<UpdateQuoteItem>,
)

@Serdeable
data class UpdateQuoteItem(
    @Nullable val id: Long?,
    @Nullable val description: String?,
    val quantity: BigDecimal,
    @Nullable val unitPrice: BigDecimal?,
    @Nullable val discount: BigDecimal?,
    @Nullable val productId: Long?,
    @Nullable val uom: String?,
)

@Serdeable
data class PreviewRequest(val customerId: Long?, val lines: List<QuoteLineRequest>)

@Serdeable
data class IssueRequest(val customerId: Long?, val lines: List<QuoteLineRequest>, val expectedTotal: BigDecimal)

/**
 * Implementacion HTTP de [ErpClient].
 *
 * TODO(rutas por validar): ninguna de las rutas de abajo esta confirmada
 * contra maya-erp-services. Estan aisladas aqui justamente para que ajustarlas
 * sea tocar este archivo y nada mas.
 */
@Singleton
open class ErpHttpClient(
    private val provider: ErpHttpClientProvider,
    private val tokens: ErpTokenService,
    private val config: ErpConfiguration,
) : ErpClient {

    override fun searchProducts(
        tenantId: Long,
        conversationId: Long,
        query: String,
        limit: Int,
    ): List<ProductSummary> {
        // Validada: ProductController → Page<ProductResponse>.
        val uri = UriBuilder.of("/api/products")
            .queryParam("search", query)
            .queryParam("size", limit)
            .build()
        return pagina(HttpRequest.GET<Any>(uri), tenantId, conversationId, ErpProduct::class.java)
            .map { ProductSummary(id = it.id, sku = it.sku, name = it.name, unitPrice = it.price, unit = it.unit) }
    }

    override fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo? {
        // Validada: StockController → List<StockRow>, una fila por sucursal/lote.
        val uri = UriBuilder.of("/api/stock").queryParam("productId", productId).build()
        val filas = lista(HttpRequest.GET<Any>(uri), tenantId, conversationId, ErpStockRow::class.java)
        val total = filas.fold(BigDecimal.ZERO) { acc, f -> acc + (f.quantity ?: BigDecimal.ZERO) }
        val sucursales = filas.mapNotNull { it.branchName }.distinct()
        return StockInfo(productId, total, sucursales.singleOrNull())
    }

    override fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary? {
        // GET /api/clients/by-phone/{digitos}: el ERP compara solo digitos y
        // por el final, y devuelve 404 si no hay uno (o si hay varios).
        val digitos = phone.filter(Char::isDigit)
        if (digitos.length < 8) return null
        return uno(HttpRequest.GET<Any>("/api/clients/by-phone/$digitos"), tenantId, conversationId, CustomerSummary::class.java)
    }

    override fun createCustomer(tenantId: Long, conversationId: Long, name: String, nit: String?, phone: String?): CustomerSummary {
        val cuerpo = CreateCustomerBody(name = name, nit = nit, phone = phone)
        return uno(HttpRequest.POST("/api/clients", cuerpo), tenantId, conversationId, CustomerSummary::class.java)
            ?: throw ErpException("El ERP no devolvio el cliente creado")
    }

    override fun previewQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
    ): QuotePreview {
        // TODO(ruta por validar): POST /api/quotes/preview
        val req = HttpRequest.POST("/api/quotes/preview", PreviewRequest(customerId, lines))
        return uno(req, tenantId, conversationId, QuotePreview::class.java)
            ?: throw ErpException("El ERP no devolvio previsualizacion de la cotizacion")
    }

    override fun issueQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
        expectedTotal: BigDecimal,
        idempotencyKey: String,
    ): IssuedQuote {
        // TODO(ruta por validar): POST /api/quotes
        val req = HttpRequest.POST("/api/quotes", IssueRequest(customerId, lines, expectedTotal))
            // La llave viaja tambien al ERP: que este servicio no repita la
            // operacion no impide que un reintento de red la duplique alla.
            .header(CABECERA_IDEMPOTENCIA, idempotencyKey)
        return uno(req, tenantId, conversationId, IssuedQuote::class.java)
            ?: throw ErpException("El ERP no devolvio la cotizacion emitida")
    }

    override fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo? {
        // Validada: CompanyController → {id, name, nit, plan, status}.
        return uno(HttpRequest.GET<Any>("/api/company"), tenantId, conversationId, CompanyInfo::class.java)
    }

    // ── borrador de cotizacion (validado contra QuoteController/ClientController) ──

    /**
     * Busca clientes tolerando como escribe la gente.
     *
     * `GET /api/clients?search=` solo compara el NOMBRE como subcadena exacta,
     * asi que: (1) cualquier palabra con forma de NIT se prueba en
     * `/api/clients/by-nit/{nit}`, con y sin guion antes del verificador;
     * (2) el resto se busca por la palabra mas larga y se filtra a los que
     * contienen TODAS las palabras, en cualquier orden y sin tildes
     * ("fernando escobar" encuentra "ESCOBAR PÉREZ, Fernando").
     */
    override fun searchCustomers(tenantId: Long, conversationId: Long, query: String, limit: Int): List<CustomerSummary> {
        val palabras = query.trim().split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
        val nits = palabras.filter(::pareceNit)
        val nombre = palabras.filterNot(::pareceNit).filter { it.length >= 2 }

        val porNit = nits.asSequence()
            .flatMap { variantesNit(it).asSequence() }
            .distinct()
            .firstNotNullOfOrNull { nit ->
                uno(
                    HttpRequest.GET<Any>(UriBuilder.of("/api/clients/by-nit").path(nit).build()),
                    tenantId, conversationId, CustomerSummary::class.java,
                )
            }

        val porNombre = if (porNit != null || nombre.isEmpty()) emptyList() else {
            val ancla = nombre.maxBy { it.length }
            val uri = UriBuilder.of("/api/clients").queryParam("search", ancla).queryParam("size", 50).build()
            val claves = nombre.map(::normalizar)
            pagina(HttpRequest.GET<Any>(uri), tenantId, conversationId, CustomerSummary::class.java)
                .filter { c -> normalizar(c.name).let { n -> claves.all { n.contains(it) } } }
                .take(limit)
        }

        val resultado = listOfNotNull(porNit) + porNombre
        // Sin datos del cliente en el log: solo como se busco y cuanto salio.
        log.info(
            "clientes.buscar nits={} palabras={} por_nit={} encontrados={}",
            nits.size, nombre.size, porNit != null, resultado.size,
        )
        return resultado
    }

    /** Digitos (con a lo sumo un guion y K final) y al menos 6: 1234567-8, 12345678, 1234567K. */
    private fun pareceNit(t: String): Boolean {
        val limpio = t.replace("-", "")
        return limpio.length in 6..15 &&
            limpio.dropLast(1).all(Char::isDigit) &&
            (limpio.last().isDigit() || limpio.last().equals('K', ignoreCase = true))
    }

    private fun variantesNit(t: String): List<String> {
        val limpio = t.replace("-", "").uppercase()
        return listOf(t, limpio, limpio.dropLast(1) + "-" + limpio.last()).distinct()
    }

    private fun normalizar(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    override fun getCustomer(tenantId: Long, conversationId: Long, customerId: Long): CustomerSummary? =
        uno(HttpRequest.GET<Any>("/api/clients/$customerId"), tenantId, conversationId, CustomerSummary::class.java)

    override fun getProduct(tenantId: Long, conversationId: Long, productId: Long): ProductSummary? =
        uno(HttpRequest.GET<Any>("/api/products/$productId"), tenantId, conversationId, ErpProduct::class.java)
            ?.let { ProductSummary(id = it.id, sku = it.sku, name = it.name, unitPrice = it.price, unit = it.unit) }

    override fun createProspectQuote(
        tenantId: Long,
        conversationId: Long,
        customer: CustomerSummary,
        lines: List<QuoteLineWrite>,
        channel: String,
        conversationRef: String,
    ): ErpQuote {
        val cuerpo = CreateQuoteBody(
            partyType = "client",
            clientId = customer.id,
            clientName = customer.name,
            clientNit = customer.nit,
            clientEmail = customer.email,
            validUntil = LocalDate.now().plusDays(VIGENCIA_DIAS).toString(),
            createdBy = CREADO_POR,
            items = lines.map { CreateQuoteItem(it.productId, it.description, it.uom, it.quantity, it.unitPrice, it.discount) },
            // Con origin=agente el ERP la crea como 'prospecto'.
            origin = "agente",
            channel = channel,
            conversationRef = conversationRef,
        )
        return uno(HttpRequest.POST("/api/quotes", cuerpo), tenantId, conversationId, ErpQuote::class.java)
            ?: throw ErpException("El ERP no devolvio la cotizacion creada")
    }

    override fun getQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote? =
        uno(HttpRequest.GET<Any>("/api/quotes/$quoteId"), tenantId, conversationId, ErpQuote::class.java)

    override fun getQuotePdf(tenantId: Long, conversationId: Long, quoteId: Long): ByteArray? =
        // GET /api/quotes/{id}/pdf: el mismo PDF que el ERP adjunta al correo.
        ejecutar(HttpRequest.GET<Any>("/api/quotes/$quoteId/pdf"), tenantId, conversationId, PDF) { decorado ->
            provider.client().toBlocking().retrieve(decorado, ByteArray::class.java)
        }

    override fun addQuoteNote(tenantId: Long, conversationId: Long, quoteId: Long, note: String) {
        val req = HttpRequest.POST("/api/quotes/$quoteId/notes", NoteBody(note, CREADO_POR))
        ejecutar(req, tenantId, conversationId) { decorado ->
            // 204 sin cuerpo: se pide String solo para fijar el tipo de respuesta.
            provider.client().toBlocking().exchange(decorado, String::class.java)
        }
            ?: throw ErpException("La cotizacion $quoteId no existe en el ERP")
    }

    override fun updateQuoteLines(tenantId: Long, conversationId: Long, quote: ErpQuote, lines: List<QuoteLineWrite>): ErpQuote {
        val cuerpo = UpdateQuoteBody(
            // El PUT exige vigencia; se conserva la que tenga.
            validUntil = quote.validUntil ?: LocalDate.now().plusDays(VIGENCIA_DIAS).toString(),
            notes = quote.notes,
            profitCalcType = quote.profitCalcType,
            profitValue = quote.profitValue,
            items = lines.map { UpdateQuoteItem(it.id, it.description, it.quantity, it.unitPrice, it.discount, it.productId, it.uom) },
        )
        return uno(HttpRequest.PUT("/api/quotes/${quote.id}", cuerpo), tenantId, conversationId, ErpQuote::class.java)
            ?: throw ErpException("El ERP no devolvio la cotizacion actualizada")
    }

    // ── plomeria ────────────────────────────────────────────────────────────

    /**
     * Toda peticion lleva empresa y conversacion, aunque la URL ya las
     * implicara: es lo que permite al ERP auditar en nombre de quien actuo el
     * agente, y lo que hace evidente en el codigo si alguna llamada se fuera
     * sin empresa.
     */
    private fun <T : Any> decorar(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        acepta: MediaType = MediaType.APPLICATION_JSON_TYPE,
    ) = req
        .header(CABECERA_TENANT, tenantId.toString())
        .header(CABECERA_CONVERSACION, conversationId.toString())
        .header("X-Agent-Service", config.clientId ?: "agents-services")
        // Con usuario (widget del ERP) se consulta como el; si no, token de servicio.
        .bearerAuth(ErpUserToken.get() ?: tokens.currentToken())
        .contentType(MediaType.APPLICATION_JSON)
        .accept(acepta)

    private fun <T : Any, R : Any> uno(req: MutableHttpRequest<T>, tenantId: Long, conversationId: Long, tipo: Class<R>): R? =
        ejecutar(req, tenantId, conversationId) { decorado ->
            provider.client().toBlocking().retrieve(decorado, tipo)
        }

    private fun <T : Any, R : Any> pagina(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        tipo: Class<R>,
    ): List<R> =
        ejecutar(req, tenantId, conversationId) { decorado ->
            provider.client().toBlocking().retrieve(decorado, Argument.of(ErpPage::class.java, tipo))
        }?.content.orEmpty().filterIsInstance(tipo)

    private fun <T : Any, R : Any> lista(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        tipo: Class<R>,
    ): List<R> =
        ejecutar(req, tenantId, conversationId) { decorado ->
            provider.client().toBlocking().retrieve(decorado, Argument.listOf(tipo))
        } ?: emptyList()

    private fun <T : Any, R : Any> ejecutar(
        req: MutableHttpRequest<T>,
        tenantId: Long,
        conversationId: Long,
        acepta: MediaType = MediaType.APPLICATION_JSON_TYPE,
        llamada: (MutableHttpRequest<T>) -> R,
    ): R? {
        return try {
            llamada(decorar(req, tenantId, conversationId, acepta))
        } catch (e: HttpClientResponseException) {
            when (e.status) {
                HttpStatus.NOT_FOUND -> null
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> if (ErpUserToken.get() != null) {
                    // Con el token del usuario no hay nada que renovar: vencio
                    // su sesion o no tiene permiso para esto.
                    throw ErpException(
                        if (e.status == HttpStatus.FORBIDDEN) "El usuario no tiene permiso para ${req.path}"
                        else "La sesion del usuario en el ERP vencio",
                        e,
                    )
                } else if (e.status == HttpStatus.FORBIDDEN) {
                    throw ErpException("El servicio no tiene permiso para ${req.path}", e)
                } else {
                    // Un 401 casi siempre es el token vencido antes de tiempo.
                    // Se descarta y se reintenta UNA vez; si vuelve a fallar es
                    // credencial mala y repetir no arregla nada.
                    tokens.invalidate()
                    log.warn("el ERP devolvio 401; se renueva el token y se reintenta una vez")
                    try {
                        llamada(decorar(req, tenantId, conversationId, acepta))
                    } catch (segundo: HttpClientResponseException) {
                        throw ErpException("El ERP rechazo la credencial del servicio", segundo)
                    }
                }
                else -> throw ErpException("El ERP respondio ${e.status.code} en ${req.path}", e)
            }
        } catch (e: ErpException) {
            // Ya trae un mensaje claro (p. ej. falta de credencial): no se envuelve.
            throw e
        } catch (e: Exception) {
            // La causa va en el mensaje: sin ella, "no se pudo hablar" no
            // distingue un ERP caido de una respuesta que no se pudo leer.
            throw ErpException(
                "No se pudo hablar con el ERP en ${req.path}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(300)}",
                e,
            )
        }
    }

    companion object {
        const val CABECERA_TENANT = "X-Tenant-Id"
        const val CABECERA_CONVERSACION = "X-Conversation-Id"
        const val CABECERA_IDEMPOTENCIA = "Idempotency-Key"
        /** Vigencia por defecto de un prospecto; el vendedor la ajusta al revisar. */
        const val VIGENCIA_DIAS = 15L
        const val CREADO_POR = "Asistente Tino"
        private val PDF = MediaType("application/pdf")
        private val log = LoggerFactory.getLogger(ErpHttpClient::class.java)
    }
}
