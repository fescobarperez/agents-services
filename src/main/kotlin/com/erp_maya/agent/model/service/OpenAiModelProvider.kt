package com.erp_maya.agent.model.service

import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.model.client.OpenAiFunction
import com.erp_maya.agent.model.client.OpenAiFunctionCall
import com.erp_maya.agent.model.client.OpenAiMessage
import com.erp_maya.agent.model.client.OpenAiProperty
import com.erp_maya.agent.model.client.OpenAiRequest
import com.erp_maya.agent.model.client.OpenAiResponse
import com.erp_maya.agent.model.client.OpenAiSchema
import com.erp_maya.agent.model.client.OpenAiTool
import com.erp_maya.agent.model.client.OpenAiToolCall
import com.erp_maya.agent.model.client.ProviderHttpClients
import com.erp_maya.agent.model.domain.ModelCallException
import com.erp_maya.agent.model.domain.ModelCompletion
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.model.domain.ToolInvocation
import com.erp_maya.agent.model.domain.ToolSchema
import com.erp_maya.agent.model.repository.AiProviderRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/** Implementacion para la API de chat de OpenAI. */
@Singleton
class OpenAiModelProvider(
    private val proveedores: AiProviderRepository,
    private val credenciales: CredentialResolver,
    private val clientes: ProviderHttpClients,
    private val json: ObjectMapper,
) : ModelProvider {

    override val providerCode = "openai"

    override fun complete(
        model: ModelRef,
        prompt: ModelPrompt,
        tools: List<ToolSchema>,
        options: ModelOptions,
    ): ModelCompletion {
        val proveedor = proveedores.byCode(providerCode)
            ?: throw ModelCallException("El proveedor '$providerCode' no esta configurado o esta inactivo")

        val cuerpo = OpenAiRequest(
            model = model.modelName,
            messages = prompt.messages.map { traducir(it) },
            temperature = options.temperature.toDouble(),
            maxCompletionTokens = options.maxTokens,
            // Solo se declaran si el modelo las soporta: mandarselas a uno que
            // no las entiende es un 400 seguro, y el de resumen no las lleva.
            tools = if (tools.isEmpty() || !model.supportsTools) null else tools.map(::declarar),
        )

        val peticion = HttpRequest.POST("${proveedor.baseUrl}/chat/completions", cuerpo)
            .bearerAuth(credenciales.secretFor(proveedor))
            .contentType(MediaType.APPLICATION_JSON)

        val respuesta = try {
            clientes.forBaseUrl(proveedor.baseUrl).toBlocking().retrieve(peticion, OpenAiResponse::class.java)
        } catch (e: Exception) {
            // El mensaje del proveedor puede traer fragmentos del prompt; se
            // registra el tipo y se deja el detalle para el log de depuracion.
            log.warn("fallo de {} con {}: {}", providerCode, model.modelName, e.javaClass.simpleName)
            log.debug("detalle del fallo del proveedor", e)
            throw ModelCallException("El proveedor '$providerCode' no respondio", e)
        }

        val mensaje = respuesta.choices?.firstOrNull()?.message
            ?: throw ModelCallException("El proveedor '$providerCode' respondio sin mensaje")

        val uso = respuesta.usage
        return ModelCompletion(
            text = mensaje.content?.trim(),
            usage = ModelUsage(
                input = uso?.promptTokens ?: 0,
                cached = uso?.promptTokensDetails?.cachedTokens ?: 0,
                output = uso?.completionTokens ?: 0,
            ),
            toolCalls = mensaje.toolCalls.orEmpty().map { interpretar(it) },
        )
    }

    private fun traducir(m: PromptMessage) = OpenAiMessage(
        role = m.role.name.lowercase(),
        content = m.content,
        toolCallId = m.toolCallId,
        toolCalls = m.toolCalls.takeIf { it.isNotEmpty() }?.map { inv ->
            OpenAiToolCall(
                id = inv.id,
                function = OpenAiFunctionCall(inv.name, json.writeValueAsString(inv.arguments)),
            )
        },
    )

    private fun declarar(t: ToolSchema) = OpenAiTool(
        function = OpenAiFunction(
            name = t.name.replace('.', '_'), // la API no admite puntos en el nombre
            description = t.description,
            parameters = OpenAiSchema(
                properties = t.parameters.associate { it.name to OpenAiProperty(it.type, it.description) },
                required = t.parameters.filter { it.required }.map { it.name },
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun interpretar(c: OpenAiToolCall): ToolInvocation {
        // `arguments` llega como texto JSON. Si el modelo lo malforma, se
        // devuelve vacio en vez de reventar: la herramienta rechazara la
        // llamada por parametros faltantes, que es un fallo mucho mas legible.
        val args = runCatching {
            json.readValue(c.function.arguments, Map::class.java) as Map<String, Any?>
        }.getOrElse {
            log.warn("argumentos ilegibles en la invocacion de {}", c.function.name)
            emptyMap()
        }
        return ToolInvocation(id = c.id, name = c.function.name.replace('_', '.'), arguments = args)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OpenAiModelProvider::class.java)
    }
}
