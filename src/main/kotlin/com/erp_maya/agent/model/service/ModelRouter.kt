package com.erp_maya.agent.model.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ToolSchema
import com.erp_maya.agent.model.domain.UnknownProviderException
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/** Resultado de un turno contra el modelo, con el rastro de que se uso. */
data class RoutedCompletion(
    val completion: ModelCompletion,
    val model: ModelRef,
    val fallbackUsed: Boolean,
    val latencyMs: Int,
)

/**
 * Escoge la implementacion por `ai_providers.code` y aplica el respaldo.
 *
 * Un solo reintento, y contra el modelo de respaldo, no contra el mismo:
 * repetir contra un modelo caido solo suma latencia, y el usuario del otro
 * lado esta esperando.
 */
@Singleton
open class ModelRouter(proveedores: List<ModelProvider>) {

    private val porCodigo: Map<String, ModelProvider> = proveedores.associateBy { it.providerCode }

    init {
        log.info("Proveedores de modelo registrados: {}", porCodigo.keys.joinToString())
    }

    /** Atajo para el turno de un canal, que ya trae todo en su contexto. */
    open fun complete(
        contexto: ExecutionContext,
        prompt: ModelPrompt,
        tools: List<ToolSchema> = emptyList(),
    ): RoutedCompletion =
        complete(
            principal = contexto.model,
            respaldo = contexto.fallbackModel,
            opciones = ModelOptions(
                temperature = contexto.agent.temperature,
                timeoutMs = contexto.agent.responseTimeoutMs,
            ),
            prompt = prompt,
            tools = tools,
        )

    open fun complete(
        principal: ModelRef,
        respaldo: ModelRef?,
        opciones: ModelOptions,
        prompt: ModelPrompt,
        tools: List<ToolSchema> = emptyList(),
    ): RoutedCompletion {
        val inicio = System.nanoTime()
        return try {
            RoutedCompletion(invocar(principal, prompt, tools, opciones), principal, false, transcurrido(inicio))
        } catch (falloPrincipal: Exception) {
            if (respaldo == null) {
                log.error("modelo {} fallo y no hay respaldo configurado", principal.modelName)
                throw falloPrincipal
            }
            log.warn("modelo {} fallo ({}); reintentando con el respaldo {}",
                principal.modelName, falloPrincipal.javaClass.simpleName, respaldo.modelName)

            RoutedCompletion(invocar(respaldo, prompt, tools, opciones), respaldo, true, transcurrido(inicio))
        }
    }

    private fun invocar(
        model: ModelRef,
        prompt: ModelPrompt,
        tools: List<ToolSchema>,
        opciones: ModelOptions,
    ): ModelCompletion {
        val proveedor = porCodigo[model.providerCode] ?: throw UnknownProviderException(model.providerCode)
        return proveedor.complete(model, prompt, tools, opciones)
    }

    private fun transcurrido(inicioNanos: Long) = ((System.nanoTime() - inicioNanos) / 1_000_000).toInt()

    private companion object {
        private val log = LoggerFactory.getLogger(ModelRouter::class.java)
    }
}
