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
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Implementacion para APIs con el formato de chat de OpenAI
 * (`POST {base_url}/chat/completions`, autenticacion Bearer).
 *
 * La URL y la credencial salen de la fila de `ai_providers` cuyo `code` es
 * [providerCode]; cada proveedor compatible es una subclase de una linea.
 */
abstract class OpenAiCompatibleModelProvider(
    private val proveedores: AiProviderRepository,
    private val credenciales: CredentialResolver,
    private val clientes: ProviderHttpClients,
    private val json: ObjectMapper,
) : ModelProvider {

    abstract override val providerCode: String

    /**
     * Si el proveedor acepta `temperature`. Los Claude recientes (Sonnet 5,
     * Opus 5) responden 400 «temperature is deprecated for this model».
     */
    protected open val enviaTemperatura: Boolean = true

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
            temperature = if (enviaTemperatura) options.temperature.toDouble() else null,
            // Anthropic exige un tope de salida; OpenAI lo acepta igual.
            maxCompletionTokens = options.maxTokens ?: MAX_TOKENS_POR_DEFECTO,
            // Solo se declaran si el modelo las soporta: mandarselas a uno que
            // no las entiende es un 400 seguro, y el de resumen no las lleva.
            tools = if (tools.isEmpty() || !model.supportsTools) null else tools.map(::declarar),
        )

        val url = "${proveedor.baseUrl}/chat/completions"
        val crudo = try {
            clientes.postJson(
                url = url,
                bearer = credenciales.secretFor(proveedor),
                json = json.writeValueAsString(cuerpo),
                timeout = Duration.ofMillis(options.timeoutMs.toLong().coerceAtLeast(1_000)),
            )
        } catch (e: Exception) {
            log.warn("fallo de {} con {}: {} {}", providerCode, model.modelName, e.javaClass.simpleName, e.message)
            log.debug("detalle del fallo del proveedor", e)
            throw ModelCallException("El proveedor '$providerCode' no respondio", e)
        }

        if (crudo.status !in 200..299) {
            // El cuerpo de error del proveedor dice que fallo (modelo, llave,
            // parametro); se recorta porque puede traer fragmentos del prompt.
            log.warn(
                "{} respondio {} con {}: {}",
                providerCode, crudo.status, model.modelName, crudo.body.take(500),
            )
            throw ModelCallException("El proveedor '$providerCode' respondio ${crudo.status}")
        }

        val respuesta = try {
            requireNotNull(json.readValue(crudo.body, OpenAiResponse::class.java))
        } catch (e: Exception) {
            log.warn("respuesta ilegible de {} con {}: {}", providerCode, model.modelName, e.javaClass.simpleName)
            throw ModelCallException("El proveedor '$providerCode' respondio algo ilegible", e)
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
                // Mismo nombre que se declaro: sin punto, que la API no admite.
                function = OpenAiFunctionCall(inv.name.replace('.', '_'), json.writeValueAsString(inv.arguments)),
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
        // Solo el primer '_' era el punto: `cotizacion_cliente_nuevo` vuelve a
        // ser `cotizacion.cliente_nuevo`, no `cotizacion.cliente.nuevo`.
        return ToolInvocation(id = c.id, name = c.function.name.replaceFirst('_', '.'), arguments = args)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OpenAiCompatibleModelProvider::class.java)
        private const val MAX_TOKENS_POR_DEFECTO = 4096
    }
}

/** OpenAI. */
@Singleton
class OpenAiModelProvider(
    proveedores: AiProviderRepository,
    credenciales: CredentialResolver,
    clientes: ProviderHttpClients,
    json: ObjectMapper,
) : OpenAiCompatibleModelProvider(proveedores, credenciales, clientes, json) {
    override val providerCode = "openai"
}

/**
 * Claude por la capa compatible con OpenAI de Anthropic
 * (`base_url = https://api.anthropic.com/v1`). Sirve para arrancar y probar;
 * esa capa no aplica prompt caching, asi que para produccion conviene un
 * proveedor nativo sobre la Messages API.
 */
@Singleton
class AnthropicCompatModelProvider(
    proveedores: AiProviderRepository,
    credenciales: CredentialResolver,
    clientes: ProviderHttpClients,
    json: ObjectMapper,
) : OpenAiCompatibleModelProvider(proveedores, credenciales, clientes, json) {
    override val providerCode = "anthropic"

    // Se omite para todos los modelos de Anthropic: los que aun la aceptan
    // funcionan bien con su valor por defecto, y asi no hay que llevar una
    // lista de cuales la rechazan.
    override val enviaTemperatura = false
}
