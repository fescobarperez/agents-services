package com.erp_maya.agent.erp.domain

import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/*
 * Stackline Salud tal como lo expone el ERP (/api/salud). Solo lo que el
 * asistente usa para agendar: nada del expediente clinico, que el token del
 * asistente tampoco puede leer.
 *
 * Las horas viajan como texto ISO-8601 (instantes); CitaRules las pasa a la
 * hora de Guatemala para hablarle al paciente.
 */

/** Tipo de cita (Consulta 30 min, Limpieza 45 min…). */
@Serdeable
data class SaludAppointmentType(
    val id: Long,
    val name: String,
    val durationMinutes: Int,
    @Nullable val price: java.math.BigDecimal? = null,
    val requiresResource: Boolean = false,
    val active: Boolean = true,
)

/** Usuario de la clinica con su perfil de profesional; `configured` false si no atiende. */
@Serdeable
data class SaludProfessional(
    val userId: Long,
    val name: String,
    val configured: Boolean = false,
    @Nullable val specialty: String? = null,
    val active: Boolean = false,
)

@Serdeable
data class SaludSlot(val startsAt: String, val endsAt: String, @Nullable val resourceId: Long? = null)

@Serdeable
data class SaludAvailability(
    val date: String,
    val professionalId: Long,
    val typeId: Long,
    val durationMinutes: Int,
    val slots: List<SaludSlot> = emptyList(),
)

@Serdeable
data class SaludAppointment(
    val id: Long,
    val patientId: Long,
    val patientName: String,
    @Nullable val patientPhone: String? = null,
    val professionalId: Long,
    val professionalName: String,
    val typeId: Long,
    val typeName: String,
    @Nullable val resourceId: Long? = null,
    @Nullable val resourceName: String? = null,
    val startsAt: String,
    val endsAt: String,
    val status: String,
    @Nullable val source: String? = null,
    @Nullable val reason: String? = null,
)

/** «Buscar antes de crear»: un cliente que coincide y si ya es paciente. */
@Serdeable
data class SaludCandidate(
    val clientId: Long,
    val name: String,
    @Nullable val phone: String? = null,
    val patient: Boolean = false,
    @Nullable val recordNumber: String? = null,
)

/** Lo minimo del expediente que devuelve el alta: el id del paciente es el del cliente. */
@Serdeable
data class SaludPatientDetail(val client: SaludPatientClient)

@Serdeable
data class SaludPatientClient(val id: Long, val name: String, @Nullable val phone: String? = null)

/** POST /api/salud/appointments (AgendaDtos.AppointmentRequest). */
@Serdeable
data class SaludAppointmentWrite(
    val patientId: Long,
    val professionalId: Long,
    val typeId: Long,
    @Nullable val resourceId: Long?,
    val startsAt: String,
    @Nullable val reason: String?,
    /** El ERP la deja «solicitada» siempre que agenda el asistente. */
    val status: String = "solicitada",
    /** El control que se agenda con esta cita (boton del recordatorio de control). */
    @Nullable val followUpId: Long? = null,
)

/** POST /api/salud/follow-ups/{id}/feedback (CajaDtos.FeedbackRequest): el telefono del chat, para comprobar que es el paciente. */
@Serdeable
data class SaludFeedbackWrite(val feeling: String, val phone: String, @Nullable val note: String? = null)

/** CajaDtos.FeedbackResult: con quien y de que tipo es el control, para ofrecer horarios. */
@Serdeable
data class SaludFeedbackResult(
    val id: Long,
    val patientId: Long,
    @Nullable val patientName: String? = null,
    @Nullable val professionalId: Long? = null,
    @Nullable val professionalName: String? = null,
    @Nullable val typeId: Long? = null,
    @Nullable val typeName: String? = null,
    val dueOn: String,
    val status: String,
    val feeling: String,
)

/** PUT /api/salud/appointments/{id}/schedule (AgendaDtos.RescheduleRequest). */
@Serdeable
data class SaludRescheduleWrite(
    val startsAt: String,
    @Nullable val professionalId: Long?,
    @Nullable val resourceId: Long?,
    val confirmed: Boolean = false,
)

@Serdeable
data class SaludStatusWrite(val status: String, @Nullable val reason: String?)

/** POST /api/salud/patients: alta unificada (cliente + paciente). */
@Serdeable
data class SaludPatientCreate(val client: SaludClientData, val patient: SaludPatientData)

@Serdeable
data class SaludPatientFromClient(val patient: SaludPatientData)

/** ClientDtos.Request del ERP: solo nombre y telefono; el resto, sus defaults. */
@Serdeable
data class SaludClientData(val name: String, @Nullable val phone: String?, val status: String = "active")

/**
 * PatientDtos.PatientData del ERP. Escribio por WhatsApp: acepta mensajes y
 * los recordatorios le llegan por ahi.
 */
@Serdeable
data class SaludPatientData(
    @Nullable val reminderChannel: String? = "whatsapp",
    @Nullable val acceptsMessages: Boolean? = true,
    @Nullable val referralSource: String? = "whatsapp",
)
