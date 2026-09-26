package com.erp_maya.agent.model.service

import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.ToolSchema
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton

/**
 * Sustituye a OpenAI en las pruebas.
 *
 * Existe para que la bateria no dependa de una clave, de la red ni del saldo
 * de una cuenta, y para que el texto sea determinista. Se activa solo en el
 * entorno de test: en cualquier otro, el bean real sigue siendo el de verdad.
 */
@Singleton
@Replaces(OpenAiModelProvider::class)
@Requires(env = [Environment.TEST])
class ProveedorDePruebas : ModelProvider {

    override val providerCode = "openai"

    /** Los prompts que recibio, para poder afirmar sobre como se armaron. */
    val recibidos = mutableListOf<ModelPrompt>()

    /** Los esquemas que se le declararon, para poder afirmar sobre ellos. */
    val esquemasVistos = mutableListOf<List<ToolSchema>>()

    override fun complete(
        model: ModelRef,
        prompt: ModelPrompt,
        tools: List<ToolSchema>,
        options: ModelOptions,
    ): ModelCompletion {
        recibidos += prompt
        esquemasVistos += tools
        // Guion programado por el test, si lo hay; si no, respuesta por defecto.
        guion.poll()?.let { return it }
        return ModelCompletion(
            text = "respuesta simulada de ${model.modelName}",
            usage = ModelUsage(input = 120, cached = 80, output = 20),
        )
    }

    /** Respuestas programadas, que se consumen en orden. */
    val guion: java.util.Queue<ModelCompletion> = java.util.concurrent.ConcurrentLinkedQueue()

    fun programar(vararg respuestas: ModelCompletion) {
        guion.clear()
        respuestas.forEach { guion.add(it) }
    }

    fun limpiar() {
        guion.clear()
        recibidos.clear()
        esquemasVistos.clear()
    }
}
