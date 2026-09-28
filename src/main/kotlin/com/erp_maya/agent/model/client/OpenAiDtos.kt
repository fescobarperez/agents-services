package com.erp_maya.agent.model.client

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/** Cuerpo de `POST /chat/completions`. Solo lo que este servicio usa. */
@Serdeable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    /** Nula = no se manda: los modelos Claude recientes rechazan el parametro. */
    @Nullable val temperature: Double? = null,
    @JsonProperty("max_completion_tokens") @Nullable val maxCompletionTokens: Int? = null,
    @Nullable val tools: List<OpenAiTool>? = null,
)

@Serdeable
data class OpenAiMessage(
    val role: String,
    @Nullable val content: String? = null,
    @JsonProperty("tool_call_id") @Nullable val toolCallId: String? = null,
    @JsonProperty("tool_calls") @Nullable val toolCalls: List<OpenAiToolCall>? = null,
)

@Serdeable
data class OpenAiTool(val type: String = "function", val function: OpenAiFunction)

@Serdeable
data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: OpenAiSchema,
)

/** Esquema JSON de los parametros, en el subconjunto que la API acepta. */
@Serdeable
data class OpenAiSchema(
    val type: String = "object",
    val properties: Map<String, OpenAiProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serdeable
data class OpenAiProperty(val type: String, val description: String)

@Serdeable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

@Serdeable
data class OpenAiFunctionCall(
    val name: String,
    /** Llega como texto JSON, no como objeto: la API lo define asi. */
    val arguments: String,
)

@Serdeable
data class OpenAiResponse(
    @Nullable val choices: List<OpenAiChoice>? = null,
    @Nullable val usage: OpenAiUsage? = null,
)

@Serdeable
data class OpenAiChoice(
    @Nullable val message: OpenAiMessage? = null,
    @JsonProperty("finish_reason") @Nullable val finishReason: String? = null,
)

@Serdeable
data class OpenAiUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int = 0,
    @JsonProperty("completion_tokens") val completionTokens: Int = 0,
    @JsonProperty("prompt_tokens_details") @Nullable val promptTokensDetails: OpenAiPromptDetails? = null,
)

/** Los tokens que el proveedor sirvio desde su cache: entran mas baratos. */
@Serdeable
data class OpenAiPromptDetails(
    @JsonProperty("cached_tokens") val cachedTokens: Int = 0,
)
