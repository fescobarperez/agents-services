package com.erp_maya.agent.prompt.domain

import com.erp_maya.agent.tools.domain.PendingWrite
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Lo que sobrevive entre turnos, en `conversations.state`.
 *
 * `summary` es prosa y puede envejecer; `entities` son los datos duros y son
 * los que mandan. Que el id de un cliente dependa de que el resumen lo
 * mencione seria construir sobre arena.
 */
@Serdeable
data class SessionState(
    val summary: String? = null,
    val summaryVersion: Int = 0,
    /**
     * Id del ultimo mensaje que ya entro al resumen. Lo que venga despues es
     * lo que falta resumir; de aqui salen los dos disparadores (inactividad y
     * respaldo por ventana).
     */
    val summarizedThrough: Long = 0,
    val entities: Map<String, String> = emptyMap(),
    val turn: Int = 0,
    /**
     * Escritura previsualizada y a la espera de confirmacion.
     *
     * Vive aqui y no en memoria porque la compuerta exige que el preview se
     * haya mostrado en un turno ANTERIOR: si se perdiera al reiniciar, una
     * emision podria pasar sin haberse enseñado nunca.
     */
    val pendingWrite: PendingWrite? = null,
    /** Cotizacion que se esta armando en esta conversacion (asistente del ERP). */
    val borrador: QuoteDraft? = null,
    /**
     * El ultimo aviso del asesor (solicitudes resueltas). Cuando el cliente
     * responde "¿y eso por que?", es casi siempre sobre esto: el modelo lo
     * necesita con los ids para no adivinar a cual solicitud se refiere.
     */
    @Nullable val avisoReciente: AvisoCambios? = null,
    /**
     * Las ultimas fotos del cliente, por referencia (media id de Meta) y con
     * su lectura en texto: el render se hace sobre una de ellas. Nunca los bytes.
     */
    val fotos: List<FotoCliente> = emptyList(),
)

@Serdeable
data class FotoCliente(
    val mediaId: String,
    @Nullable val mimeType: String? = null,
    /** wamid del mensaje que la trajo. */
    @Nullable val messageId: String? = null,
    /** La lectura en texto (lo que se ve, la zona marcada…). */
    @Nullable val lectura: String? = null,
    val recibidaMs: Long = 0,
) {
    companion object {
        /** Cuantas se recuerdan: las mas nuevas. */
        const val MAXIMO = 6
    }
}

@Serdeable
data class AvisoCambios(
    val quoteId: Long,
    val docNumber: String,
    /** epoch ms */
    val enviadoMs: Long,
    val items: List<AvisoItem> = emptyList(),
)

@Serdeable
data class AvisoItem(
    val solicitudId: Long,
    val estado: String,
    val detalle: String,
    @Nullable val respuesta: String? = null,
)

/**
 * El borrador de cotizacion de la conversacion.
 *
 * Mientras no hay cliente, las lineas esperan aqui (`pendientes`): el ERP
 * exige cliente para crear una cotizacion. En cuanto hay cliente se crea el
 * PROSPECTO en el ERP y desde ahi el documento de verdad es el del ERP
 * (`quoteId`); aqui solo queda la referencia.
 */
@Serdeable
data class QuoteDraft(
    val customerId: Long? = null,
    val customerName: String? = null,
    val quoteId: Long? = null,
    val quoteNumber: String? = null,
    val pendientes: List<DraftLine> = emptyList(),
    /**
     * WhatsApp: ya se busco al cliente por su numero. Se busca una sola vez
     * por conversacion; si no aparecio, el modelo pide nombre y NIT.
     */
    val telefonoRevisado: Boolean = false,
    /** Datos que pide el playbook (`cotizacion.dato`): clave → valor. */
    val datos: Map<String, String> = emptyMap(),
    /** Ultima vez que se toco la cotizacion (epoch ms); de aqui sale el cierre por inactividad. */
    @Nullable val actualizadaMs: Long? = null,
    /** La anterior que se cerro y por que, p. ej. "COT-000002 (PDF enviado)". Solo informativo. */
    @Nullable val ultimaCerrada: String? = null,
    /** Id en el ERP de esa cotizacion cerrada: para reabrirla o pedirle cambios sin adivinar. */
    @Nullable val ultimaCerradaId: Long? = null,
    /** Lo que se esta diseñando con el cliente (fotos, medidas, detalles). Vive con la cotizacion en curso. */
    @Nullable val diseno: FichaDiseno? = null,
) {
    /**
     * Cierra la cotizacion en curso: lo siguiente que pida el cliente va en
     * una nueva. Se conserva quien es el cliente.
     */
    fun cerrar(motivo: String): QuoteDraft = QuoteDraft(
        customerId = customerId,
        customerName = customerName,
        telefonoRevisado = telefonoRevisado,
        ultimaCerrada = quoteNumber?.let { "$it ($motivo)" } ?: ultimaCerrada,
        ultimaCerradaId = quoteId ?: ultimaCerradaId,
    )
}

