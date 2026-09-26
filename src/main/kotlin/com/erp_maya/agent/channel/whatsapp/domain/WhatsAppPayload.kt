package com.erp_maya.agent.channel.whatsapp.domain

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/**
 * El cuerpo del webhook de WhatsApp Cloud API, en el subconjunto que este
 * servicio necesita. Meta manda bastante mas; lo que no se declara se ignora.
 */
@Serdeable
data class WhatsAppWebhook(
    @Nullable val entry: List<WaEntry>? = null,
)

@Serdeable
data class WaEntry(@Nullable val changes: List<WaChange>? = null)

@Serdeable
data class WaChange(@Nullable val value: WaValue? = null)

@Serdeable
data class WaValue(
    @Nullable val metadata: WaMetadata? = null,
    @Nullable val messages: List<WaMessage>? = null,
    @Nullable val contacts: List<WaContact>? = null,
    /** Acuses de entrega y lectura. Llegan mucho y no abren turno. */
    @Nullable val statuses: List<Map<String, Any?>>? = null,
)

@Serdeable
data class WaMetadata(
    @JsonProperty("display_phone_number") @Nullable val displayPhoneNumber: String? = null,
    /** Identifica la cuenta que recibe: es la llave de `channels.account_ref`. */
    @JsonProperty("phone_number_id") @Nullable val phoneNumberId: String? = null,
)

@Serdeable
data class WaContact(
    @JsonProperty("wa_id") @Nullable val waId: String? = null,
    @Nullable val profile: WaProfile? = null,
)

@Serdeable
data class WaProfile(@Nullable val name: String? = null)

@Serdeable
data class WaMessage(
    @Nullable val id: String? = null,
    @Nullable val from: String? = null,
    @Nullable val timestamp: String? = null,
    @Nullable val type: String? = null,
    @Nullable val text: WaText? = null,
    @Nullable val interactive: WaInteractive? = null,
)

@Serdeable
data class WaText(@Nullable val body: String? = null)

@Serdeable
data class WaInteractive(
    @Nullable val type: String? = null,
    @JsonProperty("button_reply") @Nullable val buttonReply: WaButtonReply? = null,
    @JsonProperty("list_reply") @Nullable val listReply: WaButtonReply? = null,
)

@Serdeable
data class WaButtonReply(@Nullable val id: String? = null, @Nullable val title: String? = null)

/**
 * Un mensaje entrante ya normalizado.
 *
 * `texto` sale del cuerpo o del id de la opcion pulsada: para la compuerta de
 * escritura, que el cliente pulse el boton "Confirmar" tiene que llegar como
 * el id `confirm`, no como la etiqueta que se le mostro.
 */
@Serdeable
data class MensajeEntrante(
    val wamid: String,
    val de: String,
    val cuenta: String,
    val texto: String?,
)
