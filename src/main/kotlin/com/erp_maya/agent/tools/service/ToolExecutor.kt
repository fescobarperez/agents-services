package com.erp_maya.agent.tools.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.LeadWrite
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.playbook.service.PlaybookService
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
    private val playbooks: PlaybookService,
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
            "productos.search" -> {
                val encontrados = conExistencias(
                    t, conversationId,
                    erp.searchProducts(
                        t, conversationId,
                        call.stringArg("query").orEmpty(),
                        (call.longArg("limit") ?: 10L).toInt().coerceIn(1, MAX_RESULTADOS),
                    ),
                )
                // Con "solo total" el modelo ni recibe el precio de catalogo:
                // no puede decir una cifra que luego no coincida con el PDF.
                if (ocultarPrecios(contexto)) encontrados.map { it.copy(unitPrice = null) } else encontrados
            }
            "productos.stock" -> erp.getStock(t, conversationId, requireId(call, "productId"))
            "clientes.find" -> erp.findCustomerByPhone(t, conversationId, call.stringArg("phone").orEmpty())
            "clientes.buscar" -> erp.searchCustomers(t, conversationId, call.stringArg("query").orEmpty())
            "empresa.get" -> erp.getCompany(t, conversationId)
            ToolCatalog.CONTACTO -> erp.createLead(t, conversationId, prospecto(contexto, conversationId, call))
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

    /**
     * Arma el prospecto con lo que dio el modelo. Sin nombre o sin forma de
     * contacto no se llama al ERP: el error vuelve al modelo para que lo pida.
     */
    private fun prospecto(contexto: ExecutionContext, conversationId: Long, call: ToolCall): LeadWrite {
        val nombre = call.stringArg("nombre")?.trim()
            ?: throw ErpException("Falta el nombre: pregúntale como se llama")
        val telefono = call.stringArg("telefono")?.trim()
        val correo = call.stringArg("correo")?.trim()
        if (telefono == null && correo == null) {
            throw ErpException("Falta un telefono o un correo: pídele uno para que ventas lo contacte")
        }
        return LeadWrite(
            name = nombre.take(150),
            companyName = call.stringArg("empresa")?.trim()?.take(150),
            phone = telefono?.take(40),
            email = correo?.take(150),
            interest = call.stringArg("interes")?.trim()?.take(80),
            message = call.stringArg("mensaje")?.trim()?.take(1000),
            wantsDemo = esVerdadero(call.arguments["demo"]),
            // Los canales de WhatsApp se reconocen tambien por la cuenta (wa:…).
            source = if (ToolCatalog.conStickers(contexto)) "tino_whatsapp" else origenProspecto(contexto.channel.kind),
            sourceRef = "conv:$conversationId",
        )
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
    private fun ocultarPrecios(contexto: ExecutionContext): Boolean =
        ocultaPrecios(playbooks.para(contexto), contexto.channel.kind)

    private fun conExistencias(t: Long, conversationId: Long, productos: List<ProductSummary>): List<ProductSummary> =
        productos.map { p ->
            // Bajo pedido: no hay existencias que consultar.
            if (p.madeToOrder == true) return@map p
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

/**
 * El cliente no debe oír precios de catálogo cuando el playbook solo permite
 * el total (`limites.mostrar_precios = "total"`). Un vendedor sí los ve.
 */
internal fun ocultaPrecios(playbook: Playbook, kindCanal: String): Boolean =
    playbook.limites.soloTotal && playbook.audiencia(kindCanal) != Playbook.AUDIENCIA_VENDEDOR

/** De donde vino el prospecto, segun el canal de la conversacion. */
internal fun origenProspecto(kindCanal: String): String = when (kindCanal.lowercase()) {
    "web" -> "tino_web"
    "whatsapp" -> "tino_whatsapp"
    else -> "manual"
}

/** El modelo a veces manda booleanos como texto ("true", "si"). */
internal fun esVerdadero(valor: Any?): Boolean = when (valor) {
    is Boolean -> valor
    is String -> valor.trim().lowercase() in setOf("true", "si", "sí", "1", "yes")
    is Number -> valor.toInt() != 0
    else -> false
}