/**
 * La ficha de diseño: lo que el cliente quiere, armado durante la
 * conversacion (fotos, medidas, detalles como aguas o cubierta).
 *
 * Es la fuente de verdad para cotizar lo que no sale tal cual del catalogo.
 * Las medidas estimadas de una foto NO se cotizan hasta que el cliente las
 * confirma; las que el cliente dice con sus palabras ya cuentan como
 * confirmadas.
 */
@Serdeable
data class FichaDiseno(
    /** Que es: pergola, deck, mueble de cocina… */
    @Nullable val tipo: String? = null,
    @Nullable val descripcion: String? = null,
    @Nullable val material: String? = null,
    @Nullable val acabado: String? = null,
    @Nullable val medidas: com.erp_maya.agent.erp.domain.Dimensions? = null,
    /** `foto` (estimadas) o `cliente` (las dijo el cliente). */
    @Nullable val origenMedidas: String? = null,
    val medidasConfirmadas: Boolean = false,
    /** Lo demas que se acordo: aguas, cubierta, deck, iluminacion… */
    val detalles: Map<String, String> = emptyMap(),
    /** Producto del catalogo (o propuesto) con que se va a cotizar. */
    @Nullable val productoId: Long? = null,
    @Nullable val productoNombre: String? = null,
    /** El producto lo propuso el asistente y espera revision (sin precio). */
    val productoPendiente: Boolean = false,
    /** Renders pedidos para esta cotizacion (cuentan contra el limite del playbook). */
    val renders: Int = 0,
    /** El ultimo render pedido (render_jobs.id) y la foto sobre la que se hizo. */
    @Nullable val ultimoRender: Long? = null,
    @Nullable val fotoRender: String? = null,
    /** El render que el cliente aprobo (render_jobs.id). */
    @Nullable val renderAprobado: Long? = null,
    /** Ya se guardo en la cotizacion del ERP (ficha + imagen). */
    val disenoAdjunto: Boolean = false,
) {
    /** Hay medidas sacadas de una foto que el cliente aun no confirmo. */
    fun medidasSinConfirmar(): Boolean = medidas != null && !medidasConfirmadas

    /** Texto para el asesor y para el prompt. */
    fun texto(): String = buildString {
        tipo?.let { append(it) }
        descripcion?.let { if (isNotEmpty()) append(": "); append(it) }
        material?.let { append(". Material: ").append(it) }
        acabado?.let { append(". Acabado: ").append(it) }
        medidas?.let {
            append(". Medidas: ").append(it.texto())
            append(if (medidasConfirmadas) " (confirmadas)" else " (estimadas, sin confirmar)")
        }
        if (detalles.isNotEmpty()) append(". ").append(detalles.entries.joinToString("; ") { "${it.key}: ${it.value}" })
    }.trim()

    companion object {
        const val ORIGEN_FOTO = "foto"
        const val ORIGEN_CLIENTE = "cliente"
    }
}

@Serdeable
data class DraftLine(
    val productId: Long,
    val sku: String,
    val name: String,
    val quantity: BigDecimal,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val unit: String? = null,
    /** Medida pedida si difiere de la del catalogo. */
    @Nullable val dimensions: com.erp_maya.agent.erp.domain.Dimensions? = null,
)
