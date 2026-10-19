package com.erp_maya.agent.playbook.domain

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Como vende el agente para una empresa: el negocio, los limites, los datos
 * que una cotizacion necesita y cuando se da por cerrada.
 *
 * Vive en `agent_playbooks.config` (JSONB). Todos los campos tienen valor por
 * defecto: un playbook incompleto —o ninguno— no rompe el turno, cae en lo
 * mas conservador.
 *
 * Lo que aqui se configura lo APLICA el codigo (checklist, compuerta de
 * envio, cierre). El prompt solo lo describe; si el modelo se lo salta, la
 * herramienta lo rechaza.
 */
@Serdeable
data class Playbook(
    @Nullable val negocio: Negocio? = null,
    /** Con quien habla el agente en cada tipo de canal: `cliente` o `vendedor`. */
    val audiencias: Map<String, String> = mapOf("whatsapp" to AUDIENCIA_CLIENTE),
    val limites: Limites = Limites(),
    val cotizacion: CotizacionConfig = CotizacionConfig(),
    /** Textos fijos de los avisos al cliente; las cifras las pone el ERP. */
    val mensajes: Mensajes = Mensajes(),
    /** Fotos del cliente. Apagado por defecto: leerlas cuesta una llamada extra al modelo. */
    val imagenes: ImagenesConfig = ImagenesConfig(),
) {
    fun audiencia(kindCanal: String): String = audiencias[kindCanal.lowercase()] ?: AUDIENCIA_CLIENTE

    companion object {
        const val AUDIENCIA_CLIENTE = "cliente"
        const val AUDIENCIA_VENDEDOR = "vendedor"
    }
}

/**
 * Fotos que manda el cliente.
 *
 * Con `aceptar = false` la foto no se lee: el agente le pide al cliente que
 * describa lo que necesita. Las que pasen de `max_por_turno` se ignoran y el
 * agente lo dice.
 */
@Serdeable
data class ImagenesConfig(
    val aceptar: Boolean = false,
    @JsonProperty("max_por_turno") val maxPorTurno: Int = 3,
    /**
     * El agente puede mostrar como quedaria lo cotizado editando la foto del
     * cliente. Necesita ademas un modelo de imagenes en el agente.
     */
    @JsonProperty("generar_renders") val generarRenders: Boolean = false,
    /** Renders por cotizacion. */
    @JsonProperty("renders_max") val rendersMax: Int = 3,
)

@Serdeable
data class Negocio(
    @Nullable val descripcion: String? = null,
    @Nullable val entregas: String? = null,
    @Nullable val pagos: String? = null,
    @Nullable val horario: String? = null,
    /** Cualquier otra cosa que el agente deba saber del negocio. */
    @Nullable val notas: String? = null,
)

@Serdeable
data class Limites(
    /** Por encima de esto la cotizacion no se envia sola: la revisa un vendedor. */
    @JsonProperty("monto_maximo") @Nullable val montoMaximo: BigDecimal? = null,
    @JsonProperty("lineas_maximas") @Nullable val lineasMaximas: Int? = null,
    @JsonProperty("puede_ofrecer_descuentos") val puedeOfrecerDescuentos: Boolean = false,
    /** Temas que el agente no resuelve: los deriva a una persona. */
    @JsonProperty("fuera_de_alcance") val fueraDeAlcance: List<String> = emptyList(),
    /**
     * Qué precios puede decir el agente al cliente: "unitario" (precios de
     * catálogo y por línea, como siempre) o "total" (solo el total de la
     * cotización que calcula el ERP, con ganancia, cargos y redondeo).
     */
    @JsonProperty("mostrar_precios") val mostrarPrecios: String = PRECIOS_UNITARIO,
) {
    /** El cliente solo debe oír el total de la cotización. */
    val soloTotal: Boolean get() = mostrarPrecios.equals(PRECIOS_TOTAL, ignoreCase = true)

    companion object {
        const val PRECIOS_UNITARIO = "unitario"
        const val PRECIOS_TOTAL = "total"
    }
}

