package com.erp_maya.agent.api.controller

import com.erp_maya.agent.api.dto.TurnError
import com.erp_maya.agent.api.dto.TurnRequest
import com.erp_maya.agent.api.dto.TurnResponse
import com.erp_maya.agent.api.security.ApiKeyAuthenticator
import com.erp_maya.agent.api.security.CallerCredentials
import com.erp_maya.agent.api.service.AgentTurnService
import com.erp_maya.agent.context.domain.AgentUnavailableException
import com.erp_maya.agent.conversation.domain.TurnInProgressException
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.http.exceptions.HttpStatusException
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * El unico endpoint de entrada: WhatsApp, el widget del ERP y, mas adelante,
 * terceros llaman aqui. El adaptador de cada canal vive fuera.
 *
 * Controlador delgado: autentica, delega y traduce el fallo a HTTP.
 */
@Controller("/v1/agent")
open class AgentTurnController(
    private val authenticator: ApiKeyAuthenticator,
    private val service: AgentTurnService,
) {

    @Post("/turn", consumes = [MediaType.APPLICATION_JSON], produces = [MediaType.APPLICATION_JSON])
    @Status(HttpStatus.OK)
    open fun turn(
        @Header(CABECERA_API_KEY) apiKey: String?,
        @Valid @Body request: TurnRequest,
    ): TurnResponse {
        val caller = authenticator.authenticate(apiKey)
            ?: throw HttpStatusException(HttpStatus.UNAUTHORIZED, "Credencial invalida o ausente")

        if (request.scope != null) {
            // Se registra para detectar integraciones que creen que asi piden
            // permisos; el valor no se usa nunca.
            log.debug("cliente={} mando scope en el cuerpo; se ignora", caller.clientId)
        }
        return service.handle(request, caller)
    }

    /**
     * Falla cerrado: sin agente vigente, empresa apagada o asignacion ambigua
     * no se responde con datos. 409 y no 5xx porque no es un error del
     * servidor: es configuracion que falta, y reintentar no la va a arreglar.
     */
    @io.micronaut.http.annotation.Error(exception = AgentUnavailableException::class)
    open fun sinAgente(e: AgentUnavailableException): HttpResponse<TurnError> {
        log.warn("turno no atendido: {} ({})", e.message, e.reason)
        return HttpResponse.status<TurnError>(HttpStatus.CONFLICT)
            .body(TurnError(e.message ?: "Turno no atendible", e.reason.name))
    }

    /**
     * La misma llave llego mientras el primer turno sigue vivo. 409 y no un
     * reintento: el canal debe esperar la respuesta del primero.
     */
    @io.micronaut.http.annotation.Error(exception = TurnInProgressException::class)
    open fun enProceso(e: TurnInProgressException): HttpResponse<TurnError> =
        HttpResponse.status<TurnError>(HttpStatus.CONFLICT)
            .body(TurnError(e.message ?: "Turno en proceso", "TURN_IN_PROGRESS"))

    private companion object {
        const val CABECERA_API_KEY = "X-Api-Key"
        private val log = LoggerFactory.getLogger(AgentTurnController::class.java)
    }
}
