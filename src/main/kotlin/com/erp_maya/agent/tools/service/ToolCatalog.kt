package com.erp_maya.agent.tools.service

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.tools.domain.ToolDefinition
import com.erp_maya.agent.model.domain.ToolSchema
import com.erp_maya.agent.model.domain.ToolSchemaParam
import com.erp_maya.agent.tools.domain.ToolParameter
import jakarta.inject.Singleton

/**
 * Catalogo de herramientas.
 *
 * Se construye una vez al arrancar y se comparte entre todas las
 * conversaciones; nunca uno por conversacion. Lo que cambia por agente no es
 * el catalogo sino QUE herramientas tiene concedidas, y eso se filtra contra
 * `ai_agent_tools`.
 */
@Singleton
class ToolCatalog {

    private val todas: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "productos.search",
            mode = ToolMode.READ,
            description = "Busca productos por nombre (el ERP no busca por codigo ni SKU). Usa palabras del nombre.",
            parameters = listOf(
                ToolParameter("query", "string", true, "Texto a buscar"),
                ToolParameter("limit", "integer", false, "Maximo de resultados, por defecto 10"),
            ),
        ),
        ToolDefinition(
            name = "productos.stock",
            mode = ToolMode.READ,
            description = "Consulta la existencia disponible de un producto.",
            parameters = listOf(ToolParameter("productId", "integer", true, "Id del producto")),
        ),
        ToolDefinition(
            name = "clientes.find",
            mode = ToolMode.READ,
            description = "Busca un cliente por su numero de telefono.",
            parameters = listOf(ToolParameter("phone", "string", true, "Telefono del cliente")),
        ),
        ToolDefinition(
            name = "clientes.buscar",
            mode = ToolMode.READ,
            description = "Busca clientes por NIT (exacto) o por nombre (parcial). Devuelve id, nombre y NIT. " +
                "Si te dan NIT y nombre, busca primero por NIT; si no aparece, prueba por nombre.",
            parameters = listOf(
                ToolParameter("query", "string", true, "Un NIT o parte del nombre, no ambos juntos"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.cliente",
            mode = ToolMode.READ,
            description = "Fija el cliente de la cotizacion en curso (usa el id de clientes.buscar). " +
                "Si habia productos en espera, crea la cotizacion como prospecto.",
            parameters = listOf(ToolParameter("customerId", "integer", true, "Id del cliente")),
        ),
        ToolDefinition(
            name = "cotizacion.cliente_nuevo",
            mode = ToolMode.READ,
            description = "Registra como cliente a quien escribe (su numero se toma de la conversacion) y lo fija " +
                "en la cotizacion. Solo si el cliente no esta identificado. Si el NIT ya existe, usa ese cliente.",
            parameters = listOf(
                ToolParameter("nombre", "string", true, "Nombre completo o razon social"),
                ToolParameter("nit", "string", false, "NIT; CF si no tiene"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.agregar",
            mode = ToolMode.READ,
            description = "Agrega un producto a la cotizacion en curso (queda como prospecto, no se envia). " +
                "Si ya estaba, suma la cantidad. Si aun no hay cliente, la linea espera y hay que pedirlo.",
            parameters = listOf(
                ToolParameter("productId", "integer", true, "Id del producto (de productos.search)"),
                ToolParameter("quantity", "number", true, "Cantidad exacta que pidio el cliente (p. ej. 25), como numero"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.cantidad",
            mode = ToolMode.READ,
            description = "Cambia la cantidad de una linea de la cotizacion en curso; 0 la elimina.",
            parameters = listOf(
                ToolParameter("lineId", "integer", true, "Id de la linea"),
                ToolParameter("quantity", "number", true, "Nueva cantidad"),
            ),
        ),
        ToolDefinition(
            name = STICKER,
            mode = ToolMode.READ,
            description = "Envia un sticker animado de Tino junto a tu respuesta. Usalo SOLO en momentos clave y " +
                "como maximo uno por turno: 'saludo' al iniciar o retomar la conversacion con alguien; 'listo' al " +
                "crear la cotizacion o cerrar algo que el cliente pidio; 'buscando' solo si la busqueda sera larga " +
                "o compleja. No lo uses en respuestas rutinarias, ni repitas el mismo en mensajes seguidos.",
            parameters = listOf(
                ToolParameter("momento", "string", true, "Uno de: ${MOMENTOS_STICKER.joinToString(", ")}"),
            ),
        ),
        ToolDefinition(
            name = "empresa.get",
            mode = ToolMode.READ,
            description = "Devuelve moneda, impuesto y estado de la empresa.",
        ),
        ToolDefinition(
            name = "cotizaciones.preview",
            mode = ToolMode.READ,
            description = "Calcula el total de una cotizacion sin emitirla. " +
                "Es obligatorio mostrarla al cliente antes de emitir.",
            parameters = listOf(
                ToolParameter("customerId", "integer", false, "Id del cliente"),
                ToolParameter("lines", "array", true, "Lineas con productId y quantity"),
            ),
        ),
        ToolDefinition(
            name = "cotizaciones.issue",
            mode = ToolMode.WRITE,
            description = "Emite la cotizacion previamente mostrada y confirmada por el cliente.",
            parameters = listOf(
                ToolParameter("customerId", "integer", false, "Id del cliente"),
                ToolParameter("lines", "array", true, "Lineas con productId y quantity"),
                ToolParameter("total", "number", true, "Total confirmado, debe coincidir con el preview"),
            ),
        ),
    )

    private val porNombre = todas.associateBy { it.name }

    fun byName(name: String): ToolDefinition? = porNombre[name]

    /** Los esquemas que se le declaran al proveedor del modelo. */
    fun schemasFor(contexto: ExecutionContext): List<ToolSchema> =
        availableFor(contexto).map { d ->
            ToolSchema(
                name = d.name,
                description = d.description,
                parameters = d.parameters.map { ToolSchemaParam(it.name, it.type, it.required, it.description) },
            )
        }

    /**
     * Las que este agente tiene concedidas segun `ai_agent_tools`, menos las
     * que el canal no sabe pintar (los stickers solo existen en WhatsApp).
     */
    fun availableFor(contexto: ExecutionContext): List<ToolDefinition> =
        todas.filter { contexto.toolFor(it.name) != null && (it.name != STICKER || conStickers(contexto)) }

    companion object {
        const val STICKER = "tino.sticker"
        val MOMENTOS_STICKER = listOf("saludo", "listo", "buscando")

        fun conStickers(contexto: ExecutionContext): Boolean =
            contexto.channel.kind.equals("whatsapp", ignoreCase = true) || contexto.channel.accountRef.startsWith("wa:")
    }
}