@Serdeable
data class CotizacionConfig(
    @JsonProperty("datos_requeridos") val datosRequeridos: List<DatoRequerido> = listOf(
        DatoRequerido(DatoRequerido.CLIENTE, "Cliente identificado (nombre y NIT o CF)", DatoRequerido.ORIGEN_SISTEMA),
        DatoRequerido(DatoRequerido.LINEAS, "Productos y cantidades", DatoRequerido.ORIGEN_SISTEMA),
    ),
    val cierre: Cierre = Cierre(),
    val envio: Envio = Envio(),
    val reapertura: Reapertura = Reapertura(),
    /** Si el asistente puede crear productos que no estan en el catalogo. */
    @JsonProperty("productos_nuevos") val productosNuevos: ProductosNuevos = ProductosNuevos(),
) {
    /** Los que el cliente tiene que dar y se guardan con `cotizacion.dato`. */
    fun datosDelCliente(): List<DatoRequerido> = datosRequeridos.filter { it.origen != DatoRequerido.ORIGEN_SISTEMA }
}

/**
 * Un dato que la cotizacion necesita.
 *
 * `origen = sistema` lo resuelve el codigo (`cliente`: hay cliente fijado;
 * `lineas`: hay productos). `origen = cliente` lo pide el agente y se guarda
 * con `cotizacion.dato`; al enviar se escribe en las notas de la cotizacion.
 */
@Serdeable
data class DatoRequerido(
    val clave: String,
    val etiqueta: String,
    val origen: String = ORIGEN_CLIENTE,
    val opcional: Boolean = false,
    /** Pista para el agente: formato esperado, ejemplos. */
    @Nullable val descripcion: String? = null,
) {
    companion object {
        const val ORIGEN_SISTEMA = "sistema"
        const val ORIGEN_CLIENTE = "cliente"
        const val CLIENTE = "cliente"
        const val LINEAS = "lineas"
    }
}

/**
 * Productos que el cliente pide y no estan en el catalogo.
 *
 * Con `permitido` el asistente crea el producto SIN precio y pendiente de
 * revision; la linea queda por cotizar y el asesor le pone precio. Solo si
 * pertenece a una de las `lineas` (nombres de categorias del ERP); con la
 * lista vacia vale cualquier categoria que exista. Si no aplica, el agente le
 * dice al cliente `mensaje_fuera_de_linea`.
 */
@Serdeable
data class ProductosNuevos(
    val permitido: Boolean = false,
    val lineas: List<String> = emptyList(),
    @JsonProperty("mensaje_fuera_de_linea") @Nullable val mensajeFueraDeLinea: String? = null,
)

/** Cuando una cotizacion deja de estar en curso y lo siguiente va en otra. */
@Serdeable
data class Cierre(
    @JsonProperty("al_enviar_pdf") val alEnviarPdf: Boolean = true,
    /** Horas sin tocarla; null o 0 = nunca por tiempo. */
    @JsonProperty("inactividad_horas") @Nullable val inactividadHoras: Long? = 24,
    /** Un vendedor la movio en el ERP (ya no es prospecto): deja de ser del agente. */
    @JsonProperty("si_cambia_estado_en_erp") val siCambiaEstadoEnErp: Boolean = true,
)

@Serdeable
data class Envio(
    /** El cliente tiene que confirmar en el mismo turno en que se pide el envio. */
    @JsonProperty("requiere_confirmacion") val requiereConfirmacion: Boolean = true,
    @JsonProperty("marcar_preliminar") val marcarPreliminar: Boolean = true,
)

/**
 * Un prospecto vuelve a 'abierta' si el cliente lo pide y ningun vendedor lo
 * abrio (eso lo valida el ERP). Ya tomado, el cliente deja solicitudes de cambio.
 */
@Serdeable
data class Reapertura(
    val permitida: Boolean = true,
    /** El agente confirma con el cliente antes de reabrir. */
    @JsonProperty("requiere_confirmacion") val requiereConfirmacion: Boolean = true,
)

@Serdeable
data class Mensajes(
    @JsonProperty("cambios_aplicados") val cambiosAplicados: PlantillaCambios = PlantillaCambios(),
    @JsonProperty("cotizacion_enviada") val cotizacionEnviada: PlantillaEnviada = PlantillaEnviada(),
    val decision: PlantillaDecision = PlantillaDecision(),
)

