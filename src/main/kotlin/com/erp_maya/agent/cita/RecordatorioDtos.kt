package com.erp_maya.agent.cita

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/**
 * POST /v1/notifications/appointment: lo manda el ERP (proceso de
 * recordatorios de Stackline Salud) cuando toca recordar una cita.
 * `notification_id` es «cita-{id}-{epoch del inicio}»: si la cita se
 * reprograma, cambia y el recordatorio nuevo sale.
 */
@Serdeable
data class RecordatorioRequest(
    @JsonProperty("notification_id") val notificationId: String,
    @JsonProperty("tenant_id") val tenantId: Long,
    val kind: String,
    val payload: RecordatorioPayload,
)

/** Armado por el ERP: la fecha y la hora ya vienen en hora de Guatemala. */
@Serdeable
data class RecordatorioPayload(
    val appointmentId: Long,
    val patientId: Long,
    val patientName: String,
    val phone: String,
    @Nullable val companyName: String? = null,
    @Nullable val professionalName: String? = null,
    @Nullable val typeName: String? = null,
    @Nullable val resourceName: String? = null,
    val startsAt: String,
    val date: String,
    val time: String,
    @Nullable val status: String? = null,
)

@Serdeable
data class RecordatorioResponse(val delivered: Boolean, @Nullable val reason: String? = null, @Nullable val via: String? = null)
