package com.erp_maya.agent.prompt.domain

import com.erp_maya.agent.tools.domain.PendingWrite
import io.micronaut.serde.annotation.Serdeable

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
)
