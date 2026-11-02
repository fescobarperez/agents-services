package com.erp_maya.agent.cita

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/**
 * POST /v1/notifications/follow-up: lo manda el ERP unos dias antes de que
 * venza un control (Stackline Salud). `notification_id` es «control-{id}»:
 * cada control se recuerda una sola vez.
 */
@Serdeable
data class ControlRequest(
    @JsonProperty("notification_id") val notificationId: String,
    @JsonProperty("tenant_id") val tenantId: Long,
    val kind: String,
    val payload: ControlPayload,
)

/** Armado por el ERP: la fecha ya viene escrita. No trae datos clinicos (diagnostico, motivo). */
@Serdeable
data class ControlPayload(
    val followUpId: Long,
    val patientId: Long,
    val patientName: String,
    val phone: String,
    @Nullable val companyName: String? = null,
    @Nullable val professionalId: Long? = null,
    @Nullable val professionalName: String? = null,
    @Nullable val typeId: Long? = null,
    @Nullable val typeName: String? = null,
    val dueOn: String,
    val date: String,
)
