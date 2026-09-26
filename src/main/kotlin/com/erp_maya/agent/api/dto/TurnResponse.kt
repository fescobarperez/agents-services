package com.erp_maya.agent.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class TurnResponse(
    @JsonProperty("conversation_id") val conversationId: String,
    @JsonProperty("turn_id") val turnId: String,
    val events: List<AgentEvent>,
    @Nullable val state: TurnState? = null,
    @Nullable val usage: TokenUsage? = null,
)

/** Lo minimo que el canal necesita saber del hilo; el resto vive en la base. */
@Serdeable
data class TurnState(
    @JsonProperty("summary_version") val summaryVersion: Int,
)

@Serdeable
data class TokenUsage(
    val input: Int = 0,
    val cached: Int = 0,
    val output: Int = 0,
)

/**
 * Respuesta de un turno que no se puede atender. `reason` es para maquinas:
 * el adaptador decide con eso si escala a un humano o si reintenta.
 */
@Serdeable
data class TurnError(
    val error: String,
    val reason: String,
)
