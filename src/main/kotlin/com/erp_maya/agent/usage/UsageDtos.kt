package com.erp_maya.agent.usage

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/** Consumo de IA de una empresa en un mes, para el resumen de la consola. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TenantUsageSummary(
    val tenantId: Long,
    /** Mensajes de IA: turnos completados (lo que cuenta contra el limite del plan). */
    val turns: Long,
    val calls: Long,
    val images: Long,
    /** Llamadas + imagenes de ejemplo. */
    val costUsd: BigDecimal,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class UsageOverview(val month: String, val partial: Boolean, val tenants: List<TenantUsageSummary>)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ImagesUsage(val count: Long, val costUsd: BigDecimal)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModelUsageRow(
    val model: String,
    val provider: String,
    val calls: Long,
    /** Solo en modelos de imagenes (cobran por imagen, no por tokens). */
    val images: Long?,
    val tokensIn: Long,
    val tokensCached: Long,
    val tokensOut: Long,
    val costUsd: BigDecimal,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class KindUsageRow(val kind: String, val calls: Long, val costUsd: BigDecimal)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ChannelUsageRow(val channel: String, val turns: Long, val costUsd: BigDecimal)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class DailyUsageRow(val date: String, val turns: Long, val costUsd: BigDecimal)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MonthUsageRow(val month: String, val turns: Long, val costUsd: BigDecimal)

/** Detalle de un mes de una empresa (API.md · «Uso y costos», bloque `ai`). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TenantUsageDetail(
    val tenantId: Long,
    val month: String,
    val partial: Boolean,
    val turns: Long,
    val calls: Long,
    val tokensIn: Long,
    val tokensCached: Long,
    val tokensOut: Long,
    /** Llamadas + imagenes de ejemplo. */
    val costUsd: BigDecimal,
    val images: ImagesUsage,
    val byModel: List<ModelUsageRow>,
    val byKind: List<KindUsageRow>,
    val byChannel: List<ChannelUsageRow>,
    val daily: List<DailyUsageRow>,
    /** Los ultimos meses hasta `month` incluido, del mas reciente al mas antiguo. */
    val history: List<MonthUsageRow>,
)
