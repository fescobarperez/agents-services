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
    )
}

@Serdeable
data class DraftLine(
    val productId: Long,
    val sku: String,
    val name: String,
    val quantity: BigDecimal,
    @Nullable val unitPrice: BigDecimal? = null,
    @Nullable val unit: String? = null,
)
