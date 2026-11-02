package com.erp_maya.agent.cita

import com.erp_maya.agent.api.security.ApiKeyAuthenticator
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

/** Recordatorios de cita y de control que el ERP manda a los pacientes por WhatsApp. */
@Controller("/v1/notifications")
open class RecordatorioController(
    private val authenticator: ApiKeyAuthenticator,
    private val service: RecordatorioService,
    private val controles: ControlService,
) {

    @Post("/appointment", consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    // Habla con Meta y con la base con clientes bloqueantes.
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun appointment(
        @Header("X-Api-Key") apiKey: String?,
        @Body request: RecordatorioRequest,
    ): RecordatorioResponse {
        val caller = authenticator.authenticate(apiKey)
            ?: throw HttpStatusException(HttpStatus.UNAUTHORIZED, "Credencial invalida o ausente")
        if (!caller.allows(SCOPE)) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "El cliente ${caller.clientId} no puede enviar avisos")
        }
        return service.entregar(request)
    }

    /** Recordatorio de un control: como sigue el paciente y la oferta de agendar. */
    @Post("/follow-up", consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun followUp(
        @Header("X-Api-Key") apiKey: String?,
        @Body request: ControlRequest,
    ): RecordatorioResponse {
        val caller = authenticator.authenticate(apiKey)
            ?: throw HttpStatusException(HttpStatus.UNAUTHORIZED, "Credencial invalida o ausente")
        if (!caller.allows(SCOPE)) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "El cliente ${caller.clientId} no puede enviar avisos")
        }
        return controles.entregar(request)
    }

    private companion object {
        const val SCOPE = "notificaciones.write"
    }
}
