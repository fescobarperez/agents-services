package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.channel.web.WebCarts
import com.erp_maya.agent.channel.web.WebChatService
import com.erp_maya.agent.channel.web.WebMediaArchivo
import com.erp_maya.agent.channel.web.WebMediaRepository
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.channel.web.WebFirma
import com.erp_maya.agent.channel.web.WebOrigins
import com.erp_maya.agent.channel.web.WebRateLimiter
import com.erp_maya.agent.context.service.ExecutionContextResolver
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.erp.domain.CatalogProduct
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ProductImageData
import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.render.ColaRenders
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.time.temporal.ChronoUnit

/**
 * El planificador por presupuesto: la sesion del visitante, sus opciones y el
 * semaforo.
 *
 * Tiene dos entradas al mismo nucleo:
 *  - la publica (widget): valida llave, dominio y limites antes de todo;
 *  - la del agente (herramientas `planificador.*`, [PlannerTools]): el canal y
 *    la sesion ya vienen del turno, asi que se llama directo al nucleo.
 *
 * La empresa sale siempre del planificador, nunca de lo que mande el
 * navegador. Los precios son los del ERP (cargos, ganancia, IVA y redondeo
 * dentro); el visitante nunca ve subtotal, IVA ni costos.
 */
@Singleton
open class PlannerService(
    private val widgets: PlannerWidgetRepository,
    private val sesiones: PlannerSessionRepository,
    private val catalogo: PlannerCatalog,
    private val precios: PlannerPricing,
    private val resolver: ExecutionContextResolver,
    private val playbooks: PlaybookService,
    /** Foto del espacio y renders: viven en web_media con vencimiento, como en el chat web. */
    private val media: WebMediaRepository,
    private val renders: ColaRenders,
    private val conversaciones: ConversationService,
    @param:Value("\${planner.rate.sessions-per-ip:5}") private val sesionesPorIp: Int,
    @param:Value("\${planner.rate.options-per-session:6}") private val opcionesPorSesion: Int,
    @param:Value("\${planner.rate.options-per-ip:20}") private val opcionesPorIp: Int,
    @param:Value("\${planner.rate.options-per-widget:120}") private val opcionesPorWidget: Int,
    @param:Value("\${planner.rate.reads-per-session:60}") private val lecturasPorSesion: Int,
    @param:Value("\${planner.rate.photos-per-session:6}") private val fotosPorSesion: Int,
    @param:Value("\${planner.rate.renders-per-ip-day:15}") private val rendersPorIpDia: Int,
    @param:Value("\${planner.rate.renders-per-widget-day:300}") private val rendersPorWidgetDia: Int,
    /** Direccion publica del servicio: el enlace al render que va en el WhatsApp tiene que ser absoluto. */
    @param:Value("\${web.public-url:}") private val publicUrl: String,
    /** La firma «Con tecnología de Corvex» enlaza aqui (vacio: solo texto). */
    @param:Value("\${web.powered-by-url:}") private val poweredByUrl: String = "",
) {

    private val limites = WebRateLimiter()
    /**
     * Tope tecnico de renders por dia (IP y planificador). El de negocio es el
     * del playbook, por diseño; este solo frena a quien empieza diseños nuevos
     * para tener mas renders: cada uno cuesta. En memoria, como los limites del chat.
     */
    private val rendersDelDia = ConcurrentHashMap<String, Pair<LocalDate, Int>>()

    // ── entrada publica ─────────────────────────────────────────────────────

    /** Lo que pinta el widget al cargar. Tambien valida el dominio: si no pasa, no se muestra. */
    open fun config(llave: String, origin: String?): PlannerConfigView {
        val w = widget(llave, origin)
        return PlannerConfigView(
            title = w.title,
            lang = w.lang,
            accent = w.accentColor,
            currency = WebCarts.moneda(w.locale),
            fitThresholdPct = w.fitThresholdPct,
            rendersMax = limiteRenders(w),
            retentionDays = w.retentionDays,
            maxOptions = w.maxOptions,
            projectTypes = w.projectTypes.map { PlannerTypeRef(it.code, it.label) },
            categories = categoriasVisibles(w),
            chat = conAgente(w),
            maxMessageLength = LARGO_MENSAJE,
            features = PlannerFeaturesView(renders = limiteRenders(w) > 0, contact = w.contactWhatsapp != null),
            poweredByUrl = WebFirma.enlace(poweredByUrl),
        )
    }

    /** Lo que se puede elegir en un tipo de proyecto (medidas, opciones, paquetes), sin costos. */
    open fun tipo(llave: String, origin: String?, codigo: String): PlannerTypeView = tipoDe(widget(llave, origin), codigo)

    /** Los productos de una categoria del planificador, con su foto: el visitante elige uno. */
    open fun productos(llave: String, origin: String?, ip: String, categoryId: Long): List<PlannerProductView> {
        val w = widget(llave, origin)
        limitar("pl:ip:$ip", lecturasPorSesion, "Vas muy rápido.")
        if (categoryId !in w.categoryIds) throw WebException(404, "NOT_FOUND", "Esa categoría no está en el planificador")
        return conErp { catalogo.productosDe(w.tenantId, categoryId) }.map { vistaProducto(w, it) }
    }

    /**
     * La foto de un producto del planificador. La pide un `<img>` (sin Origin):
     * solo se sirven productos de sus categorias, y la version va en la URL
     * para poder guardarla en cache sin vencimiento.
     */
    open fun fotoProducto(llave: String, productId: Long, miniatura: Boolean): Pair<ProductImageData, Long> {
        if (!LLAVE.matches(llave)) throw WebException(404, "NOT_FOUND", "No encontrado")
        val w = widgets.porLlave(llave)?.takeIf { it.active } ?: throw WebException(404, "NOT_FOUND", "No encontrado")
        val p = productoDelCatalogo(w, productId)?.takeIf { it.imageVersion != null }
            ?: throw WebException(404, "NOT_FOUND", "Este producto no tiene foto")
        val foto = conErp { catalogo.foto(w.tenantId, productId, p.imageVersion!!, miniatura) }
            ?: throw WebException(404, "NOT_FOUND", "Este producto no tiene foto")
        return foto to p.imageVersion!!
    }

    /** Los productos de una categoria del planificador (para el agente: sin llave ni limites, ya los paso el turno). */
    open fun productosDe(w: PlannerWidget, categoryId: Long): List<PlannerProductView> {
        if (categoryId !in w.categoryIds) throw WebException(404, "NOT_FOUND", "Esa categoría no está en el planificador")
        return conErp { catalogo.productosDe(w.tenantId, categoryId) }.map { vistaProducto(w, it) }
    }

    /** Las categorias configuradas que existen en el ERP, en su orden. Sin ERP, ninguna (el widget sigue con los tipos fijos). */
    open fun categoriasVisibles(w: PlannerWidget): List<PlannerCategoryView> {
        if (w.categoryIds.isEmpty()) return emptyList()
        val todas = try {
            catalogo.categorias(w.tenantId).associateBy { it.id }
        } catch (e: ErpException) {
            log.warn("planificador {} sin categorias del ERP: {}", w.publicKey, e.message)
            return emptyList()
        }
        return w.categoryIds.mapNotNull { id -> todas[id]?.let { PlannerCategoryView(it.id, it.name) } }
    }

    /** El producto, si esta en alguna de las categorias del planificador (y sus subcategorias). */
    private fun productoDelCatalogo(w: PlannerWidget, productId: Long): CatalogProduct? =
        w.categoryIds.asSequence().mapNotNull { c -> conErp { catalogo.productosDe(w.tenantId, c) }.firstOrNull { it.id == productId } }.firstOrNull()

    private fun vistaProducto(w: PlannerWidget, p: CatalogProduct): PlannerProductView {
        val precio = p.price?.takeIf { it.signum() > 0 }
        return PlannerProductView(
            type = tipoDeProducto(p.id),
            id = p.id,
            name = p.name,
            summary = resumenDe(p.description),
            category = p.categoryName,
            price = precio,
            unit = p.unit,
            pricing = if (precio == null) PRICING_REVIEW else PRICING_OK,
            image = p.imageVersion?.let { "/${w.publicKey}/products/${p.id}/image?size=thumb&v=$it" },
        )
    }

    open fun crearSesion(llave: String, origin: String?, ip: String, body: PlannerSessionIn): PlannerStateView {
        val w = widget(llave, origin)
        limitar("ps:ip:$ip", sesionesPorIp, "Demasiadas sesiones nuevas.")
        val presupuesto = body.budget?.let { presupuesto(it) }
        val tipo = body.projectType?.let { tipoProyecto(w, it).code }
        val id = PlannerSessionRepository.nuevoId()
        sesiones.crear(id, w, presupuesto, tipo, vence(w))
        return estado(w, sesion(w, id))
    }

    open fun leer(llave: String, origin: String?, sid: String): PlannerStateView {
        val w = widget(llave, origin)
        limitar("pr:$sid", lecturasPorSesion, "Vas muy rápido.")
        return estado(w, sesion(w, sid))
    }

    /** Presupuesto, tipo y notas. Cambiar el presupuesto NO vuelve al ERP: el semaforo se recalcula con lo guardado. */
    open fun actualizar(llave: String, origin: String?, sid: String, body: PlannerUpdateIn): PlannerStateView {
        val w = widget(llave, origin)
        limitar("pr:$sid", lecturasPorSesion, "Vas muy rápido.")
        val s = sesion(w, sid)
        actualizarSesion(w, s, body)
        return estado(w, sesion(w, sid))
    }

    /**
     * Crea una opcion nueva (A, B, C…) sin tocar las anteriores, la valora en
     * el ERP junto con sus variantes y la deja activa.
     */
    open fun crearOpcion(llave: String, origin: String?, ip: String, sid: String, body: PlannerOptionIn): PlannerOptionCreated {
        val w = widget(llave, origin)
        limitarOpciones(llave, sid, ip)
        val letra = crearOpcion(w, sesion(w, sid), body)
        return PlannerOptionCreated(letra, estado(w, sesion(w, sid)))
    }

    open fun activar(llave: String, origin: String?, sid: String, letra: String): PlannerStateView {
        val w = widget(llave, origin)
        limitar("pr:$sid", lecturasPorSesion, "Vas muy rápido.")
        val s = sesion(w, sid)
        activar(w, s, letra)
        return estado(w, sesion(w, sid))
    }

    // ── nucleo (lo usan tambien las herramientas del agente) ───────────────

    /** El planificador de un canal, para el turno del agente (sin `Origin`: ya lo valido la entrada). */
    open fun widgetDeCanal(channelId: Long): PlannerWidget? = widgets.porCanal(channelId)

    /** La sesion del planificador que atiende esa conversacion, si sigue vigente. */
    open fun sesionDeConversacion(w: PlannerWidget, conversationId: Long): PlannerSessionRow? =
        sesiones.sesionDeConversacion(conversationId, w.channelId)?.let { sesiones.sesion(it, w.channelId) }

    open fun tipoDe(w: PlannerWidget, codigo: String): PlannerTypeView {
        val t = tipoProyecto(w, codigo)
        val plantilla = conErp { catalogo.producto(w.tenantId, t.productId) }.template
        return PlannerTypeView(
            code = t.code,
            label = t.label,
            params = plantilla?.params.orEmpty().map {
                PlannerParamView(it.code, it.label, it.unit, it.min, it.max, it.defaultValue, it.required == true)
            },
            options = plantilla?.options.orEmpty().map { o ->
                PlannerOptionDefView(o.code, o.label, o.required == true, o.choices.map {
                    PlannerChoiceView(it.code, it.label, it.isDefault == true, it.benefit)
                })
            },
            presets = plantilla?.presets.orEmpty().map { PlannerTypeRef(it.code, it.label) },
        )
    }

    /** La plantilla del tipo (o null si se cotiza por precio de lista). */
    open fun plantillaDe(w: PlannerWidget, codigo: String): ProductTemplate? =
        conErp { catalogo.producto(w.tenantId, tipoProyecto(w, codigo).productId) }.template

    open fun actualizarSesion(w: PlannerWidget, s: PlannerSessionRow, body: PlannerUpdateIn) {
        val presupuesto = body.budget?.let { presupuesto(it) }
        val tipo = body.projectType?.let { tipoProyecto(w, it).code }
        val notas = body.notes?.let { notas(s.notes, it) }
        sesiones.actualizar(s, presupuesto, tipo, notas, vence(w))
        if (body.removePhoto == true && s.photoMediaId != null) sesiones.guardarFoto(s.id, null, vence(w))
    }

    /** Crea, valora y activa la opcion. Devuelve su letra. [WebException] con lo que este mal. */
    open fun crearOpcion(w: PlannerWidget, s: PlannerSessionRow, body: PlannerOptionIn): String {
        val actuales = sesiones.opciones(s.id)
        if (actuales.size >= w.maxOptions) throw maximo(w)
        val padre = body.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { l ->
            actuales.firstOrNull { it.letter == l } ?: throw WebException(404, "OPTION", "La opción $l no existe")
        }
        val tipo = tipoProyecto(w, body.projectType ?: padre?.projectType ?: s.projectType
            ?: throw WebException(400, "BAD_SPEC", "Elige el tipo de proyecto"))

        val (opcion, letra) = try {
            val producto = conErp { catalogo.producto(w.tenantId, tipo.productId) }
            val unidad = (producto.product.unit ?: tipo.unit).takeIf { producto.template == null }
            val cambios = OptionChanges(body.params, body.choices, body.preset, body.quantity)
            val spec = PlannerSpecs.combinar(
                tipo.productId, producto.template, padre?.takeIf { it.projectType == tipo.code }?.spec, cambios,
                porArea = PlannerSpecs.porArea(unidad),
            )
            PlannerSpecs.validar(spec, producto.template)
            val v = conErp { precios.valorar(w.tenantId, spec, producto) }
            val detalles = PlannerSpecs.detalles(spec, producto.template, padre?.details, unidad)
            val fila = PlannerOptionRow(
                letter = "",
                parentLetter = padre?.letter,
                projectType = tipo.code,
                title = PlannerSpecs.titulo(tipo, detalles, body.title),
                summary = PlannerSpecs.resumen(detalles),
                spec = spec,
                details = detalles,
                pricing = if (v.precio.review) PRICING_REVIEW else PRICING_OK,
                total = v.precio.total,
                subtotal = v.precio.subtotal,
                tax = v.precio.tax,
                variants = v.variantes,
                budgetAtCreation = s.budget,
            )
            fila to (sesiones.guardarOpcion(s.id, w.maxOptions, fila, vence(w)) ?: throw maximo(w))
        } catch (e: PlannerSpecException) {
            throw WebException(400, "BAD_SPEC", e.message)
        }
        log.info("planificador {}: opción {} creada (tipo={}, precio={})", w.publicKey, letra, opcion.projectType, opcion.pricing)
        return letra
    }

    open fun activar(w: PlannerWidget, s: PlannerSessionRow, letra: String) {
        val l = letra.trim().uppercase()
        if (!sesiones.activar(s.id, l, vence(w))) throw WebException(404, "OPTION", "La opción $l no existe")
    }

    /** Hubo actividad (p. ej. un mensaje): el diseño se guarda otros N dias desde ahora. */
    open fun tocar(w: PlannerWidget, s: PlannerSessionRow) = sesiones.tocar(s.id, vence(w))

    open fun estado(w: PlannerWidget, s: PlannerSessionRow): PlannerStateView {
        val opciones = sesiones.opciones(s.id)
        val foto = s.photoMediaId?.let { id -> media.leerDatos(id)?.takeIf { it.widgetKey == w.publicKey } }
        return PlannerStateView(
            session = s.id,
            expiresAt = s.expiresAt.toString(),
            currency = WebCarts.moneda(w.locale),
            budget = s.budget,
            projectType = s.projectType,
            notes = s.notes.map { (k, v) -> PlannerNoteView(k, v) },
            activeOption = s.activeLetter,
            maxOptions = w.maxOptions,
            renders = PlannerRendersView(opciones.count { it.renderStatus == RENDER_EN_CAMINO || it.renderStatus == RENDER_LISTO }, limiteRenders(w)),
            // Vencida en web_media, ya no se muestra (y el render sale sin foto).
            photo = foto?.let { PlannerPhotoView(ruta(w.publicKey, it.id), it.fileName) },
            contact = contacto(w, s, opciones),
            options = opciones.map { o ->
                vista(o, s.budget, w.fitThresholdPct).copy(render = o.renderStatus?.let { st ->
                    PlannerRenderView(st, o.renderMediaId?.takeIf { st == RENDER_LISTO }?.let { ruta(w.publicKey, it) })
                })
            },
        )
    }

    open fun opciones(s: PlannerSessionRow): List<PlannerOptionRow> = sesiones.opciones(s.id)

    // ── foto del espacio y renders ──────────────────────────────────────────

    /**
     * La foto del espacio: base del render (opcional). Solo fotos (por su
     * contenido, no por lo que diga el navegador), de hasta 5 MB, y solo si el
     * planificador hace renders. Reemplaza la anterior.
     */
    open fun subirFoto(llave: String, origin: String?, ip: String, sid: String, body: PlannerPhotoIn): PlannerStateView {
        val w = widget(llave, origin)
        val s = sesion(w, sid)
        if (limiteRenders(w) <= 0) throw WebException(409, "RENDERS_OFF", "Este planificador no hace renders.")
        limitar("pf:${s.id}", fotosPorSesion, "Demasiadas fotos.")
        limitar("pf:ip:$ip", fotosPorSesion * 3, "Demasiadas fotos.")
        val crudo = body.data?.substringAfter("base64,")?.trim().orEmpty()
        if (crudo.isEmpty()) throw WebException(400, "EMPTY", "Falta la foto")
        if (crudo.length > WebChatService.MAX_FOTO_BYTES * 4 / 3 + 8) throw WebException(413, "TOO_LARGE", "La foto pesa más de 5 MB")
        val bytes = runCatching { Base64.getDecoder().decode(crudo) }.getOrNull()
            ?: throw WebException(400, "BAD_IMAGE", "No pude leer la foto")
        if (bytes.size > WebChatService.MAX_FOTO_BYTES) throw WebException(413, "TOO_LARGE", "La foto pesa más de 5 MB")
        val mime = WebChatService.tipoImagen(bytes) ?: throw WebException(415, "BAD_IMAGE", "Solo fotos JPG, PNG o WebP")
        val nombre = body.name?.trim()?.replace(Regex("[^\\p{L}\\p{N} ._-]"), "")?.take(80)?.takeIf { it.isNotBlank() }
        val id = media.guardar(llave, s.id, WebChatService.KIND_IMAGEN, mime, nombre, bytes)
        sesiones.guardarFoto(s.id, id, vence(w))
        return estado(w, sesion(w, sid))
    }

    /** «Ver render» de una opcion. Ademas del tope del diseño, un tope tecnico por dia (IP y planificador). */
    open fun pedirRender(llave: String, origin: String?, ip: String, sid: String, letra: String): PlannerStateView {
        val w = widget(llave, origin)
        val s = sesion(w, sid)
        limitar("pr:${s.id}", lecturasPorSesion, "Vas muy rápido.")
        pedirRender(w, s, letra, ip)
        return estado(w, sesion(w, sid))
    }

    /**
     * Reserva el render de la opcion y lo deja en la cola de renders. Misma regla
     * que el render de la cotizacion: `imagenes.generar_renders` y modelo de
     * imagenes del agente; tope `imagenes.renders_max` por diseño (los fallidos no
     * cuentan). Si la opcion ya tiene uno en camino o listo, no hace nada.
     * `ip` null = lo pide el agente (el visitante ya paso por el chat).
     */
    open fun pedirRender(w: PlannerWidget, s: PlannerSessionRow, letra: String, ip: String?) {
        val l = letra.trim().uppercase()
        val contexto = runCatching { resolver.resolve(cuenta(w.publicKey), w.tenantId) }.getOrNull()
            ?: throw WebException(409, "RENDERS_OFF", "Este planificador no hace renders en este momento.")
        val imagenes = playbooks.para(contexto).imagenes
        val modelo = contexto.imageModel
        if (!imagenes.generarRenders || modelo == null) throw WebException(409, "RENDERS_OFF", "Este planificador no hace renders.")
        val maximo = imagenes.rendersMax.coerceAtLeast(1)
        val o = sesiones.opciones(s.id).firstOrNull { it.letter == l } ?: throw WebException(404, "OPTION", "La opción $l no existe")
        if (o.renderStatus == RENDER_EN_CAMINO || o.renderStatus == RENDER_LISTO) return
        if (sesiones.rendersUsados(s.id) < maximo) {
            ip?.let { del("ip:$it", rendersPorIpDia) }
            del("w:${w.publicKey}", rendersPorWidgetDia)
        }
        val numero = sesiones.reservarRender(s.id, l, maximo)
        when (numero) {
            PlannerSessionRepository.RESERVA_NO_EXISTE -> throw WebException(404, "OPTION", "La opción $l no existe")
            PlannerSessionRepository.RESERVA_YA -> return
            PlannerSessionRepository.RESERVA_TOPE -> throw WebException(
                409, "RENDER_LIMIT",
                "Ya usaste los $maximo renders de este diseño. Puedes seguir ajustando tus opciones; un asesor ve los cambios.",
            )
        }
        try {
            val conversacion = conversaciones.abrirConversacion(contexto, s.id)
            // Una foto vencida en web_media no sirve de base: el render sale sin ella.
            val foto = s.photoMediaId?.takeIf { id -> media.leerDatos(id)?.widgetKey == w.publicKey }
            val tipo = tipoProyecto(w, o.projectType)
            val plantilla = conErp { catalogo.producto(w.tenantId, tipo.productId) }.template
            val trabajo = renders.encolar(
                conversationId = conversacion.id,
                tenantId = w.tenantId,
                accountRef = cuenta(w.publicKey),
                recipient = DESTINO_RENDER,
                modelId = modelo.id,
                baseMediaId = foto ?: SIN_FOTO,
                prompt = PlannerRender.instrucciones(tipo, o, plantilla, s.notes, foto != null),
                renderNumber = numero,
                renderMax = maximo,
            )
            sesiones.asignarTrabajo(s.id, l, trabajo)
            log.info("planificador {}: render de la opción {} encolado ({} de {}, trabajo {})", w.publicKey, l, numero, maximo, trabajo)
        } catch (e: Exception) {
            // No quedo en la cola: no cuenta contra el tope y se puede reintentar.
            sesiones.renderFallido(null, s.id, l)
            if (e is WebException) throw e
            log.error("planificador {}: no se pudo encolar el render de {}", w.publicKey, l, e)
            throw WebException(502, "RENDER", "No pude pedir el render. Intenta de nuevo en un momento.")
        }
    }

    /**
     * El enlace de WhatsApp con el diseño ya escrito: la opcion en pantalla, las
     * demas, sus notas y su ultimo render. Va en cada estado para que el widget
     * lo use como un enlace normal (un window.open tras una llamada lo bloquean
     * los navegadores). Null sin WhatsApp configurado o sin opciones.
     */
    private fun contacto(w: PlannerWidget, s: PlannerSessionRow, opciones: List<PlannerOptionRow>): PlannerContactView? {
        val numero = w.contactWhatsapp ?: return null
        if (opciones.isEmpty()) return null
        val elegida = opciones.firstOrNull { it.letter == s.activeLetter } ?: opciones.last()
        val render = PlannerContacto.ultimoRender(opciones)
        val base = publicUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
        val enlaceRender = render?.renderMediaId?.let { id -> base?.let { "$it/v1/planner${ruta(w.publicKey, id)}" } }
        val texto = PlannerContacto.mensaje(w, s, opciones, elegida, enlaceRender, render?.letter?.takeIf { enlaceRender != null }, WebCarts.moneda(w.locale))
        return PlannerContactView(PlannerContacto.enlace(numero, texto))
    }

    /** Toco «Contactar a un asesor»: queda el evento (que opcion y contra que presupuesto). El enlace ya lo tiene. */
    open fun contactar(llave: String, origin: String?, sid: String, body: PlannerContactIn) {
        val w = widget(llave, origin)
        val s = sesion(w, sid)
        limitar("pc:${s.id}", CONTACTOS_POR_SESION, "Vas muy rápido.")
        val o = sesiones.opciones(s.id).let { os -> os.firstOrNull { it.letter == body.option?.trim()?.uppercase() } ?: os.firstOrNull { it.letter == s.activeLetter } }
        val estado = o?.let { PlannerBudget.evaluar(it.total, it.pricing == PRICING_REVIEW, s.budget, w.fitThresholdPct).status }
        sesiones.contacto(s.id, mapOf("letter" to o?.letter, "total" to o?.total, "budget" to s.budget, "status" to estado))
        sesiones.tocar(s.id, vence(w))
    }

    /** Una foto o render del planificador. Lo pide un `<img>` (sin Origin): lo protege el id y que sea de este planificador. */
    open fun archivo(llave: String, id: String): WebMediaArchivo {
        if (!LLAVE.matches(llave)) throw WebException(404, "NOT_FOUND", "No encontrado")
        return media.leer(id)?.takeIf { it.widgetKey == llave }
            ?: throw WebException(404, "NOT_FOUND", "Esta imagen ya no está disponible")
    }

    /** Cuenta un render del dia para `clave`; 429 si ya llego al tope. */
    private fun del(clave: String, maximo: Int) {
        if (maximo <= 0) return
        val hoy = LocalDate.now()
        var paso = true
        rendersDelDia.compute(clave) { _, v ->
            val actual = if (v == null || v.first != hoy) hoy to 0 else v
            if (actual.second >= maximo) { paso = false; actual } else hoy to actual.second + 1
        }
        if (rendersDelDia.size > 20_000) rendersDelDia.entries.removeIf { it.value.first != hoy }
        if (!paso) throw WebException(429, "RATE_LIMIT", "Llegaste al máximo de renders por hoy. Vuelve mañana.")
    }

    // ── piezas ──────────────────────────────────────────────────────────────

    /** La entrada publica: llave, planificador encendido y dominio permitido. */
    open fun widget(llave: String, origin: String?): PlannerWidget {
        if (!LLAVE.matches(llave)) throw WebException(404, "NOT_FOUND", "Planificador no encontrado")
        val w = widgets.porLlave(llave) ?: throw WebException(404, "NOT_FOUND", "Planificador no encontrado")
        if (!w.active) throw WebException(409, "UNAVAILABLE", "El planificador no está disponible en este momento.")
        if (!WebOrigins.permitido(origin, w.allowedOrigins)) {
            log.warn("planificador {} usado desde un dominio no permitido: {}", llave, origin)
            throw WebException(403, "ORIGIN", "Este sitio no está autorizado para usar el planificador.")
        }
        return w
    }

    open fun sesion(w: PlannerWidget, sid: String): PlannerSessionRow {
        if (!PlannerSessionRepository.ID.matches(sid)) throw WebException(404, "SESSION", SIN_SESION)
        return sesiones.sesion(sid, w.channelId) ?: throw WebException(404, "SESSION", SIN_SESION)
    }

    open fun limitar(clave: String, maximo: Int, mensaje: String) {
        val espera = limites.intentar(clave, maximo)
        if (espera > 0) throw WebException(429, "RATE_LIMIT", "$mensaje Intenta de nuevo en $espera s.", espera)
    }

    /** Del mas fino al mas grueso: cada opcion es una llamada al ERP. */
    private fun limitarOpciones(llave: String, sid: String, ip: String) {
        limitar("po:$sid", opcionesPorSesion, "Vas muy rápido.")
        limitar("po:ip:$ip", opcionesPorIp, "Vas muy rápido.")
        limitar("po:w:$llave", opcionesPorWidget, "Hay mucha actividad.")
    }

    /**
     * Renders por diseño: la misma regla que en la cotizacion (RenderService).
     * Solo con `imagenes.generar_renders` en el playbook del agente asignado y
     * con modelo de imagenes; el tope es `imagenes.renders_max` y se cuenta por
     * sesion de planificacion (el prospecto actual). 0 = sin renders.
     */
    open fun limiteRenders(w: PlannerWidget): Int = runCatching {
        val contexto = resolver.resolve(cuenta(w.publicKey), w.tenantId)
        val imagenes = playbooks.para(contexto).imagenes
        if (imagenes.generarRenders && contexto.imageModel != null) imagenes.rendersMax.coerceAtLeast(1) else 0
    }.getOrDefault(0)

    /** ¿Hay un agente que converse? Sin el, el planificador funciona con chips y campos, sin chat. */
    private fun conAgente(w: PlannerWidget): Boolean =
        runCatching { resolver.resolve(cuenta(w.publicKey), w.tenantId) }.isSuccess

    /**
     * El tipo de proyecto de un codigo: uno fijo de la consola o, con `p{id}`,
     * el producto de una de las categorias del planificador (lo eligio de la lista).
     */
    open fun tipoProyecto(w: PlannerWidget, codigo: String): ProjectType {
        val c = codigo.trim()
        w.projectTypes.firstOrNull { it.code == c }?.let { return it }
        PRODUCTO.matchEntire(c)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
            productoDelCatalogo(w, id)?.let { p -> return ProjectType(c, p.name, p.id, p.unit, p.description) }
        }
        throw WebException(400, "BAD_SPEC", "Ese tipo de proyecto no está disponible")
    }

    private fun vence(w: PlannerWidget): Instant = Instant.now().plus(w.retentionDays.toLong().coerceAtLeast(1), ChronoUnit.DAYS)

    private fun maximo(w: PlannerWidget) =
        WebException(409, "MAX_OPTIONS", "Llegaste al máximo de ${w.maxOptions} opciones. Vuelve a una de las que ya tienes.")

    /** El ERP caido no es culpa del visitante: 502 y un mensaje para reintentar. */
    private fun <T> conErp(llamada: () -> T): T = try {
        llamada()
    } catch (e: ErpException) {
        log.warn("planificador sin precios del ERP: {}", e.message)
        throw WebException(502, "PRICING", "No pude calcular el precio. Intenta de nuevo en un momento.")
    }

    companion object {
        const val PRICING_OK = "priced"
        const val PRICING_REVIEW = "review"
        const val CONTACTOS_POR_SESION = 10
        const val RENDER_EN_CAMINO = "pending"
        const val RENDER_LISTO = "ready"
        const val RENDER_FALLIDO = "failed"
        /** El destinatario de un render del planificador en la cola (la sesion sale de la conversacion). */
        const val DESTINO_RENDER = "planner"
        /** Render sin foto del espacio: se genera desde cero. */
        const val SIN_FOTO = ""
        /** Ruta de una foto o render, relativa a /v1/planner: el widget le antepone su base. */
        fun ruta(llave: String, id: String) = "/$llave/media/$id"
        const val SIN_SESION = "Tu diseño ya no está disponible. Empieza uno nuevo."
        val LLAVE = Regex("^pk_[A-Za-z0-9]{20,40}$")
        /** Tope de NUMERIC(14,2), muy por encima de cualquier proyecto real. */
        val PRESUPUESTO_MAXIMO = BigDecimal("999999999999.99")
        const val MAX_NOTAS = 12
        const val LARGO_NOTA = 120
        /** Largo maximo de un mensaje del visitante. */
        const val LARGO_MENSAJE = 600
        /** Cuantas variantes de la opcion activa se ofrecen como cambios rapidos. */
        const val MAX_ALTERNATIVAS = 3

        /** La cuenta del canal: `planner:{llave}` (como `web:{llave}` en el chat). */
        fun cuenta(llave: String) = "planner:$llave"

        /** Codigo del tipo de un producto elegido de la lista: `p{productId}`. */
        val PRODUCTO = Regex("^p(\\d{1,18})$")
        fun tipoDeProducto(productId: Long) = "p$productId"

        /** El primer parrafo de la descripcion, recortado: lo que se lee en la lista. */
        fun resumenDe(descripcion: String?): String? =
            descripcion?.trim()?.substringBefore("\n\n")?.replace(Regex("\\s+"), " ")?.let {
                if (it.length <= LARGO_RESUMEN) it else it.take(LARGO_RESUMEN - 1).substringBeforeLast(' ') + "…"
            }?.takeIf { it.isNotEmpty() }

        private const val LARGO_RESUMEN = 160

        /** Sin minimo ni maximo de negocio: solo positivo, a centavos y que quepa en la columna. */
        fun presupuesto(monto: BigDecimal): BigDecimal {
            val m = monto.setScale(2, RoundingMode.HALF_UP)
            if (m.signum() <= 0) throw WebException(400, "BUDGET", "Escribe cuánto quieres invertir")
            if (m > PRESUPUESTO_MAXIMO) throw WebException(400, "BUDGET", "Ese monto es demasiado grande")
            return m
        }

        /** Las notas resultantes: las nuevas encima de las de antes; un valor vacio borra la nota. */
        fun notas(actuales: Map<String, String>, cambios: Map<String, String>): Map<String, String> {
            val out = LinkedHashMap(actuales)
            cambios.forEach { (k, v) ->
                val clave = k.trim()
                if (!PlannerSpecs.CODIGO.matches(clave)) throw WebException(400, "NOTES", "Nota inválida: «$k»")
                val valor = v.trim()
                if (valor.isEmpty()) out.remove(clave)
                else if (valor.length > LARGO_NOTA) throw WebException(400, "NOTES", "Máximo $LARGO_NOTA caracteres por nota")
                else out[clave] = valor
            }
            if (out.size > MAX_NOTAS) throw WebException(400, "NOTES", "Máximo $MAX_NOTAS notas")
            return out
        }

        /** La opcion como la ve el visitante, con el semaforo del presupuesto de ahora. */
        fun vista(o: PlannerOptionRow, presupuesto: BigDecimal?, umbral: BigDecimal): PlannerOptionView {
            val s = PlannerBudget.evaluar(o.total, o.pricing == PRICING_REVIEW, presupuesto, umbral)
            val sugerencia = if (s.status == PlannerBudget.OVER) PlannerBudget.sugerencia(o.variants, o.total, presupuesto) else null
            return PlannerOptionView(
                letter = o.letter,
                parent = o.parentLetter,
                projectType = o.projectType,
                title = o.title,
                summary = o.summary,
                details = o.details,
                pricing = o.pricing,
                total = o.total,
                budget = PlannerBudgetView(s.status, s.remaining, s.excess, s.ratio),
                suggestion = sugerencia?.let { PlannerSuggestionView(it.variant.label, it.variant.change, it.variant.total!!, it.fits) },
                // Cambios rapidos de un toque: las variantes ya valoradas (con precio confiable).
                alternatives = o.variants.filter { !it.review && it.total != null }.take(MAX_ALTERNATIVAS)
                    .map { PlannerAlternativeView(it.label, it.change, it.total!!) },
                createdAt = o.createdAt.toString(),
            )
        }

        private val log = LoggerFactory.getLogger(PlannerService::class.java)
    }
}
