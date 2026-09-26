package com.erp_maya.agent.model.service

import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ToolSchema

/**
 * Un proveedor de modelos.
 *
 * Es la unica frontera por la que entra el SDK o el protocolo de un tercero.
 * Ninguna clase de negocio habla con OpenAI ni con nadie mas directamente:
 * cambiar de proveedor tiene que ser agregar una implementacion y un UPDATE en
 * `ai_models`, nunca tocar el orquestador del turno.
 */
interface ModelProvider {

    /** Valor de `ai_providers.code` que atiende esta implementacion. */
    val providerCode: String

    fun complete(
        model: ModelRef,
        prompt: ModelPrompt,
        tools: List<ToolSchema>,
        options: ModelOptions,
    ): ModelCompletion
}
