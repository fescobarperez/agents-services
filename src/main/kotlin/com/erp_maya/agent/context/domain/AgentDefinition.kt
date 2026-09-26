package com.erp_maya.agent.context.domain

import java.math.BigDecimal

/**
 * Un agente resuelto por su codigo, sin canal ni empresa.
 *
 * Lo usa el agente de `resumen`, que no atiende a nadie: lo invoca el sistema
 * sobre una conversacion que ya existe. Por eso no pasa por `channel_agents`
 * ni arrastra politica de empresa.
 */
data class AgentDefinition(
    val id: Long,
    val code: String,
    val model: ModelRef,
    val fallbackModel: ModelRef?,
    val prompt: PromptRef,
    val temperature: BigDecimal,
    val responseTimeoutMs: Int,
)
