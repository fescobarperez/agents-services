package com.erp_maya.agent.tools.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.tools.domain.ToolCall
import com.erp_maya.agent.tools.domain.ToolResult
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Ejecuta una herramienta contra el ERP.
 *
 * Toda escritura pasa antes por [WriteGate]; esta clase no decide si se puede,
 * solo hace. Separarlo evita la tentacion de colar una excepcion "solo por
 * esta vez" dentro de la logica de negocio.
 */
@Singleton
open class ToolExecutor(
    private val erp: ErpClient,
    private val catalogo: ToolCatalog,
    private val gate: WriteGate,
) {

    open fun execute(
        contexto: ExecutionContext,
        conversationId: Long,
        call: ToolCall,
        turno: WriteContext,
        idempotencyKey: String,
    ): ToolResult {
        catalogo.byName(call.name)
            ?: return ToolResult.Failed(call.name, "La herramienta '${call.name}' no existe")

        when (val veredicto = gate.evaluate(contexto, call, turno)) {
            is GateDecision.Escalate -> {
                log.warn(
                    "escritura bloqueada tenant={} conversation={} tool={} motivo={}",
                    contexto.tenantId, conversationId, call.name, veredicto.reason,
                )
                return ToolResult.Escalate(call.name, veredicto.reason.name, veredicto.message)
            }
            GateDecision.Allow -> Unit
        }

        return try {
            ToolResult.Ok(call.name, invocar(contexto, conversationId, call, idempotencyKey))
        } catch (e: ErpException) {
            log.warn("la herramienta {} fallo contra el ERP: {}", call.name, e.message)
            ToolResult.Failed(call.name, e.message ?: "El ERP no respondio")
        }
    }

    private fun invocar(
        contexto: ExecutionContext,
        conversationId: Long,
        call: ToolCall,
        idempotencyKey: String,
    ): Any? {
        val t = contexto.tenantId
        return when (call.name) {
            "productos.search" -> conExistencias(
                t, conversationId,
                erp.searchProducts(
                    t, conversationId,
                    call.stringArg("query").orEmpty(),
                    (call.longArg("limit") ?: 10L).toInt().coerceIn(1, MAX_RESULTADOS),
                ),
            )
            "productos.stock" -> erp.getStock(t, conversationId, requireId(call, "productId"))
            "clientes.find" -> erp.findCustomerByPhone(t, conversationId, call.stringArg("phone").orEmpty())
            "clientes.buscar" -> erp.searchCustomers(t, conversationId, call.stringArg("query").orEmpty())
            "empresa.get" -> erp.getCompany(t, conversationId)
            "cotizaciones.preview" -> erp.previewQuote(t, conversationId, call.longArg("customerId"), lineas(call))
            "cotizaciones.issue" -> erp.issueQuote(
                tenantId = t,
                conversationId = conversationId,
                customerId = call.longArg("customerId"),
                lines = lineas(call),
                expectedTotal = call.decimalArg("total") ?: BigDecimal.ZERO,
                // Derivada del mensaje entrante: si el canal reintenta, el ERP
                // reconoce la operacion y no emite un segundo documento.
                idempotencyKey = "$idempotencyKey:${call.name}",
            )
            else -> throw ErpException("La herramienta '${call.name}' no tiene implementacion")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun lineas(call: ToolCall): List<QuoteLineRequest> {
        val crudas = call.arguments["lines"] as? List<Map<String, Any?>> ?: emptyList()
        return crudas.mapNotNull { fila ->
            val producto = (fila["productId"] as? Number)?.toLong()
                ?: (fila["productId"] as? String)?.toLongOrNull()
            val cantidad = when (val q = fila["quantity"]) {
                is Number -> BigDecimal(q.toString())
                is String -> q.toBigDecimalOrNull()
                else -> null
            }
            if (producto == null || cantidad == null) null else QuoteLineRequest(producto, cantidad)
        }
    }

    /**
     * Completa cada resultado con su existencia. El modelo la necesita para
     * contestar "hay stock" sin otra vuelta, y el panel la muestra. Si la
     * consulta de un producto falla, ese queda sin dato: no tumba la busqueda.
     */
    private fun conExistencias(t: Long, conversationId: Long, productos: List<ProductSummary>): List<ProductSummary> =
        productos.map { p ->
            val stock = runCatching { erp.getStock(t, conversationId, p.id) }
                .onFailure { log.debug("sin existencia para {}: {}", p.id, it.message) }
                .getOrNull()
            p.copy(available = stock?.available, branch = stock?.branch)
        }

    private fun requireId(call: ToolCall, clave: String): Long =
        call.longArg(clave) ?: throw ErpException("Falta el parametro '$clave'")

    private companion object {
        /** Tope de resultados: cada uno cuesta una consulta de existencias. */
        const val MAX_RESULTADOS = 8
        private val log = LoggerFactory.getLogger(ToolExecutor::class.java)
    }
}
