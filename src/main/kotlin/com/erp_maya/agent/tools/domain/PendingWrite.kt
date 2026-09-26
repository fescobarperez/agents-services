package com.erp_maya.agent.tools.domain

import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Una escritura previsualizada y a la espera de confirmacion.
 *
 * Vive en el estado de la conversacion porque la compuerta exige que el
 * preview se haya mostrado en un turno ANTERIOR: si viviera en memoria, un
 * reinicio del servicio dejaria pasar una emision sin haberla enseñado.
 */
@Serdeable
data class PendingWrite(
    val tool: String,
    val total: BigDecimal,
    /** Turno en el que se mostro el preview. Debe ser menor que el actual. */
    val previewTurn: Int,
    /** Huella de los argumentos, para detectar que cambiaron tras el preview. */
    val argsHash: String,
)
