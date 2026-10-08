package com.erp_maya.agent.notification

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/** POST /v1/notifications/quote: lo manda el ERP (bandeja quote_notifications). */
@Serdeable
data class QuoteNotificationRequest(
    @JsonProperty("notification_id") val notificationId: Long,
    @JsonProperty("tenant_id") val tenantId: Long,
    val kind: String,
    @Nullable val channel: String? = null,
    @JsonProperty("conversation_ref") val conversationRef: String,
    val payload: ChangesAppliedPayload,
)

/** Armado por el ERP: las cifras y textos van tal cual al cliente. */
@Serdeable
data class ChangesAppliedPayload(
    val quoteId: Long,
    val docNumber: String,
    @Nullable val clientName: String? = null,
    @Nullable val total: String? = null,
    @Nullable val pdfPath: String? = null,
    val results: List<ChangeResult> = emptyList(),
    /** La cotizacion sigue enviada: se le vuelven a mostrar los botones de decision. */
    val askDecision: Boolean = false,
    /** Hubo cambios en las lineas sobre una enviada: volvio a borrador y llegara una version nueva. */
    val newVersionPending: Boolean = false,
    /** Version enviada a la que se atan los botones. */
    val version: Int = 0,
    /** Reenvio manual de la misma version desde el ERP. */
    val resend: Boolean = false,
)

@Serdeable
data class ChangeResult(
    @Nullable val requestId: Long? = null,
    val kind: String,
    val status: String,
    @Nullable val detail: String? = null,
    @Nullable val requested: String? = null,
    @Nullable val response: String? = null,
)

@Serdeable
data class QuoteNotificationResponse(val delivered: Boolean, val reason: String? = null)
