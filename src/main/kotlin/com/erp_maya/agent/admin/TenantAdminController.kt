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
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

/**
 * IA de una empresa en la consola: politica, agentes de la empresa, canales,
 * instrucciones y playbook. `tenantId` es el id de la empresa en el ERP;
 * stack-line-services ya comprobo que existe y el rol del operador.
 */
@Controller("/v1/admin/tenants/{tenantId}", produces = [MediaType.APPLICATION_JSON])
@ExecuteOn(TaskExecutors.BLOCKING)
open class TenantAdminController(
    private val guard: AdminGuard,
    private val service: TenantAdminService,
) {

    @Get
    open fun view(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long): TenantAiView {
        guard.autorizar(apiKey)
        return service.view(tenantId)
    }

    @Put("/policy", consumes = [MediaType.APPLICATION_JSON])
    open fun policy(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @Body body: Map<String, Any?>): TenantAiView {
        guard.autorizar(apiKey)
        return service.savePolicy(tenantId, body)
    }

    @Post("/agents", consumes = [MediaType.APPLICATION_JSON])
    open fun create(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @Body body: Map<String, Any?>): HttpResponse<TenantAiView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createAgent(tenantId, body))
    }

    @Patch("/agents/{id}", consumes = [MediaType.APPLICATION_JSON])
    open fun update(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @PathVariable id: Long, @Body body: Map<String, Any?>): TenantAiView {
        guard.autorizar(apiKey)
        return service.updateAgent(tenantId, id, body)
    }

    @Post("/agents/{id}/archive")
    open fun archive(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @PathVariable id: Long): TenantAiView {
        guard.autorizar(apiKey)
        return service.archiveAgent(tenantId, id)
    }

    @Get("/whatsapp/lookup")
    open fun lookupWhatsApp(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @QueryValue phoneNumberId: String?): WhatsAppNumberView {
        guard.autorizar(apiKey)
        return service.lookupWhatsApp(tenantId, phoneNumberId)
    }

    @Post("/whatsapp-channels", consumes = [MediaType.APPLICATION_JSON])
    open fun connectWhatsApp(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @Body body: Map<String, Any?>): HttpResponse<TenantAiView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.connectWhatsApp(tenantId, body))
    }

    @Post("/whatsapp-channels/{channelId}/disconnect")
    open fun disconnectWhatsApp(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @PathVariable channelId: Long): TenantAiView {
        guard.autorizar(apiKey)
        return service.disconnectWhatsApp(tenantId, channelId)
    }

    @Post("/web-channels", consumes = [MediaType.APPLICATION_JSON])
    open fun createWeb(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @Body body: Map<String, Any?>): HttpResponse<TenantAiView> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createWebChannel(tenantId, body))
    }

    @Patch("/web-channels/{channelId}", consumes = [MediaType.APPLICATION_JSON])
    open fun updateWeb(
        @Header(API_KEY) apiKey: String?,
        @PathVariable tenantId: Long,
        @PathVariable channelId: Long,
        @Body body: Map<String, Any?>,
    ): TenantAiView {
        guard.autorizar(apiKey)
        return service.updateWebChannel(tenantId, channelId, body)
    }

    @Get("/agents/{id}/instructions")
    open fun instructions(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @PathVariable id: Long): List<PromptVersionView> {
        guard.autorizar(apiKey)
        return service.instructions(tenantId, id)
    }

    @Post("/agents/{id}/instructions", consumes = [MediaType.APPLICATION_JSON])
    open fun createInstructions(
        @Header(API_KEY) apiKey: String?,
        @Header(OPERATOR) operator: String?,
        @PathVariable tenantId: Long,
        @PathVariable id: Long,
        @Body body: Map<String, Any?>,
    ): HttpResponse<List<PromptVersionView>> {
        guard.autorizar(apiKey)
        return HttpResponse.created(service.createInstructions(tenantId, id, body, guard.operador(operator)))
    }

    @Post("/agents/{id}/instructions/{versionId}/activate")
    open fun activateInstructions(
        @Header(API_KEY) apiKey: String?,
        @PathVariable tenantId: Long,
        @PathVariable id: Long,
        @PathVariable versionId: Long,
    ): List<PromptVersionView> {
        guard.autorizar(apiKey)
        return service.activateInstructions(tenantId, id, versionId)
    }

    @Post("/agents/{id}/instructions/clear")
    open fun clearInstructions(@Header(API_KEY) apiKey: String?, @PathVariable tenantId: Long, @PathVariable id: Long): List<PromptVersionView> {
        guard.autorizar(apiKey)
        return service.activateInstructions(tenantId, id, null)
    }

    @Put("/agents/{id}/playbook", consumes = [MediaType.APPLICATION_JSON])
    open fun playbook(
        @Header(API_KEY) apiKey: String?,
        @Header(OPERATOR) operator: String?,
        @PathVariable tenantId: Long,
        @PathVariable id: Long,
        @Body body: Map<String, Any?>,
    ): TenantAiView {
        guard.autorizar(apiKey)
        return service.savePlaybook(tenantId, id, body, guard.operador(operator))
    }

    @io.micronaut.http.annotation.Error(exception = AdminException::class)
    open fun error(e: AdminException): HttpResponse<AdminError> =
        HttpResponse.status<AdminError>(HttpStatus.valueOf(e.status)).body(AdminError(e.code, e.message, e.fields))
}
