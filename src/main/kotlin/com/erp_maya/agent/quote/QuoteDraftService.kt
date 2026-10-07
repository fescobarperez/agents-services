package com.erp_maya.agent.quote

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.service.PanelCards
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineWrite
import com.erp_maya.agent.prompt.domain.DraftLine
import com.erp_maya.agent.prompt.domain.QuoteDraft
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/** Lo que dejo un paso sobre el borrador: el borrador nuevo, un mensaje y tarjetas. */
data class DraftStep(
    val borrador: QuoteDraft?,
    val mensaje: String,
    val tarjetas: List<AgentEvent.Card> = emptyList(),
    /** Datos para devolverle al modelo cuando el paso vino de una herramienta. */
    val datos: Map<String, Any?> = emptyMap(),
)

/**
 * Arma la cotizacion de la conversacion: agregar lineas, fijar el cliente y
 * editar cantidades.
 *
 * Es el nivel de escritura "libre" del diseño: el documento queda como
 * PROSPECTO en el ERP y no sale al cliente, asi que no pasa por la compuerta.
 * Enviarlo a revision (prospecto → borrador) si pasara por ella.
 *
 * Los montos y el impuesto los calcula SIEMPRE el ERP: el panel pinta lo que
 * devolvio el PUT/POST, nunca una suma hecha aqui.
 */
@Singleton
open class QuoteDraftService(private val erp: ErpClient) {

    /** Agrega un producto por id (herramienta) o por SKU (boton del panel). */
    open fun agregar(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        productId: Long?,
        sku: String?,
        cantidad: BigDecimal,
    ): DraftStep {
        require(cantidad.signum() > 0) { "La cantidad debe ser mayor que cero" }
        val t = contexto.tenantId
        val producto = resolverProducto(t, conversationId, productId, sku)
            ?: throw ErpException("No encontre el producto ${sku ?: productId} en el catalogo")
        val borrador = actual ?: QuoteDraft()

        // Sin cliente: la linea espera y se pide el cliente.
        if (borrador.customerId == null) {
            val pendientes = sumar(borrador.pendientes, producto, cantidad)
            val nuevo = borrador.copy(pendientes = pendientes)
            return DraftStep(
                borrador = nuevo,
                mensaje = "Anoté ${cantidad.stripTrailingZeros().toPlainString()} × ${producto.name}. " +
                    "¿Para qué cliente es la cotización? Indique nombre o NIT.",
                datos = mapOf("estado" to "falta_cliente", "lineas_en_espera" to pendientes.size),
            )
        }

        val cotizacion = if (borrador.quoteId == null) {
            crear(contexto, conversationId, conversationRef, borrador, sumar(borrador.pendientes, producto, cantidad))
        } else {
            val vigente = erp.getQuote(t, conversationId, borrador.quoteId)
                ?: throw ErpException("La cotización ${borrador.quoteNumber} ya no existe en el ERP")
            erp.updateQuoteLines(t, conversationId, vigente, lineasCon(vigente, producto, cantidad))
        }
        return listo(borrador, cotizacion, "Agregué ${producto.name} a la cotización ${cotizacion.docNumber}.")
    }

    /** Fija el cliente; si habia lineas en espera, crea el prospecto. */
    open fun fijarCliente(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        customerId: Long,
    ): DraftStep {
        val cliente = erp.getCustomer(contexto.tenantId, conversationId, customerId)
            ?: throw ErpException("No encontre el cliente $customerId")
        val borrador = actual ?: QuoteDraft()
        if (borrador.quoteId != null && borrador.customerId != cliente.id) {
            // Cambiar el cliente de un documento ya creado es otra operacion
            // (y otro proyecto en el ERP); no se hace por debajo.
            throw ErpException("La cotización ${borrador.quoteNumber} ya es de ${borrador.customerName}")
        }
        val conCliente = borrador.copy(customerId = cliente.id, customerName = cliente.name)
        val tarjetaCliente = PanelCards.cliente(cliente)

        if (conCliente.pendientes.isEmpty()) {
            return DraftStep(
                borrador = conCliente,
                mensaje = "Cliente: ${cliente.name}.",
                tarjetas = listOf(tarjetaCliente),
                datos = mapOf("cliente" to cliente.name, "estado" to "sin_lineas"),
            )
        }
        val cotizacion = crear(contexto, conversationId, conversationRef, conCliente, conCliente.pendientes)
        val paso = listo(conCliente, cotizacion, "Creé la cotización ${cotizacion.docNumber} para ${cliente.name}.")
        return paso.copy(tarjetas = listOf(tarjetaCliente) + paso.tarjetas)
    }

