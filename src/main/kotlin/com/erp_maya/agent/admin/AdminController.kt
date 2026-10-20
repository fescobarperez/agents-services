package com.erp_maya.agent.admin

import com.erp_maya.agent.admin.AdminGuard.Companion.API_KEY
import com.erp_maya.agent.admin.AdminGuard.Companion.OPERATOR
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

/**
 * API de administracion global de la IA: proveedores, modelos, identidad de
 * Tino y tipos de agente. Solo la llama stack-line-services (consola de
 * plataforma) con la llave `admin`; los roles de operador se aplican alla.
 */
@Controller("/v1/admin/ai", produces = [MediaType.APPLICATION_JSON])
@ExecuteOn(TaskExecutors.BLOCKING)
open class AdminController(
    private val guard: AdminGuard,
    private val service: AdminService,
) {

    @Get("/catalog")
    open fun catalog(@Header(API_KEY) apiKey: String?): Catalog {
        guard.autorizar(apiKey)
        return service.catalog()
    }

    // ── proveedores ─────────────────────────────────────────────────────────

    @Post("/providers", consumes = [MediaType.APPLICATION_JSON])
    open fun createProvider(@Header(API_KEY) apiKey: String?, @Body body: Map<String, Any?>): HttpResponse<ProviderView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createProvider(body))
    }

    @Patch("/providers/{id}", consumes = [MediaType.APPLICATION_JSON])
    open fun updateProvider(@Header(API_KEY) apiKey: String?, @PathVariable id: Long, @Body body: Map<String, Any?>): ProviderView {
        guard.autorizar(apiKey)
        return service.updateProvider(id, body)
    }

    // ── modelos ─────────────────────────────────────────────────────────────

    @Post("/models", consumes = [MediaType.APPLICATION_JSON])
    open fun createModel(@Header(API_KEY) apiKey: String?, @Body body: Map<String, Any?>): HttpResponse<ModelView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createModel(body))
    }

    @Patch("/models/{id}", consumes = [MediaType.APPLICATION_JSON])
    open fun updateModel(@Header(API_KEY) apiKey: String?, @PathVariable id: Long, @Body body: Map<String, Any?>): ModelView {
        guard.autorizar(apiKey)
        return service.updateModel(id, body)
    }

    // ── identidad de Tino ───────────────────────────────────────────────────

    @Get("/identity")
    open fun identity(@Header(API_KEY) apiKey: String?): IdentityView {
        guard.autorizar(apiKey)
        return service.identity()
    }

    @Post("/identity", consumes = [MediaType.APPLICATION_JSON])
    open fun createIdentity(@Header(API_KEY) apiKey: String?, @Header(OPERATOR) operator: String?, @Body body: Map<String, Any?>): HttpResponse<IdentityView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createIdentity(body, guard.operador(operator)))
    }

    @Post("/identity/{promptId}/activate")
    open fun activateIdentity(@Header(API_KEY) apiKey: String?, @PathVariable promptId: Long): IdentityView {
        guard.autorizar(apiKey)
        return service.activateIdentity(promptId)
    }

    // ── tipos de agente ─────────────────────────────────────────────────────

    @Get("/agents")
    open fun agents(@Header(API_KEY) apiKey: String?): List<AgentView> {
        guard.autorizar(apiKey)
        return service.agents()
    }

    @Post("/agents", consumes = [MediaType.APPLICATION_JSON])
    open fun createAgent(@Header(API_KEY) apiKey: String?, @Header(OPERATOR) operator: String?, @Body body: Map<String, Any?>): HttpResponse<AgentView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createAgentType(body, guard.operador(operator)))
    }

    @Patch("/agents/{id}", consumes = [MediaType.APPLICATION_JSON])
    open fun updateAgent(@Header(API_KEY) apiKey: String?, @PathVariable id: Long, @Body body: Map<String, Any?>): AgentView {
        guard.autorizar(apiKey)
        return service.updateAgent(id, body)
    }

    @Post("/agents/{id}/archive")
    open fun archiveAgent(@Header(API_KEY) apiKey: String?, @PathVariable id: Long): AgentView {
        guard.autorizar(apiKey)
        return service.archiveAgentType(id, true)
    }

    @Post("/agents/{id}/restore")
    open fun restoreAgent(@Header(API_KEY) apiKey: String?, @PathVariable id: Long): AgentView {
        guard.autorizar(apiKey)
        return service.archiveAgentType(id, false)
    }

    @Get("/agents/{id}/prompts")
    open fun prompts(@Header(API_KEY) apiKey: String?, @PathVariable id: Long): List<PromptVersionView> {
        guard.autorizar(apiKey)
        return service.prompts(id)
    }

    @Post("/agents/{id}/prompts", consumes = [MediaType.APPLICATION_JSON])
    open fun createPrompt(
        @Header(API_KEY) apiKey: String?,
        @Header(OPERATOR) operator: String?,
        @PathVariable id: Long,
        @Body body: Map<String, Any?>,
    ): HttpResponse<PromptVersionView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createPrompt(id, body, guard.operador(operator)))
    }

    @Post("/agents/{id}/prompts/{promptId}/activate")
    open fun activatePrompt(@Header(API_KEY) apiKey: String?, @PathVariable id: Long, @PathVariable promptId: Long): AgentView {
        guard.autorizar(apiKey)
        return service.activatePrompt(id, promptId)
    }

    @io.micronaut.http.annotation.Error(exception = AdminException::class)
    open fun error(e: AdminException): HttpResponse<AdminError> =
        HttpResponse.status<AdminError>(HttpStatus.valueOf(e.status)).body(AdminError(e.code, e.message, e.fields))
}
