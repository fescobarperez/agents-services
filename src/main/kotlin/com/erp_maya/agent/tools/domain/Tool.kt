package com.erp_maya.agent.tools.domain

import com.erp_maya.agent.context.domain.ToolMode
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/**
 * Una herramienta tal como la ve el modelo.
 *
 * Es la capa de abstraccion MCP: el esquema vive aqui y maya-erp-services
 * queda detras. El agente nunca ve una URL del ERP ni un nombre de endpoint,
 * solo `productos.search` y sus parametros.
 */
@Serdeable
data class ToolDefinition(
    val name: String,
    val mode: ToolMode,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

@Serdeable
data class ToolParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/** Una invocacion concreta pedida por el modelo. */
data class ToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
) {
    fun longArg(clave: String): Long? = when (val v = arguments[clave]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    fun stringArg(clave: String): String? = arguments[clave]?.toString()?.takeIf { it.isNotBlank() }

    fun decimalArg(clave: String): BigDecimal? = when (val v = arguments[clave]) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull()
            ?: NUMERO.find(v)?.value?.replace(',', '.')?.toBigDecimalOrNull()
        else -> null
    }

    private companion object {
        /** El primer numero de un texto como "25 unidades" o "2,5 m". */
        val NUMERO = Regex("\\d+([.,]\\d+)?")
    }
}

sealed interface ToolResult {
    data class Ok(val name: String, val data: Any?) : ToolResult
    data class Failed(val name: String, val message: String) : ToolResult
    /** No se ejecuto y hay que pasarle la conversacion a una persona. */
    data class Escalate(val name: String, val reason: String, val message: String) : ToolResult
}
