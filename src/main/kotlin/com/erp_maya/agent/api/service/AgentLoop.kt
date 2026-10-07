package com.erp_maya.agent.api.service

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.quote.DraftStep
import com.erp_maya.agent.quote.QuoteDraftService
import com.erp_maya.agent.model.domain.ModelPrompt
import com.erp_maya.agent.model.domain.ModelUsage
import com.erp_maya.agent.model.domain.PromptMessage
import com.erp_maya.agent.model.domain.Role
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.prompt.domain.SessionState
import com.erp_maya.agent.tools.domain.PendingWrite
import com.erp_maya.agent.tools.domain.ToolCall
import com.erp_maya.agent.tools.domain.ToolResult
import com.erp_maya.agent.tools.service.ToolCatalog
import com.erp_maya.agent.tools.service.ToolExecutor
import com.erp_maya.agent.tools.service.WriteContext
import com.erp_maya.agent.tools.service.WriteGate
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/** Lo que dejo el bucle tras hablar con el modelo y ejecutar herramientas. */
data class LoopOutcome(
    val text: String,
    val usage: ModelUsage,
    val toolsCalled: List<String>,
    val modelName: String,
    val modelId: Long,
    val fallbackUsed: Boolean,
    val latencyMs: Int,
    val pendingWrite: PendingWrite?,
    /** Si viene informado, el turno no se resolvio y pasa a una persona. */
    val escalation: String? = null,
    /** Tarjetas para el panel, construidas con los datos del ERP. */
    val cards: List<AgentEvent.Card> = emptyList(),
    /** Cotizacion en curso tras este turno; se persiste en el estado. */
    val borrador: QuoteDraft? = null,
)

/**
 * El bucle modelo ↔ herramientas.
 *
 * El modelo pide herramientas, se ejecutan, se le devuelve el resultado y
 * vuelve a decidir. Se repite hasta `max_tool_loops`, que es un tope y no una
 * sugerencia: sin el, un modelo que se confunde puede pedir la misma consulta
 * indefinidamente y gastar el presupuesto de la empresa en una tarde.
 */