    /** Cambia la cantidad de una linea; cantidad 0 la quita. */
    open fun editarLinea(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        lineId: Long,
        cantidad: BigDecimal,
    ): DraftStep {
        val borrador = actual ?: throw ErpException("No hay cotización en curso")
        val quoteId = borrador.quoteId ?: throw ErpException("La cotización aún no se ha creado")
        val vigente = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización ${borrador.quoteNumber} ya no existe en el ERP")
        if (vigente.items.none { it.id == lineId }) throw ErpException("La línea $lineId no es de esta cotización")

        val lineas = vigente.items.mapNotNull { i ->
            val q = if (i.id == lineId) cantidad else i.quantity
            if (q.signum() <= 0) null else conservar(i.id, i.productName, q, i.unitPrice, i.discount)
        }
        if (lineas.isEmpty()) throw ErpException("La cotización debe conservar al menos una línea")
        val cotizacion = erp.updateQuoteLines(contexto.tenantId, conversationId, vigente, lineas)
        return listo(borrador, cotizacion, "Actualicé la cotización ${cotizacion.docNumber}.")
    }

    // ── internos ────────────────────────────────────────────────────────────

    private fun crear(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        borrador: QuoteDraft,
        lineas: List<DraftLine>,
    ): ErpQuote {
        val cliente = CustomerSummary(id = borrador.customerId!!, name = borrador.customerName ?: "")
        val completo = erp.getCustomer(contexto.tenantId, conversationId, cliente.id) ?: cliente
        val cotizacion = erp.createProspectQuote(
            contexto.tenantId, conversationId, completo,
            lineas.map { QuoteLineWrite(productId = it.productId, quantity = it.quantity, unitPrice = it.unitPrice, uom = it.unit) },
            channel = contexto.channel.kind,
            conversationRef = conversationRef,
        )
        log.info("prospecto {} creado desde {} para cliente {}", cotizacion.docNumber, conversationRef, completo.id)
        return cotizacion
    }

    /** Lineas actuales + el producto (suma si ya estaba). */
    private fun lineasCon(vigente: ErpQuote, producto: ProductSummary, cantidad: BigDecimal): List<QuoteLineWrite> {
        val existente = vigente.items.firstOrNull { it.productId == producto.id }
        val actuales = vigente.items.map { i ->
            val q = if (i.id == existente?.id) i.quantity + cantidad else i.quantity
            conservar(i.id, i.productName, q, i.unitPrice, i.discount)
        }
        return if (existente != null) actuales
        else actuales + QuoteLineWrite(productId = producto.id, quantity = cantidad, unitPrice = producto.unitPrice, uom = producto.unit)
    }

    private fun conservar(id: Long, nombre: String?, q: BigDecimal, precio: BigDecimal?, descuento: BigDecimal?) =
        QuoteLineWrite(id = id, description = nombre, quantity = q, unitPrice = precio, discount = descuento)

    private fun sumar(pendientes: List<DraftLine>, p: ProductSummary, cantidad: BigDecimal): List<DraftLine> {
        val ya = pendientes.firstOrNull { it.productId == p.id }
        return if (ya == null) pendientes + DraftLine(p.id, p.sku, p.name, cantidad, p.unitPrice, p.unit)
        else pendientes.map { if (it.productId == p.id) it.copy(quantity = it.quantity + cantidad) else it }
    }

    private fun resolverProducto(t: Long, conversationId: Long, productId: Long?, sku: String?): ProductSummary? = when {
        productId != null -> erp.getProduct(t, conversationId, productId)
        // Respaldo: /api/products solo busca por NOMBRE, asi que por SKU puede
        // no encontrarlo. El panel manda el id; esto queda para clientes viejos.
        !sku.isNullOrBlank() -> erp.searchProducts(t, conversationId, sku, 10)
            .firstOrNull { it.sku.equals(sku, ignoreCase = true) }
        else -> null
    }

    private fun listo(borrador: QuoteDraft, cotizacion: ErpQuote, mensaje: String) = DraftStep(
        borrador = borrador.copy(quoteId = cotizacion.id, quoteNumber = cotizacion.docNumber, pendientes = emptyList()),
        mensaje = mensaje,
        tarjetas = listOf(PanelCards.cotizacion(cotizacion)),
        datos = mapOf(
            "cotizacion" to cotizacion.docNumber,
            "estado" to cotizacion.status,
            "lineas" to cotizacion.items.size,
            "total" to cotizacion.total?.toPlainString(),
        ),
    )

    private companion object {
        private val log = LoggerFactory.getLogger(QuoteDraftService::class.java)
    }
}