/**
 * Textos de la decision del cliente sobre la cotizacion enviada. Variables:
 * {cliente}, {numero}, {total}, {version}; en `pie`, {vigencia}.
 *
 * La pregunta va al final del mensaje que lleva el PDF como encabezado; el pie
 * (max. 60 caracteres) y los titulos de los botones (max. 20) los recorta el
 * codigo si se pasan.
 */
@Serdeable
data class PlantillaDecision(
    val pregunta: String = "¿Cómo deseas continuar?",
    val pie: String = "Válida hasta el {vigencia}",
    @JsonProperty("boton_aprobar") val botonAprobar: String = "Aprobar",
    @JsonProperty("boton_comentar") val botonComentar: String = "Tengo comentarios",
    @JsonProperty("boton_rechazar") val botonRechazar: String = "Rechazar",
    @JsonProperty("boton_si_aprobar") val botonSiAprobar: String = "Sí, apruebo",
    @JsonProperty("boton_si_rechazar") val botonSiRechazar: String = "Sí, rechazar",
    @JsonProperty("boton_no") val botonNo: String = "No",
    @JsonProperty("confirmar_aprobar") val confirmarAprobar: String = "¿Confirmas que apruebas la cotización {numero} por {total}?",
    @JsonProperty("confirmar_rechazar") val confirmarRechazar: String = "¿Confirmas que no deseas continuar con la cotización {numero}?",
    val aprobada: String = "¡Gracias por preferirnos, {cliente}! Tu cotización {numero} quedó aprobada. " +
        "Un asesor se pondrá en contacto contigo para coordinar los siguientes pasos.",
    val rechazada: String = "Entendido, cerramos la cotización {numero}. Si quieres contarnos por qué, nos ayuda a " +
        "mejorar. ¡Gracias por considerarnos!",
    val comentar: String = "Cuéntame tus comentarios sobre la cotización {numero} y se los paso a tu asesor.",
    val cancelar: String = "Perfecto, no hacemos cambios. ¿Qué te parece la cotización {numero}?",
    @JsonProperty("version_anterior") val versionAnterior: String = "Ese mensaje es de una versión anterior. " +
        "La vigente es la cotización {numero} (versión {version}). ¿Qué te parece?",
    val vencida: String = "La cotización {numero} ya venció. Le aviso a tu asesor para que la renueve y te la envíe de nuevo.",
    @JsonProperty("nueva_version") val nuevaVersion: String = "Tu asesor está preparando una nueva versión de la " +
        "cotización {numero} con estos cambios. Te la enviaremos en cuanto esté lista.",
)

/**
 * Aviso cuando el vendedor envia la cotizacion oficial (estado «enviada») a
 * un cliente que la pidio por WhatsApp. Variables: {cliente}, {numero}, {total}.
 */
@Serdeable
data class PlantillaEnviada(
    val texto: String = "Hola {cliente}, tu asesor preparó tu cotización *{numero}*.\nTotal: *{total}*",
    /** Solo si el PDF sale como documento suelto (sin botones). */
    @JsonProperty("leyenda_pdf") val leyendaPdf: String = "Cotización oficial.",
    /** Cuando el asesor la reenvia (misma version). */
    val reenvio: String = "Hola {cliente}, te reenvío tu cotización *{numero}*.\nTotal: *{total}*",
)

/**
 * Aviso al cliente cuando el vendedor aplica sus solicitudes de cambio.
 * Variables: {cliente}, {numero}, {total}, {detalle}, {respuesta}.
 */
@Serdeable
data class PlantillaCambios(
    val encabezado: String = "Hola {cliente}, tu asesor revisó tu cotización {numero}:",
    val aplicada: String = "✅ {detalle}",
    val ajustada: String = "✏️ {detalle} — {respuesta}",
    val rechazada: String = "❌ {detalle} — {respuesta}",
    val respondida: String = "💬 {detalle}: {respuesta}",
    val cierre: String = "Nuevo total: {total}. Te adjunto la cotización actualizada.",
)
