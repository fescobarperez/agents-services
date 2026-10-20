package com.erp_maya.agent.quote

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.api.service.PanelCards
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.erp.domain.TemplateItemWrite
import com.erp_maya.agent.prompt.domain.FichaDiseno
import io.micronaut.serde.ObjectMapper
import com.erp_maya.agent.erp.domain.QuoteLineWrite
import com.erp_maya.agent.erp.domain.ChangeRequestWrite
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
    /** Botones que acompañan al mensaje (en WhatsApp, botones de respuesta). */
    val opciones: List<com.erp_maya.agent.api.dto.Choice> = emptyList(),
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

    /** Solo para leer la configuracion de las lineas de plantilla. */
    private val json: ObjectMapper by lazy { ObjectMapper.getDefault() }

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
        /** Medida que pide el cliente; null = la del catalogo. */
        medidas: Dimensions? = null,
    ): DraftStep {
        require(cantidad.signum() > 0) { "La cantidad debe ser mayor que cero" }
        val t = contexto.tenantId
        val producto = resolverProducto(t, conversationId, productId, sku)
            ?: throw ErpException("No encontre el producto ${sku ?: productId} en el catalogo")
        listOfNotNull(medidas?.height, medidas?.width, medidas?.depth).forEach {
            require(it.signum() > 0) { "Las medidas deben ser mayores que cero" }
        }
        // Igual a la del catalogo = medida estandar: no se manda como especial.
        val pedida = medidas?.takeUnless { it.vacia() || it.igualA(producto.dimensions) }
        val borrador = actual ?: QuoteDraft()

        // Hay una cotizacion recien cerrada y ninguna abierta: crear otra aqui
        // seria adivinar. Lo mas comun es que el cliente quiera cambiar ESA;
        // empezar una nueva tiene que ser una decision explicita.
        if (borrador.quoteId == null && borrador.ultimaCerradaId != null) {
            throw ErpException(
                "No hay cotización abierta: la ${borrador.ultimaCerrada} ya no se edita. Si el cliente quiere " +
                    "cambiar esa, usa cotizacion.reabrir o cotizacion.solicitar_cambio con quoteId " +
                    "${borrador.ultimaCerradaId}. Si es otra compra, confírmalo con él y usa cotizacion.nueva.",
            )
        }

        // Sin cliente: la linea espera y se pide el cliente.
        if (borrador.customerId == null) {
            val pendientes = sumar(borrador.pendientes, producto, cantidad, pedida)
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
            crear(contexto, conversationId, conversationRef, borrador, sumar(borrador.pendientes, producto, cantidad, pedida))
        } else {
            val vigente = erp.getQuote(t, conversationId, borrador.quoteId)
                ?: throw ErpException("La cotización ${borrador.quoteNumber} ya no existe en el ERP")
            val lineas = lineasCon(vigente, producto, cantidad, pedida)
            playbook.limites.lineasMaximas?.let { tope ->
                if (lineas.size > tope) {
                    throw ErpException("La cotización llegó al máximo de $tope líneas; un agente de ventas debe atender el resto")
                }
            }
            erp.updateQuoteLines(t, conversationId, vigente, lineas)
        }
        val enMedida = pedida?.let { " en medida especial ${it.texto(producto.dimensionUnit)}" } ?: ""
        val paso = listo(borrador, cotizacion, "Agregué ${producto.name}$enMedida a la cotización ${cotizacion.docNumber}.")
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
        val linea = vigente.items.firstOrNull { it.id == lineId } ?: throw ErpException("La línea $lineId no es de esta cotización")
        // Linea de plantilla: se recalcula en el ERP para que su materia prima siga a la cantidad.
        if (linea.templateId != null && cantidad.signum() > 0) {
            val cfg = configPlantilla(linea.templateConfig)
            val cotizacion = erp.recalcTemplateItem(
                contexto.tenantId, conversationId, quoteId, lineId,
                TemplateItemWrite(linea.templateId, cfg.params, cfg.choices, cfg.preset, cantidad),
            )
            return listo(borrador, cotizacion, "Actualicé la cotización ${cotizacion.docNumber}.")
        }

        val lineas = vigente.items.mapNotNull { i ->
            val q = if (i.id == lineId) cantidad else i.quantity
            if (q.signum() <= 0) null else conservar(i.id, i.description ?: i.productName, q, i.unitPrice, i.discount)
        }
        if (lineas.isEmpty()) throw ErpException("La cotización debe conservar al menos una línea")
        val cotizacion = erp.updateQuoteLines(contexto.tenantId, conversationId, vigente, lineas)
        return listo(borrador, cotizacion, "Actualicé la cotización ${cotizacion.docNumber}.")
    }

    // ── plantillas de calculo ───────────────────────────────────────────────

    /**
     * Lo que pide la plantilla del producto: medidas (params), opciones de
     * diseño con su argumento de venta y paquetes (basica, recomendada…). Sin
     * costos: son internos. Sin plantilla, el producto se cotiza con agregar.
     */
    open fun plantilla(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, productId: Long): DraftStep {
        val p = erp.templateByProduct(contexto.tenantId, conversationId, productId)
            ?: return DraftStep(
                borrador = actual,
                mensaje = "Ese producto no tiene plantilla de cálculo; cotízalo con cotizacion.agregar.",
                datos = mapOf("plantilla" to false),
            )
        return DraftStep(
            borrador = actual,
            mensaje = "Plantilla de ${p.productName ?: p.name}.",
            datos = vistaPlantilla(p),
        )
    }

    /**
     * Agrega el producto calculado con su plantilla: el ERP saca la materia
     * prima y el precio; aqui solo se mandan medidas, elecciones y paquete.
     * Necesita cliente (la linea vive en la cotizacion, no en espera).
     */
    open fun agregarPlantilla(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        productId: Long,
        params: Map<String, BigDecimal>,
        choices: Map<String, String>,
        preset: String?,
        cantidad: BigDecimal,
        playbook: Playbook = Playbook(),
    ): DraftStep {
        require(cantidad.signum() > 0) { "La cantidad debe ser mayor que cero" }
        val t = contexto.tenantId
        val borrador = actual ?: QuoteDraft()
        if (borrador.quoteId == null && borrador.ultimaCerradaId != null) {
            throw ErpException(
                "No hay cotización abierta: la ${borrador.ultimaCerrada} ya no se edita. Usa cotizacion.reabrir o " +
                    "cotizacion.solicitar_cambio con quoteId ${borrador.ultimaCerradaId}, o cotizacion.nueva si es otra compra.",
            )
        }
        if (borrador.customerId == null) {
            throw ErpException("Para cotizar con plantilla primero identifica al cliente (cotizacion.cliente o cotizacion.cliente_nuevo).")
        }
        val p = erp.templateByProduct(t, conversationId, productId)
            ?: throw ErpException("Ese producto no tiene plantilla de cálculo; cotízalo con cotizacion.agregar.")
        params.values.forEach { require(it.signum() > 0) { "Las medidas deben ser mayores que cero" } }
        val medidas = Dimensions.de(params["alto"], params["ancho"], params["profundidad"])
        DisenoService.validarAgregar(borrador, medidas)
        val linea = TemplateItemWrite(p.id, params, choices, preset?.takeIf { it.isNotBlank() }, cantidad)

        val cotizacion = if (borrador.quoteId == null) {
            // El ERP no crea una cotizacion vacia: nace con el producto como linea
            // provisional (y lo que estaba en espera), se agrega la de plantilla y
            // la provisional se quita.
            val producto = resolverProducto(t, conversationId, productId, null)
                ?: throw ErpException("No encontré el producto $productId en el catálogo")
            val creada = crear(contexto, conversationId, conversationRef, borrador, borrador.pendientes + DraftLine(
                producto.id, producto.sku, producto.name, BigDecimal.ONE, producto.unitPrice, producto.unit, null,
            ))
            val provisional = creada.items.lastOrNull { it.productId == producto.id && it.templateId == null }?.id
            val conPlantilla = erp.addTemplateItem(t, conversationId, creada.id, linea)
            val lineas = conPlantilla.items.filter { it.id != provisional }
                .map { conservar(it.id, it.description ?: it.productName, it.quantity, it.unitPrice, it.discount) }
            erp.updateQuoteLines(t, conversationId, conPlantilla, lineas)
        } else {
            val vigente = erp.getQuote(t, conversationId, borrador.quoteId)
                ?: throw ErpException("La cotización ${borrador.quoteNumber} ya no existe en el ERP")
            playbook.limites.lineasMaximas?.let { tope ->
                if (vigente.items.size + 1 > tope) {
                    throw ErpException("La cotización llegó al máximo de $tope líneas; un agente de ventas debe atender el resto")
                }
            }
            erp.addTemplateItem(t, conversationId, vigente.id, linea)
        }

        // La ficha de diseño queda con lo cotizado: el render dibuja exactamente eso.
        val nueva = cotizacion.items.lastOrNull { it.templateId == p.id }
        val etiquetas = p.options.mapNotNull { o ->
            val elegida = choices[o.code] ?: p.presets.firstOrNull { it.code == preset }?.choices?.get(o.code)
                ?: o.choices.firstOrNull { it.isDefault == true }?.code
            o.choices.firstOrNull { it.code == elegida }?.let { o.label to it.label }
        }.toMap()
        val ficha = (borrador.diseno ?: FichaDiseno()).let { f ->
            f.copy(
                tipo = p.productName ?: f.tipo,
                medidas = medidas ?: f.medidas,
                // Las medidas de la plantilla las dio el cliente (las de una foto sin confirmar ya se rechazaron arriba).
                medidasConfirmadas = if (medidas != null) true else f.medidasConfirmadas,
                origenMedidas = if (medidas != null) FichaDiseno.ORIGEN_CLIENTE else f.origenMedidas,
                detalles = f.detalles + etiquetas,
                productoId = p.productId,
                productoNombre = p.productName ?: f.productoNombre,
                renderPlantilla = configPlantilla(nueva?.templateConfig).renderText ?: f.renderPlantilla,
            )
        }
        val descripcion = nueva?.description ?: p.productName ?: p.name
        log.info("linea de plantilla {} en {} desde {}", p.id, cotizacion.docNumber, conversationRef)
        return listo(borrador.copy(diseno = ficha), cotizacion, "Agregué $descripcion a la cotización ${cotizacion.docNumber}.")
    }

    private fun vistaPlantilla(p: ProductTemplate): Map<String, Any?> = mapOf(
        "plantilla" to true,
        "productId" to p.productId,
        "producto" to (p.productName ?: p.name),
        "medidas" to p.params.map { x ->
            mapOf(
                "code" to x.code, "label" to x.label, "unit" to x.unit, "min" to x.min, "max" to x.max,
                "default" to x.defaultValue, "required" to (x.required ?: false),
            )
        },
        "opciones" to p.options.map { o ->
            mapOf(
                "code" to o.code, "label" to o.label, "required" to (o.required ?: false),
                "choices" to o.choices.map { c ->
                    mapOf("code" to c.code, "label" to c.label, "beneficio" to c.benefit, "default" to (c.isDefault ?: false))
                },
            )
        },
        "paquetes" to p.presets.map { mapOf("code" to it.code, "label" to it.label, "choices" to it.choices) },
    )

    /** La configuracion guardada en la linea: {version, params, choices, preset, renderText}. */
    private data class ConfigPlantilla(
        val params: Map<String, BigDecimal> = emptyMap(),
        val choices: Map<String, String> = emptyMap(),
        val preset: String? = null,
        val renderText: String? = null,
    )

    private fun configPlantilla(raw: String?): ConfigPlantilla {
        if (raw.isNullOrBlank()) return ConfigPlantilla()
        @Suppress("UNCHECKED_CAST")
        val m = runCatching { json.readValue(raw, Map::class.java) as? Map<String, Any?> }.getOrNull() ?: return ConfigPlantilla()
        val params = (m["params"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val n = (v as? Number)?.let { BigDecimal(it.toString()) } ?: (v as? String)?.toBigDecimalOrNull()
            if (k == null || n == null) null else k.toString() to n
        }.toMap()
        val choices = (m["choices"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            if (k == null || v == null) null else k.toString() to v.toString()
        }.toMap()
        return ConfigPlantilla(params, choices, m["preset"]?.toString(), m["renderText"]?.toString())
    }

    /** El cliente quiere otra cotizacion: se suelta la actual y se conserva el cliente. */
    open fun nueva(actual: QuoteDraft?): DraftStep {
        val anterior = actual?.quoteNumber
        return DraftStep(
            // Decision explicita de empezar otra: se olvida la cerrada para que
            // agregar pueda crear la nueva.
            borrador = (actual?.cerrar("el cliente pidió una nueva") ?: QuoteDraft())
                .copy(ultimaCerrada = null, ultimaCerradaId = null),
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

    /** Las cotizaciones del cliente y en que van, para responder "¿como va mi cotizacion?". */
    open fun listar(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?): DraftStep {
        val clienteId = actual?.customerId ?: throw ErpException("Primero identifica al cliente")
        val lista = erp.quotesByClient(contexto.tenantId, conversationId, clienteId)
        return DraftStep(
            borrador = actual,
            mensaje = if (lista.isEmpty()) "No tienes cotizaciones registradas." else "Tus cotizaciones:",
            datos = mapOf(
                "cotizaciones" to lista.map { q ->
                    mapOf(
                        "quoteId" to q.id,
                        "numero" to q.docNumber,
                        "estado" to estadoLegible(q.status, q.taken),
                        "total" to q.total?.let(PanelCards::monto),
                        "puede_reabrirse" to (q.status.equals(ESTADO_PROSPECTO, ignoreCase = true) && !q.taken),
                        "admite_solicitudes" to (q.taken && q.status?.lowercase() !in ESTADOS_FINALES),
                        "pendiente_decision" to q.status.equals("enviada", ignoreCase = true),
                    )
                },
            ),
        )
    }

    /**
     * El cliente quiere modificar una cotizacion que ya dio por terminada. El
     * ERP decide: si ningun vendedor la abrio, vuelve a 'abierta' y se retoma;
     * si ya la tomo uno, se rechaza y el cambio va como solicitud.
     */
    open fun reabrir(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        quoteId: Long,
        playbook: Playbook,
    ): DraftStep {
        if (!playbook.cotizacion.reapertura.permitida) {
            throw ErpException("Esta empresa no permite reabrir cotizaciones: registra el cambio con cotizacion.solicitar_cambio")
        }
        val borrador = actual ?: QuoteDraft()
        if (borrador.quoteId != null && borrador.quoteId != quoteId) {
            throw ErpException("Hay otra cotización abierta (${borrador.quoteNumber}); termínala o pregúntale al cliente cuál retomar")
        }
        val propia = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización $quoteId no existe")
        if (borrador.customerId != null && propia.clientId != null && propia.clientId != borrador.customerId) {
            throw ErpException("Esa cotización no es de este cliente")
        }
        val cotizacion = try {
            erp.reopenQuote(contexto.tenantId, conversationId, quoteId)
        } catch (e: ErpException) {
            throw ErpException("${e.message}. Usa cotizacion.solicitar_cambio para dejarle el pedido al vendedor.", e)
        }
        val paso = listo(
            borrador.copy(customerId = borrador.customerId ?: propia.clientId, customerName = borrador.customerName ?: propia.clientName, ultimaCerrada = null, ultimaCerradaId = null),
            cotizacion,
            "Reabrí la cotización ${cotizacion.docNumber}; puedes modificarla.",
        )
        return paso.copy(datos = paso.datos + ("reabierta" to true))
    }

    /** El cliente pide un cambio sobre una cotizacion que ya tiene un vendedor: queda como solicitud. */
    open fun solicitarCambio(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        quoteId: Long,
        tipo: String,
        productId: Long?,
        cantidad: BigDecimal?,
        descuento: BigDecimal?,
        detalle: String?,
    ): DraftStep {
        val cotizacion = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización $quoteId no existe")
        if (actual?.customerId != null && cotizacion.clientId != null && cotizacion.clientId != actual.customerId) {
            throw ErpException("Esa cotización no es de este cliente")
        }
        val producto = productId?.let { erp.getProduct(contexto.tenantId, conversationId, it) }
        val solicitud = erp.createChangeRequest(
            contexto.tenantId, conversationId, quoteId,
            ChangeRequestWrite(
                kind = tipo.trim().lowercase(),
                productId = productId,
                productName = producto?.name,
                quantity = cantidad,
                discountPct = descuento,
                detail = detalle,
                source = contexto.channel.kind,
                requestedBy = conversationRef,
            ),
        )
        return DraftStep(
            borrador = actual,
            mensaje = "Registré tu solicitud sobre la cotización ${cotizacion.docNumber}: ${solicitud.summary ?: tipo}.",
            datos = mapOf(
                "cotizacion" to cotizacion.docNumber,
                "solicitud" to solicitud.summary,
                "instruccion" to "Dile al cliente que su asesor la revisará y le confirmará por este medio. No prometas que se aplicará.",
            ),
        )
    }

    /**
     * Las solicitudes de cambio de una cotizacion y lo que respondio el
     * asesor. El agente explica con esto —motivo y mensaje reales—, nunca
     * con razones propias.
     */
    open fun solicitudes(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, quoteId: Long): DraftStep {
        val cotizacion = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización $quoteId no existe")
        if (actual?.customerId != null && cotizacion.clientId != null && cotizacion.clientId != actual.customerId) {
            throw ErpException("Esa cotización no es de este cliente")
        }
        val lista = erp.listChangeRequests(contexto.tenantId, conversationId, quoteId)
        return DraftStep(
            borrador = actual,
            mensaje = "Solicitudes de la cotización ${cotizacion.docNumber}:",
            datos = mapOf(
                "cotizacion" to cotizacion.docNumber,
                // Lo mas reciente primero: es de lo que suele hablar el cliente.
                "solicitudes" to lista.sortedByDescending { it.id }.map { s ->
                    mapOf(
                        "solicitudId" to s.id,
                        "tipo" to s.kind,
                        "pedido" to s.summary,
                        "estado" to s.status,
                        "motivo" to s.reasonLabel,
                        "respuesta_al_cliente" to s.clientMessage,
                        "consulta_de" to s.parentId,
                    )
                },
                "instruccion" to "Explica solo con 'motivo' y 'respuesta_al_cliente'; no inventes razones. Si el " +
                    "cliente quiere saber mas o no le queda claro, registra su pregunta con cotizacion.consultar. " +
                    "Si vuelve a pedir lo mismo que ya se rechazo sin dar algo nuevo, recuerdale el motivo antes de " +
                    "registrar otra solicitud; si trae una contraoferta (otro valor), es una solicitud nueva.",
            ),
        )
    }

    /** El cliente pregunta por la respuesta a una solicitud (p. ej. por que se rechazo). */
    open fun consultar(
        contexto: ExecutionContext,
        conversationId: Long,
        conversationRef: String,
        actual: QuoteDraft?,
        quoteId: Long,
        solicitudId: Long,
        pregunta: String,
        /** Lo que el modelo cree que es esa solicitud (agregar, descuento…): se coteja con el ERP. */
        tipo: String?,
    ): DraftStep {
        require(pregunta.isNotBlank()) { "Falta la pregunta del cliente" }
        val cotizacion = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización $quoteId no existe")
        if (actual?.customerId != null && cotizacion.clientId != null && cotizacion.clientId != actual.customerId) {
            throw ErpException("Esa cotización no es de este cliente")
        }
        // Que el id corresponda a lo que el cliente nombro: si el modelo tomo
        // la solicitud equivocada, se le devuelven las candidatas para que
        // corrija o pregunte, en vez de mandarle al asesor la consulta mal.
        val todas = erp.listChangeRequests(contexto.tenantId, conversationId, quoteId)
        val objetivo = todas.firstOrNull { it.id == solicitudId }
            ?: throw ErpException("La solicitud $solicitudId no es de esta cotización. ${candidatas(todas)}")
        if (!tipo.isNullOrBlank() && !objetivo.kind.equals(tipo.trim(), ignoreCase = true)) {
            throw ErpException(
                "La solicitud $solicitudId es «${objetivo.summary}», no de tipo $tipo. ${candidatas(todas)} " +
                    "Si no está claro a cuál se refiere el cliente, pregúntale.",
            )
        }
        erp.createChangeRequest(
            contexto.tenantId, conversationId, quoteId,
            ChangeRequestWrite(
                kind = "consulta",
                parentId = solicitudId,
                detail = pregunta.trim(),
                source = contexto.channel.kind,
                requestedBy = conversationRef,
            ),
        )
        return DraftStep(
            borrador = actual,
            mensaje = "Le pasé tu consulta sobre «${objetivo.summary}» al asesor de la cotización ${cotizacion.docNumber}.",
            datos = mapOf(
                "cotizacion" to cotizacion.docNumber,
                "instruccion" to "Dile al cliente que su asesor le respondera por este medio. No adelantes una respuesta.",
            ),
        )
    }

    private fun candidatas(todas: List<com.erp_maya.agent.erp.domain.ErpChangeRequest>) =
        "Respondidas: " + todas.filter { it.kind != "consulta" && it.status != "pendiente" }
            .joinToString("; ") { "[solicitudId ${it.id}] ${it.summary} (${it.status})" } + "."

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
            if (!cotizacion.status.equals(ESTADO_ABIERTA, ignoreCase = true)) {
                log.info("cotizacion {} paso a '{}' en el ERP: se cierra", borrador.quoteNumber, cotizacion.status)
                return borrador.cerrar(motivoCierre(cotizacion.status))
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
                "SIN_CONFIRMACION: el último mensaje del cliente no se reconoció como un sí claro. No es un error " +
                    "técnico: no lo digas así ni vuelvas a llamar esta herramienta en este turno. Sin repetir el " +
                    "resumen completo, pídele que responda solo «sí» para enviarla.",
            )
        }
        var cotizacion = erp.getQuote(contexto.tenantId, conversationId, quoteId)
            ?: throw ErpException("La cotización ${actual.quoteNumber} ya no existe en el ERP")
        // Superar el monto maximo ya no frena el envio: la cotizacion SIEMPRE
        // pasa a prospecto (para que el vendedor la vea en su lista) y el
        // cliente SIEMPRE recibe el PDF preliminar. El tope solo la marca para
        // revision prioritaria en la bitacora del ERP.
        val superaTope = playbook.limites.montoMaximo?.let { (cotizacion.total ?: BigDecimal.ZERO) > it } == true
        // Los datos que dio el cliente quedan en las notas: el vendedor los ve
        // y salen en el PDF.
        cotizacion = conNotas(contexto, conversationId, cotizacion, actual, playbook)
        // El cliente la dio por terminada: abierta → prospecto. Desde aqui la
        // revisa un vendedor y el agente ya no la edita.
        if (cotizacion.status.equals(ESTADO_ABIERTA, ignoreCase = true)) {
            cotizacion = erp.finalizeQuote(contexto.tenantId, conversationId, quoteId)
        }

        if (superaTope) {
            runCatching {
                erp.addQuoteNote(
                    contexto.tenantId, conversationId, quoteId,
                    "Revisión prioritaria: el total supera el monto máximo del asistente (Q ${playbook.limites.montoMaximo?.toPlainString()})",
                )
            }.onFailure { log.warn("no se anotó el tope en {}: {}", cotizacion.docNumber, it.message) }
        }

        val preliminar = cotizacion.status.equals(ESTADO_PROSPECTO, ignoreCase = true)
        return DraftStep(
            // Ya no esta abierta: el agente la suelta. Lo que pida despues va en
            // una nueva, o la reabre (cotizacion.reabrir) si ningun vendedor la tomo.
            borrador = actual.cerrar("enviada a revisión"),
            mensaje = "Envié el PDF de la cotización ${cotizacion.docNumber}.",
            datos = resumen(cotizacion) + mapOf(
                "preliminar" to preliminar,
                "instruccion" to if (preliminar) {
                    "El PDF va como cotizacion preliminar. Dile al cliente que un agente de ventas la revisara y " +
                        "aprobara, y que le confirmara precios y existencias." +
                        if (superaTope) " Por el monto, la revisión es prioritaria; no lo presentes como un rechazo." else ""
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
            lineas.map {
                QuoteLineWrite(productId = it.productId, quantity = it.quantity, unitPrice = it.unitPrice, uom = it.unit, dimensions = it.dimensions)
            },
            channel = contexto.channel.kind,
            conversationRef = conversationRef,
        )
        log.info("prospecto {} creado desde {} para cliente {}", cotizacion.docNumber, conversationRef, completo.id)
        return cotizacion
    }

    /**
     * Lineas actuales + el producto. Suma solo a una linea del mismo producto
     * y la MISMA medida: el mismo mueble en otra medida es otra linea.
     */
    private fun lineasCon(vigente: ErpQuote, producto: ProductSummary, cantidad: BigDecimal, pedida: Dimensions?): List<QuoteLineWrite> {
        val medida = pedida ?: producto.dimensions
        val existente = vigente.items.firstOrNull {
            it.productId == producto.id && (medida?.igualA(it.dimensions) ?: (it.dimensions == null || !it.customSize))
        }
        val actuales = vigente.items.map { i ->
            val q = if (i.id == existente?.id) i.quantity + cantidad else i.quantity
            conservar(i.id, i.description ?: i.productName, q, i.unitPrice, i.discount)
        }
        return if (existente != null) actuales
        else actuales + QuoteLineWrite(
            productId = producto.id, quantity = cantidad, unitPrice = producto.unitPrice, uom = producto.unit, dimensions = pedida,
        )
    }

    private fun conservar(id: Long, nombre: String?, q: BigDecimal, precio: BigDecimal?, descuento: BigDecimal?) =
        QuoteLineWrite(id = id, description = nombre, quantity = q, unitPrice = precio, discount = descuento)

    private fun sumar(pendientes: List<DraftLine>, p: ProductSummary, cantidad: BigDecimal, pedida: Dimensions? = null): List<DraftLine> {
        fun misma(l: DraftLine) = l.productId == p.id && (if (pedida == null) l.dimensions == null else pedida.igualA(l.dimensions))
        val ya = pendientes.firstOrNull(::misma)
        return if (ya == null) pendientes + DraftLine(p.id, p.sku, p.name, cantidad, p.unitPrice, p.unit, pedida)
        else pendientes.map { if (it === ya) it.copy(quantity = it.quantity + cantidad) else it }
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
        // La direccion de entrega va a su propio campo (sale en la tarjeta del
        // cliente del PDF); el resto de datos, a las notas.
        val direccion = borrador.datos[CLAVE_DIRECCION]?.takeIf { it.isNotBlank() } ?: cotizacion.deliveryAddress
        val lineasDatos = playbook.cotizacion.datosDelCliente()
            .filter { it.clave != CLAVE_DIRECCION }
            .mapNotNull { d -> borrador.datos[d.clave]?.let { "${d.etiqueta}: $it" } }
        val previas = cotizacion.notes?.substringBefore(ENCABEZADO_NOTAS)?.trimEnd().orEmpty()
        val notas = if (lineasDatos.isEmpty()) cotizacion.notes
        else listOf(previas, (listOf(ENCABEZADO_NOTAS) + lineasDatos).joinToString("\n"))
            .filter { it.isNotBlank() }.joinToString("\n\n")
        if (notas == cotizacion.notes && direccion == cotizacion.deliveryAddress) return cotizacion
        val lineas = cotizacion.items.map { conservar(it.id, it.description ?: it.productName, it.quantity, it.unitPrice, it.discount) }
        return erp.updateQuoteLines(
            contexto.tenantId, conversationId, cotizacion.copy(notes = notas, deliveryAddress = direccion), lineas,
        )
    }

    private fun ahora() = Instant.now().toEpochMilli()

    private fun motivoCierre(estado: String?) = when (estado?.lowercase()) {
        ESTADO_PROSPECTO -> "enviada a revisión"
        "abandonada" -> "abandonada por inactividad"
        null -> "ya no está disponible"
        else -> "en manos de un asesor ($estado)"
    }

    /** El estado como se lo dice el agente al cliente. */
    private fun estadoLegible(estado: String?, tomada: Boolean) = when (estado?.lowercase()) {
        ESTADO_ABIERTA -> "en armado"
        ESTADO_PROSPECTO -> if (tomada) "en revisión con un asesor" else "esperando revisión (aún se puede modificar)"
        "borrador" -> "en revisión con un asesor"
        "enviada" -> "enviada por el asesor"
        "aprobada" -> "aprobada"
        "rechazada" -> "rechazada"
        "abandonada" -> "abandonada"
        "convertida" -> "convertida en pedido"
        "vencida" -> "vencida"
        else -> estado ?: "desconocido"
    }

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
    private fun resumen(q: ErpQuote): Map<String, Any?> {
        // Precio global: el cliente conoce el alcance y el total, no el precio
        // de cada linea ni las lineas internas (materiales, mano de obra).
        val global = q.priceDisplay.equals("global", ignoreCase = true)
        return mapOf(
            "cotizacion" to q.docNumber,
            "lineas" to q.items.filter { !global || it.showToClient }.map { i ->
                mapOf(
                    "producto" to (i.productName ?: "Línea ${i.id}"),
                    "medidas" to i.dimensions?.texto(i.dimensionUnit),
                    "medida_especial" to i.customSize.takeIf { it },
                    "precio_medida" to when (i.sizePricing) {
                        "calculated" -> "calculado por medida (estimado; lo confirma el asesor)"
                        "review" -> "por cotizar: la medida se sale del rango y el asesor pondra el precio"
                        else -> null
                    },
                    "cantidad" to PanelCards.cantidad(i.quantity),
                    "precio_unitario" to if (global) null else i.unitPrice?.let(PanelCards::monto),
                    "total_linea" to if (global) null else i.lineTotal?.let(PanelCards::monto),
                )
            },
            "subtotal" to q.subtotal?.let(PanelCards::monto),
            "impuesto" to q.tax?.let(PanelCards::monto),
            "total" to q.total?.let(PanelCards::monto),
            "importante" to if (global) {
                "Esta cotización es a precio global: da solo el total del proyecto. No menciones precios por línea " +
                    "ni cómo se calcula (materiales, mano de obra, porcentajes)."
            } else "Estos son los datos reales del ERP. Repite lineas, cantidades y montos tal cual; no recalcules.",
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(QuoteDraftService::class.java)
        const val ESTADO_ABIERTA = "abierta"
        const val ESTADO_PROSPECTO = "prospecto"
        val ESTADOS_FINALES = setOf("aprobada", "rechazada", "cancelada", "anulada", "vencida", "convertida", "abandonada")
        const val ENCABEZADO_NOTAS = "Datos del cliente (asistente):"
        /** Dato del playbook que va al campo de direccion de entrega del ERP. */
        const val CLAVE_DIRECCION = "direccion_entrega"
    }
}
