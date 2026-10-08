package com.erp_maya.agent.notification

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

/** Avisos que el ERP manda al cliente a traves del canal de la conversacion. */
@Controller("/v1/notifications")
open class QuoteNotificationController(
    private val authenticator: ApiKeyAuthenticator,
    private val service: QuoteNotificationService,
) {

    @Post("/quote", consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    // Sube el PDF a Meta y habla con el ERP con clientes bloqueantes.
    @ExecuteOn(TaskExecutors.BLOCKING)
    open fun quote(
        @Header("X-Api-Key") apiKey: String?,
        @Body request: QuoteNotificationRequest,
    ): QuoteNotificationResponse {
        val caller = authenticator.authenticate(apiKey)
            ?: throw HttpStatusException(HttpStatus.UNAUTHORIZED, "Credencial invalida o ausente")
        if (!caller.allows(SCOPE)) {
            throw HttpStatusException(HttpStatus.FORBIDDEN, "El cliente ${caller.clientId} no puede enviar avisos")
        }
        return service.entregar(request)
    }

    private companion object {
        const val SCOPE = "notificaciones.write"
    }
}
