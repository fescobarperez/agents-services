package com.erp_maya.agent.usage

import com.erp_maya.agent.admin.AdminError
import com.erp_maya.agent.admin.AdminException
import com.erp_maya.agent.admin.AdminGuard
import com.erp_maya.agent.admin.AdminGuard.Companion.API_KEY
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn

/** Uso y costos de IA para la consola de plataforma (scope `admin`). */
@Controller("/v1/admin/usage", produces = [MediaType.APPLICATION_JSON])
@ExecuteOn(TaskExecutors.BLOCKING)
open class UsageController(private val guard: AdminGuard, private val service: UsageService) {

    /** Una fila por empresa con consumo en el mes. */
    @Get
    open fun overview(@Header(API_KEY) apiKey: String?, @QueryValue month: String?): UsageOverview {
        guard.autorizar(apiKey)
        return service.overview(month)
    }

    @Get("/tenants/{tenantId}")
    open fun tenant(
        @Header(API_KEY) apiKey: String?,
        @PathVariable tenantId: Long,
        @QueryValue month: String?,
        @QueryValue months: Int?,
    ): TenantUsageDetail {
        guard.autorizar(apiKey)
        return service.tenant(tenantId, month, months ?: 6)
    }

    @io.micronaut.http.annotation.Error(exception = AdminException::class)
    open fun error(e: AdminException): HttpResponse<AdminError> =
        HttpResponse.status<AdminError>(HttpStatus.valueOf(e.status)).body(AdminError(e.code, e.message, e.fields))
}
