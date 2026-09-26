package com.erp_maya.agent.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Un turno, venga del canal que venga.
 *
 * Dos campos del cuerpo NO se usan aunque lleguen:
 *  - `scope`: los permisos salen de la credencial, no de lo que el cliente
 *    diga que tiene. Se acepta para no romper a quien ya lo manda, y se ignora.
 *  - cualquier empresa: el tenant sale de la cuenta del canal o de la
 *    credencial. Que el llamante escoja empresa seria el agujero completo.
 */
@Serdeable
data class TurnRequest(
    @field:NotBlank val channel: String,

    @JsonProperty("conversation_ref")
    @field:NotNull @field:Valid val conversationRef: ConversationRef,

    @Nullable val actor: Actor? = null,

    @field:NotNull @field:Valid val input: TurnInput,

    /** Lo que este canal sabe pintar hoy. Si no viene, manda lo configurado. */
    @Nullable val capabilities: ChannelCapabilities? = null,

    /** Aceptado y deliberadamente ignorado: ver el comentario de la clase. */
    @Nullable val scope: List<String>? = null,

    @JsonProperty("idempotency_key")
    @field:NotBlank val idempotencyKey: String,
)

@Serdeable
data class ConversationRef(
    /** Quien escribe: el numero en WhatsApp, el id de sesion en el ERP. */
    @JsonProperty("external_id") @field:NotBlank val externalId: String,
    /** La cuenta que recibe, que es la llave de `channels.account_ref`. */
    @field:NotBlank val account: String,
)

@Serdeable
data class Actor(
    val type: String,
    @JsonProperty("customer_id") @Nullable val customerId: Long? = null,
    @JsonProperty("user_id") @Nullable val userId: Long? = null,
)

@Serdeable
data class TurnInput(
    @field:NotBlank val type: String,
    @Nullable val text: String? = null,
)

@Serdeable
data class ChannelCapabilities(
    val buttons: Int = 0,
    val markdown: Boolean = false,
    @JsonProperty("max_chars") val maxChars: Int = 4096,
    val streaming: Boolean = false,
)