@Singleton
open class AgentLoop(
    private val router: ModelRouter,
    private val catalogo: ToolCatalog,
    private val ejecutor: ToolExecutor,
    private val gate: WriteGate,
    private val json: ObjectMapper,
    private val cotizaciones: QuoteDraftService,
) {

    open fun run(
        contexto: ExecutionContext,
        conversationId: Long,
        prompt: ModelPrompt,
        estado: SessionState,
        confirmado: Boolean,
        idempotencyKey: String,
        /** Numero de quien escribe, si el canal lo da (WhatsApp). */
        telefono: String? = null,
    ): LoopOutcome {
        val esquemas = catalogo.schemasFor(contexto)
        val mensajes = prompt.messages.toMutableList()
        val ejecutadas = mutableListOf<String>()
        // Por plantilla: si el modelo busca dos veces, el panel muestra la ultima.
        val tarjetas = linkedMapOf<String, AgentEvent.Card>()

        var uso = ModelUsage()
        var pendiente = estado.pendingWrite
        var borrador = estado.borrador
        var latencia = 0
        var ultimoModelo = contexto.model
        var huboRespaldo = false

        // +1 porque la ultima vuelta es la que redacta la respuesta ya con los
        // resultados en mano; si no, el tope se gastaria antes de contestar.
        repeat(contexto.agent.maxToolLoops + 1) { vuelta ->
            val salida = router.complete(contexto, ModelPrompt(mensajes), esquemas)
            uso += salida.completion.usage
            latencia += salida.latencyMs
            ultimoModelo = salida.model
            huboRespaldo = huboRespaldo || salida.fallbackUsed

            if (!salida.completion.pideHerramientas) {
                return LoopOutcome(
                    text = salida.completion.text.orEmpty(),
                    usage = uso,
                    toolsCalled = ejecutadas,
                    modelName = ultimoModelo.modelName,
                    modelId = ultimoModelo.id,
                    fallbackUsed = huboRespaldo,
                    latencyMs = latencia,
                    pendingWrite = pendiente,
                    cards = tarjetas.values.toList(),
                    borrador = borrador,
                )
            }

            // Se reenvia la peticion del propio modelo: sin ella, en la vuelta
            // siguiente no reconoce sus resultados y vuelve a pedir lo mismo.
            mensajes += PromptMessage(
                role = Role.ASSISTANT,
                content = salida.completion.text,
                toolCalls = salida.completion.toolCalls,
            )

            for (invocacion in salida.completion.toolCalls) {
                val call = ToolCall(invocacion.name, invocacion.arguments)
                log.info("conversation={} herramienta {} args={}", conversationId, call.name, call.arguments)
                val resultado = if (call.name == ToolCatalog.STICKER) {
                    // No toca ningun sistema: deja la expresion en el turno y
                    // el canal decide como pintarla. Una por turno.
                    expresar(contexto, call, tarjetas)
                } else if (call.name.startsWith(PREFIJO_BORRADOR)) {
                    // El borrador vive en el estado del hilo: estas herramientas
                    // lo leen y lo reescriben, por eso no pasan por ToolExecutor.
                    val paso = ejecutarBorrador(contexto, conversationId, call, borrador, telefono)
                    paso.second?.let { p ->
                        borrador = p.borrador
                        p.tarjetas.forEach { tarjetas[it.card] = it }
                    }
                    paso.first
                } else {
                    ejecutor.execute(
                        contexto = contexto,
                        conversationId = conversationId,
                        call = call,
                        turno = WriteContext(estado.turn, pendiente, confirmado),
                        idempotencyKey = idempotencyKey,
                    )
                }
                ejecutadas += invocacion.name

                if (resultado is ToolResult.Escalate) {
                    // La compuerta dijo que no. No se le devuelve al modelo
                    // para que lo intente de otra forma: se corta el turno.
                    log.warn(
                        "turno escalado tenant={} conversation={} tool={} motivo={}",
                        contexto.tenantId, conversationId, invocacion.name, resultado.reason,
                    )
                    return LoopOutcome(
                        text = MENSAJE_ESCALAMIENTO,
                        usage = uso,
                        toolsCalled = ejecutadas,
                        modelName = ultimoModelo.modelName,
                        modelId = ultimoModelo.id,
                        fallbackUsed = huboRespaldo,
                        latencyMs = latencia,
                        pendingWrite = pendiente,
                        cards = tarjetas.values.toList(),
                        borrador = borrador,
                        escalation = "${resultado.reason}: ${resultado.message}",
                    )
                }

                pendiente = recordarPreview(resultado, call, estado, pendiente)
                if (resultado is ToolResult.Ok) {
                    PanelCards.desde(resultado)?.let { tarjetas[it.card] = it }
                }
                mensajes += PromptMessage(
                    role = Role.TOOL,
                    content = serializar(resultado),
                    toolCallId = invocacion.id,
                )
            }

            if (vuelta == contexto.agent.maxToolLoops) {
                log.warn(
                    "se agotaron las {} vueltas de herramientas en la conversacion {}",
                    contexto.agent.maxToolLoops, conversationId,
                )
            }
        }

        // Se agoto el tope sin que el modelo redactara nada.
        return LoopOutcome(
            text = MENSAJE_SIN_RESOLVER,
            usage = uso,
            toolsCalled = ejecutadas,
            modelName = ultimoModelo.modelName,
            modelId = ultimoModelo.id,
            fallbackUsed = huboRespaldo,
            latencyMs = latencia,
            pendingWrite = pendiente,
            escalation = "TOOL_LOOP_EXHAUSTED",
            cards = tarjetas.values.toList(),
            borrador = borrador,
        )
    }

    /**
     * Herramientas del borrador de cotizacion. Devuelve el resultado para el
     * modelo y, si salio bien, el paso con el borrador nuevo y sus tarjetas.
     */
    private fun ejecutarBorrador(
        contexto: ExecutionContext,
        conversationId: Long,
        call: ToolCall,
        actual: QuoteDraft?,
        telefono: String?,
    ): Pair<ToolResult, DraftStep?> {
        if (contexto.toolFor(call.name) == null) {
            return ToolResult.Failed(call.name, "El agente no tiene concedida '${call.name}'") to null
        }
        val ref = "cnv_%08d".format(conversationId)
        return try {
            val paso = when (call.name) {
                "cotizacion.cliente" -> cotizaciones.fijarCliente(
                    contexto, conversationId, ref, actual,
                    call.longArg("customerId") ?: throw IllegalArgumentException("Falta customerId"),
                )
                "cotizacion.cliente_nuevo" -> cotizaciones.clienteNuevo(
                    contexto, conversationId, ref, actual,
                    nombre = call.stringArg("nombre") ?: throw IllegalArgumentException("Falta nombre"),
                    nit = call.stringArg("nit"),
                    telefono = telefono,
                )
                "cotizacion.agregar" -> cotizaciones.agregar(
                    contexto, conversationId, ref, actual,
                    productId = call.longArg("productId") ?: throw IllegalArgumentException("Falta productId"),
                    sku = null,
                    // Sin cantidad se rechaza: caer en 1 escondia el error y
                    // la cotizacion salia con una cantidad que nadie pidio.
                    cantidad = call.decimalArg("quantity")
                        ?: throw IllegalArgumentException("Falta quantity: indica la cantidad exacta que pidio el cliente"),
                )
                "cotizacion.cantidad" -> cotizaciones.editarLinea(
                    contexto, conversationId, actual,
                    call.longArg("lineId") ?: throw IllegalArgumentException("Falta lineId"),
                    call.decimalArg("quantity") ?: throw IllegalArgumentException("Falta quantity"),
                )
                else -> throw IllegalArgumentException("La herramienta '${call.name}' no existe")
            }
            ToolResult.Ok(call.name, paso.datos + ("mensaje" to paso.mensaje)) to paso
        } catch (e: ErpException) {
            log.warn("la herramienta {} fallo: {}", call.name, e.message)
            ToolResult.Failed(call.name, e.message ?: "El ERP no respondio") to null
        } catch (e: IllegalArgumentException) {
            ToolResult.Failed(call.name, e.message ?: "Parametros invalidos") to null
        }
    }

    private fun expresar(
        contexto: ExecutionContext,
        call: ToolCall,
        tarjetas: MutableMap<String, AgentEvent.Card>,
    ): ToolResult {
        if (catalogo.availableFor(contexto).none { it.name == ToolCatalog.STICKER }) {
            return ToolResult.Failed(call.name, "Este canal no admite stickers")
        }
        val momento = call.stringArg("momento")?.trim()?.lowercase()
        if (momento !in ToolCatalog.MOMENTOS_STICKER) {
            return ToolResult.Failed(call.name, "momento debe ser uno de ${ToolCatalog.MOMENTOS_STICKER}")
        }
        if (tarjetas.containsKey(PanelCards.EXPRESSION)) {
            return ToolResult.Failed(call.name, "Ya hay un sticker en este turno")
        }
        tarjetas[PanelCards.EXPRESSION] = PanelCards.expresion(momento!!)
        return ToolResult.Ok(call.name, mapOf("enviado" to momento))
    }

    /**
     * Una previsualizacion exitosa deja anotado el total y la huella de los
     * argumentos. Es lo que la compuerta exigira en el turno siguiente para
     * dejar emitir.
     */
    private fun recordarPreview(
        resultado: ToolResult,
        call: ToolCall,
        estado: SessionState,
        actual: PendingWrite?,
    ): PendingWrite? {
        if (resultado !is ToolResult.Ok || call.name != TOOL_PREVIEW) return actual
        val total = totalDe(resultado.data) ?: return actual

        // Se anota contra la herramienta de ESCRITURA, con los mismos
        // argumentos menos el total: es lo que se comparara al emitir.
        val equivalente = ToolCall(TOOL_EMITIR, call.arguments)
        return gate.pendingFrom(TOOL_EMITIR, total, estado.turn, equivalente)
    }

    @Suppress("UNCHECKED_CAST")
    private fun totalDe(data: Any?): BigDecimal? = runCatching {
        val mapa = json.readValue(json.writeValueAsString(data), Map::class.java) as? Map<String, Any?>
            ?: return@runCatching null
        when (val t = mapa["total"]) {
            is BigDecimal -> t
            is Number -> BigDecimal(t.toString())
            is String -> t.toBigDecimalOrNull()
            else -> null
        }
    }.getOrNull()

    private fun serializar(resultado: ToolResult): String = when (resultado) {
        is ToolResult.Ok -> runCatching { json.writeValueAsString(resultado.data) }
            .getOrElse { """{"ok":true}""" }
        is ToolResult.Failed -> """{"error":${comillas(resultado.message)}}"""
        is ToolResult.Escalate -> """{"error":${comillas(resultado.message)}}"""
    }

    private fun comillas(s: String) = '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    companion object {
        const val TOOL_PREVIEW = "cotizaciones.preview"
        const val PREFIJO_BORRADOR = "cotizacion."
        const val TOOL_EMITIR = "cotizaciones.issue"

        const val MENSAJE_ESCALAMIENTO =
            "Voy a pasar esta solicitud con un compañero del equipo para confirmarla. En un momento le escriben."
        const val MENSAJE_SIN_RESOLVER =
            "No logré completar la consulta. Un compañero del equipo le va a dar seguimiento."

        private val log = LoggerFactory.getLogger(AgentLoop::class.java)
    }
}
