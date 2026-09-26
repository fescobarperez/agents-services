package com.erp_maya.agent.model.domain

import java.math.BigDecimal

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * Un mensaje del prompt.
 *
 * Lleva las piezas del protocolo de herramientas porque el modelo necesita ver
 * su propia peticion y el resultado que le devolvimos: sin reenviar ambas, la
 * siguiente vuelta del bucle no sabe que ya pidio esa herramienta y la vuelve
 * a pedir.
 */
data class PromptMessage(
    val role: Role,
    val content: String? = null,
    /** Presente en los mensajes de rol TOOL: a que invocacion responden. */
    val toolCallId: String? = null,
    /** Presente en el mensaje ASSISTANT que pidio herramientas. */
    val toolCalls: List<ToolInvocation> = emptyList(),
)

/**
 * El prompt de un turno, ya armado y con tamaño acotado.
 *
 * El orden importa y no es estetico: va de lo mas estable a lo mas volatil
 * —system, herramientas, resumen, entidades, mensajes— para que el proveedor
 * pueda cachear el prefijo. Invertirlo invalida el cache en cada turno y
 * multiplica el costo.
 */
data class ModelPrompt(val messages: List<PromptMessage>) {
    val aproxChars: Int get() = messages.sumOf { it.content?.length ?: 0 }
}

/** Esquema de una herramienta tal como se le declara al proveedor. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: List<ToolSchemaParam> = emptyList(),
)

data class ToolSchemaParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
)

/** Una herramienta que el modelo pidio ejecutar. */
data class ToolInvocation(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
)

data class ModelOptions(
    val temperature: BigDecimal,
    val timeoutMs: Int,
    val maxTokens: Int? = null,
)

data class ModelUsage(val input: Int = 0, val cached: Int = 0, val output: Int = 0) {
    operator fun plus(otro: ModelUsage) =
        ModelUsage(input + otro.input, cached + otro.cached, output + otro.output)
}

/**
 * Respuesta del modelo: o texto para el usuario, o herramientas que quiere
 * ejecutar. Nunca las dos cosas a la vez con sentido.
 */
data class ModelCompletion(
    val text: String?,
    val usage: ModelUsage,
    val toolCalls: List<ToolInvocation> = emptyList(),
) {
    val pideHerramientas: Boolean get() = toolCalls.isNotEmpty()
}

/** Fallo al hablar con el proveedor: red, timeout, credencial o cuota. */
class ModelCallException(mensaje: String, causa: Throwable? = null) : RuntimeException(mensaje, causa)

/** No hay implementacion registrada para el proveedor que dice la configuracion. */
class UnknownProviderException(providerCode: String) :
    RuntimeException("No hay implementacion para el proveedor '$providerCode'")
