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
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.DraftLine
import com.erp_maya.agent.prompt.domain.QuoteDraft
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Lo que dejo un paso sobre el borrador: el borrador nuevo, un mensaje y tarjetas. */
data class DraftStep(
    val borrador: QuoteDraft?,
    val mensaje: String,
    val tarjetas: List<AgentEvent.Card> = emptyList(),
    /** Datos para devolverle al modelo cuando el paso vino de una herramienta. */
    val datos: Map<String, Any?> = emptyMap(),
    /** Archivos que el canal debe entregar al cliente (el PDF de la cotizacion). */
    val documentos: List<AgentEvent.Document> = emptyList(),
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
        playbook: Playbook = Playbook(),
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

        val nueva = borrador.quoteId == null
        val cotizacion = if (nueva) {
            crear(contexto, conversationId, conversationRef, borrador, sumar(borrador.pendientes, producto, cantidad))
        } else {
            val vigente = erp.getQuote(t, conversationId, borrador.quoteId)
                ?: throw ErpException("La cotización ${borrador.quoteNumber} ya no existe en el ERP")
            val lineas = lineasCon(vigente, producto, cantidad)
            playbook.limites.lineasMaximas?.let { tope ->
                if (lineas.size > tope) {
                    throw ErpException("La cotización llegó al máximo de $tope líneas; un agente de ventas debe atender el resto")
                }
            }
            erp.updateQuoteLines(t, conversationId, vigente, lineas)
        }
        val paso = listo(borrador, cotizacion, "Agregué ${producto.name} a la cotización ${cotizacion.docNumber}.")
        // Si la cotizacion ya existia, la cantidad se SUMA a la que tenia: el
        // modelo tiene que saberlo para no anunciar solo lo que pidio ahora.
        return if (nueva) paso else paso.copy(datos = paso.datos + ("se_sumo_a_cotizacion_existente" to true))
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

    /**
     * WhatsApp: identifica al cliente por el numero desde el que escribe. Si
     * el ERP lo reconoce, el borrador ya nace con cliente y no hay que
     * preguntarle quien es. Una sola vez por conversacion.
     */
    open fun identificarPorTelefono(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        telefono: String,
    ): QuoteDraft {
        val borrador = actual ?: QuoteDraft()
        if (borrador.customerId != null || borrador.telefonoRevisado) return borrador
        val cliente = runCatching { erp.findCustomerByPhone(contexto.tenantId, conversationId, telefono) }
            .onFailure { log.warn("no se pudo buscar el cliente por telefono: {}", it.message) }
            .getOrNull()
        if (cliente != null) log.info("conversacion {} identificada como cliente {}", conversationId, cliente.id)
        return borrador.copy(
            customerId = cliente?.id,
            customerName = cliente?.name,
            telefonoRevisado = true,
        )
    }

    /**
     * Alta de cliente desde la conversacion (WhatsApp, numero desconocido).
     * Si el NIT ya existe en el ERP se usa ese cliente en vez de duplicarlo.
     */
    open fun clienteNuevo(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        nombre: String,
        nit: String?,
        telefono: String?,
    ): DraftStep {
        require(nombre.isNotBlank()) { "Falta el nombre del cliente" }
        val t = contexto.tenantId
        val nitLimpio = nit?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "CF" && it != "C/F" }
        val existente = nitLimpio?.let { n -> erp.searchCustomers(t, conversationId, n).firstOrNull { it.nit.equals(n, ignoreCase = true) } }
        val cliente = existente ?: erp.createCustomer(t, conversationId, nombre.trim(), nitLimpio ?: "CF", telefono)
        if (existente == null) log.info("cliente {} creado desde la conversacion {}", cliente.id, conversationRef)
        return fijarCliente(contexto, conversationId, conversationRef, actual, cliente.id)
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

    /** El cliente quiere otra cotizacion: se suelta la actual y se conserva el cliente. */
    open fun nueva(actual: QuoteDraft?): DraftStep {
        val anterior = actual?.quoteNumber
        return DraftStep(
            borrador = actual?.cerrar("el cliente pidió una nueva") ?: QuoteDraft(),
            mensaje = anterior?.let { "Listo, empezamos una cotización nueva; la $it queda como estaba." }
                ?: "Listo, empezamos una cotización nueva.",
            datos = mapOf("anterior" to anterior, "estado" to "sin_lineas"),
        )
    }

    /**
     * Guarda un dato que pide el playbook (direccion de entrega, fecha…). Solo
     * acepta las claves que el playbook declara: el modelo no inventa campos.
     */
    open fun dato(actual: QuoteDraft?, clave: String, valor: String, playbook: Playbook): DraftStep {
        val permitido = playbook.cotizacion.datosDelCliente().firstOrNull { it.clave == clave }
            ?: throw IllegalArgumentException(
                "Dato '$clave' no existe. Validos: ${playbook.cotizacion.datosDelCliente().joinToString { it.clave }}",
            )
        require(valor.isNotBlank()) { "Falta el valor de ${permitido.etiqueta}" }
        val borrador = (actual ?: QuoteDraft()).let {
            it.copy(datos = it.datos + (clave to valor.trim()), actualizadaMs = ahora())
        }
        val faltan = QuoteChecklist.faltantes(borrador, playbook)
        return DraftStep(
            borrador = borrador,
            mensaje = "Anoté ${permitido.etiqueta.lowercase()}: ${valor.trim()}.",
            datos = mapOf(
                "guardado" to permitido.etiqueta,
                "faltan" to faltan.map { it.etiqueta },
            ),
        )
    }

    /**
     * Revisa al inicio del turno si la cotizacion en curso sigue siendo del
     * agente. Se cierra si un vendedor ya la movio en el ERP o si pasaron mas
     * horas de las que permite el playbook sin tocarla.
     */
    open fun revisarVigencia(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        playbook: Playbook,
    ): QuoteDraft? {
        val borrador = actual ?: return null
        val quoteId = borrador.quoteId ?: return borrador
        val cierre = playbook.cotizacion.cierre

        val horas = cierre.inactividadHoras?.takeIf { it > 0 }
        val tocada = borrador.actualizadaMs?.let(Instant::ofEpochMilli)
        if (horas != null && tocada != null && tocada.plus(Duration.ofHours(horas)).isBefore(Instant.now())) {
            log.info("cotizacion {} cerrada por inactividad ({} h)", borrador.quoteNumber, horas)
            return borrador.cerrar("sin actividad por más de $horas h")
        }

        if (cierre.siCambiaEstadoEnErp) {
            val cotizacion = runCatching { erp.getQuote(contexto.tenantId, conversationId, quoteId) }
                .onFailure { log.warn("no se pudo revisar la cotizacion {}: {}", quoteId, it.message) }
                .getOrElse { return borrador }
            if (cotizacion == null) {
                log.info("cotizacion {} ya no existe en el ERP: se cierra", borrador.quoteNumber)
                return borrador.cerrar("ya no existe en el ERP")
            }
            if (!cotizacion.status.equals(ESTADO_PROSPECTO, ignoreCase = true)) {
                log.info("cotizacion {} paso a '{}' en el ERP: se cierra", borrador.quoteNumber, cotizacion.status)
                return borrador.cerrar("un vendedor la pasó a ${cotizacion.status}")
            }
        }
        return borrador
    }

    /**
     * Prepara el envio del PDF de la cotizacion en curso. No lo envia: deja un
     * documento en el turno y el canal lo entrega (WhatsApp lo descarga del ERP
     * y lo adjunta). Sale como prospecto, marcado como preliminar.
     */
    open fun enviar(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        playbook: Playbook = Playbook(),
        confirmado: Boolean = true,
    ): DraftStep {
        val quoteId = actual?.quoteId
            ?: throw ErpException("Todavía no hay cotización creada en esta conversación")

        // Compuerta: lo que el playbook exige se valida aqui, no en el prompt.
        val faltan = QuoteChecklist.faltantes(actual, playbook)
        if (faltan.isNotEmpty()) {
            throw ErpException("Antes de enviarla falta: ${faltan.joinToString { it.etiqueta }}. Pídeselo al cliente.")
        }
        if (playbook.cotizacion.envio.requiereConfirmacion && !confirmado) {
            throw ErpException(
                "El cliente no confirmó el envío en este mensaje. Muéstrale el resumen y pregúntale si se la envías.",
            )
        }
        var cotizacion = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización ${actual.quoteNumber} ya no existe en el ERP")
        playbook.limites.montoMaximo?.let { tope ->
            if ((cotizacion.total ?: BigDecimal.ZERO) > tope) {
                throw ErpException(
                    "La cotización supera el monto que el asistente puede enviar. Dile al cliente que un agente de " +
                        "ventas la revisará y se la hará llegar.",
                )
            }
        }
        // Los datos que dio el cliente quedan en las notas: el vendedor los ve
        // y salen en el PDF.
        cotizacion = conNotas(contexto, conversationId, cotizacion, actual, playbook)

        val preliminar = cotizacion.status.equals(ESTADO_PROSPECTO, ignoreCase = true)
        return DraftStep(
            // Enviada, la cotizacion se cierra (si el playbook lo dice): lo que
            // pida despues va en una nueva y no se suma a la que ya tiene en PDF.
            borrador = if (playbook.cotizacion.cierre.alEnviarPdf) actual.cerrar("PDF enviado") else actual,
            mensaje = "Envié el PDF de la cotización ${cotizacion.docNumber}.",
            datos = resumen(cotizacion) + mapOf(
                "preliminar" to preliminar,
                "instruccion" to if (preliminar) {
                    "El PDF va como cotizacion preliminar. Dile al cliente que un agente de ventas la revisara y " +
                        "aprobara, y que le confirmara precios y existencias."
                } else null,
            ),
            documentos = listOf(
                AgentEvent.Document(
                    name = "Cotizacion-${cotizacion.docNumber ?: cotizacion.id}.pdf",
                    mediaType = "application/pdf",
                    url = "/api/quotes/${cotizacion.id}/pdf",
                ),
            ),
        )
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

    /** Escribe en las notas del ERP los datos que dio el cliente (si cambiaron). */
    private fun conNotas(
        contexto: ExecutionContext,
        conversationId: Long,
        cotizacion: ErpQuote,
        borrador: QuoteDraft,
        playbook: Playbook,
    ): ErpQuote {
        val lineasDatos = playbook.cotizacion.datosDelCliente()
            .mapNotNull { d -> borrador.datos[d.clave]?.let { "${d.etiqueta}: $it" } }
        if (lineasDatos.isEmpty()) return cotizacion
        val bloque = (listOf(ENCABEZADO_NOTAS) + lineasDatos).joinToString("\n")
        val previas = cotizacion.notes?.substringBefore(ENCABEZADO_NOTAS)?.trimEnd().orEmpty()
        val notas = listOf(previas, bloque).filter { it.isNotBlank() }.joinToString("\n\n")
        if (notas == cotizacion.notes) return cotizacion
        val lineas = cotizacion.items.map { conservar(it.id, it.productName, it.quantity, it.unitPrice, it.discount) }
        return erp.updateQuoteLines(contexto.tenantId, conversationId, cotizacion.copy(notes = notas), lineas)
    }

    private fun ahora() = Instant.now().toEpochMilli()

    private fun listo(borrador: QuoteDraft, cotizacion: ErpQuote, mensaje: String) = DraftStep(
        borrador = borrador.copy(
            quoteId = cotizacion.id,
            quoteNumber = cotizacion.docNumber,
            pendientes = emptyList(),
            actualizadaMs = ahora(),
        ),
        mensaje = mensaje,
        tarjetas = listOf(PanelCards.cotizacion(cotizacion)),
        datos = resumen(cotizacion),
    )

    /**
     * La cotizacion tal como quedo en el ERP, ya formateada. El modelo la
     * repite; no calcula: un total inventado por el modelo (impuesto mal
     * aplicado, lineas olvidadas) es peor que no dar total.
     */
    private fun resumen(q: ErpQuote): Map<String, Any?> = mapOf(
        "cotizacion" to q.docNumber,
        "lineas" to q.items.map { i ->
            mapOf(
                "producto" to (i.productName ?: "Línea ${i.id}"),
                "cantidad" to PanelCards.cantidad(i.quantity),
                "precio_unitario" to i.unitPrice?.let(PanelCards::monto),
                "total_linea" to i.lineTotal?.let(PanelCards::monto),
            )
        },
        "subtotal" to q.subtotal?.let(PanelCards::monto),
        "impuesto" to q.tax?.let(PanelCards::monto),
        "total" to q.total?.let(PanelCards::monto),
        "importante" to "Estos son los datos reales del ERP. Repite lineas, cantidades y montos tal cual; no recalcules.",
    )

    private companion object {
        private val log = LoggerFactory.getLogger(QuoteDraftService::class.java)
        const val ESTADO_PROSPECTO = "prospecto"
        const val ENCABEZADO_NOTAS = "Datos del cliente (asistente):"
    }
}
