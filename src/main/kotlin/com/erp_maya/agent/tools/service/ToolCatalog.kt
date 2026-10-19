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
            description = "Busca productos por nombre o SKU (ignora mayusculas y tildes). Usa palabras del nombre; si no hay resultados, prueba con un sinonimo o una palabra mas corta.",
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
                "Si ya estaba en la misma medida, suma la cantidad. Si aun no hay cliente, la linea espera y hay que " +
                "pedirlo. Si el producto tiene medidas (dimensions) y el cliente pide otras, pasalas en alto, ancho " +
                "y profundidad; si lo quiere en la medida estandar, omitelas. No calcules ni prometas el precio de " +
                "una medida especial: lo confirma el asesor.",
            parameters = listOf(
                ToolParameter("productId", "integer", true, "Id del producto (de productos.search)"),
                ToolParameter("quantity", "number", true, "Cantidad exacta que pidio el cliente (p. ej. 25), como numero"),
                ToolParameter("alto", "number", false, "Alto que pide el cliente, en la unidad del producto (p. ej. 2.0)"),
                ToolParameter("ancho", "number", false, "Ancho que pide el cliente"),
                ToolParameter("profundidad", "number", false, "Profundidad que pide el cliente"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.diseno",
            mode = ToolMode.READ,
            description = "Anota o corrige la ficha de diseño de lo que el cliente quiere (sobre todo cuando manda fotos " +
                "o pide algo a la medida): tipo, descripcion, material, acabado, medidas y detalles como aguas o " +
                "cubierta. Lo que no mandes se conserva. Si las medidas salen de una foto usa origen_medidas=foto " +
                "(quedan sin confirmar); si el cliente las dijo, origen_medidas=cliente.",
            parameters = listOf(
                ToolParameter("tipo", "string", false, "Que es: pergola, deck, mueble…"),
                ToolParameter("descripcion", "string", false, "Una o dos frases con lo que quiere"),
                ToolParameter("material", "string", false, "Material"),
                ToolParameter("acabado", "string", false, "Acabado o color"),
                ToolParameter("alto", "number", false, "Alto en metros"),
                ToolParameter("ancho", "number", false, "Ancho en metros"),
                ToolParameter("profundidad", "number", false, "Profundidad en metros"),
                ToolParameter("origen_medidas", "string", false, "foto (estimadas) o cliente (las dijo el cliente)"),
                ToolParameter("detalles", "string", false, "Otros datos como 'aguas=1; cubierta=teja'. Un valor vacio borra el dato"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.confirmar_medidas",
            mode = ToolMode.READ,
            description = "Marca como confirmadas las medidas de la ficha de diseño. Solo funciona si el cliente dijo " +
                "que si en su ultimo mensaje, despues de que le mostraste las medidas.",
        ),
        ToolDefinition(
            name = "cotizacion.producto_nuevo",
            mode = ToolMode.READ,
            description = "Registra un producto que el cliente pide y NO esta en el catalogo (buscalo antes con " +
                "productos.search). Queda sin precio y pendiente de que un asesor lo revise; despues agregalo con " +
                "cotizacion.agregar y la linea queda por cotizar. Solo si pertenece a las lineas que vende la empresa.",
            parameters = listOf(
                ToolParameter("nombre", "string", true, "Nombre corto del producto, p. ej. 'Pergola de madera a 1 agua'"),
                ToolParameter("categoria", "string", true, "Linea de producto (categoria) a la que pertenece"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.render",
            mode = ToolMode.READ,
            description = "Genera una imagen de ejemplo de como quedaria lo que se cotiza, editando la foto del lugar " +
                "que mando el cliente. Usala cuando el cliente pida ver como quedaria, con la ficha de diseño completa " +
                "(tipo y medidas). Hay un limite de imagenes por cotizacion: junta todo antes de la primera. La imagen " +
                "llega aparte en unos segundos.",
            parameters = listOf(
                ToolParameter("foto", "integer", false, "Numero de la foto del lugar (1 = la primera que mando); si no, la del render anterior o la mas reciente"),
                ToolParameter("ajuste", "string", false, "El cambio que pidio el cliente respecto a la imagen anterior, si lo hay"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.aprobar_diseno",
            mode = ToolMode.READ,
            description = "El cliente dice que le gusta la ultima imagen de ejemplo: la anota como su diseño y la guarda " +
                "en la cotizacion para el asesor. Solo funciona si el cliente dijo que si en su ultimo mensaje.",
        ),
        ToolDefinition(
            name = "cotizacion.dato",
            mode = ToolMode.READ,
            description = "Guarda un dato que la cotizacion necesita (los pide el checklist de la cotizacion en " +
                "curso, p. ej. direccion de entrega). Usa exactamente la clave que indica el checklist.",
            parameters = listOf(
                ToolParameter("clave", "string", true, "Clave del dato, tal como aparece en el checklist"),
                ToolParameter("valor", "string", true, "Lo que dijo el cliente"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.listar",
            mode = ToolMode.READ,
            description = "Lista las cotizaciones del cliente identificado con su estado, si puede reabrirse y si " +
                "admite solicitudes de cambio. Usala cuando pregunte por una cotizacion anterior o quiera cambiarla.",
        ),
        ToolDefinition(
            name = "cotizacion.reabrir",
            mode = ToolMode.READ,
            description = "Reabre una cotizacion que el cliente ya habia terminado para modificarla. Solo si " +
                "cotizacion.listar dice puede_reabrirse=true y el cliente confirmo que quiere modificarla. Si un " +
                "asesor ya la tomo, el sistema lo rechaza: usa cotizacion.solicitar_cambio.",
            parameters = listOf(ToolParameter("quoteId", "integer", true, "quoteId de cotizacion.listar")),
        ),
        ToolDefinition(
            name = "cotizacion.solicitar_cambio",
            mode = ToolMode.READ,
            description = "Registra un pedido de cambio sobre una cotizacion que ya revisa un asesor (no la " +
                "modifica: el asesor decide). Una llamada por cada cambio. tipo: agregar, quitar, cantidad, " +
                "descuento, condiciones u otro.",
            parameters = listOf(
                ToolParameter("quoteId", "integer", true, "quoteId de cotizacion.listar"),
                ToolParameter("tipo", "string", true, "agregar | quitar | cantidad | descuento | condiciones | otro"),
                ToolParameter("productId", "integer", false, "Producto (de productos.search) para agregar, quitar o cantidad"),
                ToolParameter("cantidad", "number", false, "Cantidad para agregar o la nueva cantidad"),
                ToolParameter("descuento", "number", false, "Porcentaje de descuento que pide el cliente"),
                ToolParameter("detalle", "string", false, "Lo que pidio el cliente, con sus palabras"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.aprobar",
            mode = ToolMode.READ,
            description = "El cliente aprueba por texto la cotizacion que su asesor le ENVIO (estado enviada). " +
                "Primero preguntale si confirma con numero y total; llamala cuando responda que si. El mensaje " +
                "que devuelve es el cierre: despues el proceso de cotizacion termino.",
            parameters = listOf(ToolParameter("quoteId", "integer", true, "quoteId de cotizacion.listar")),
        ),
        ToolDefinition(
            name = "cotizacion.rechazar",
            mode = ToolMode.READ,
            description = "El cliente rechaza por texto la cotizacion enviada. Pide confirmacion antes. El motivo " +
                "es OPCIONAL: pasalo solo si el cliente lo dijo, nunca lo exijas.",
            parameters = listOf(
                ToolParameter("quoteId", "integer", true, "quoteId de cotizacion.listar"),
                ToolParameter("motivo", "string", false, "precio | plazo_entrega | compro_en_otro_lugar | ya_no_lo_necesita | otro"),
                ToolParameter("nota", "string", false, "Lo que dijo el cliente, con sus palabras"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.motivo_rechazo",
            mode = ToolMode.READ,
            description = "Guarda el motivo que el cliente cuenta DESPUES de haber rechazado una cotizacion.",
            parameters = listOf(
                ToolParameter("quoteId", "integer", true, "quoteId de la cotizacion rechazada"),
                ToolParameter("motivo", "string", false, "precio | plazo_entrega | compro_en_otro_lugar | ya_no_lo_necesita | otro"),
                ToolParameter("nota", "string", false, "Lo que dijo el cliente, con sus palabras"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.solicitudes",
            mode = ToolMode.READ,
            description = "Las solicitudes de cambio de una cotizacion con su estado, el motivo y lo que respondio el " +
                "asesor. Usala cuando el cliente pregunte que paso con lo que pidio o por que se rechazo.",
            parameters = listOf(ToolParameter("quoteId", "integer", true, "quoteId de cotizacion.listar")),
        ),
        ToolDefinition(
            name = "cotizacion.consultar",
            mode = ToolMode.READ,
            description = "Le pasa al asesor una pregunta del cliente sobre la respuesta a una solicitud (p. ej. por " +
                "que se rechazo) cuando el motivo registrado no le basta. El asesor responde por este medio.",
            parameters = listOf(
                ToolParameter("quoteId", "integer", true, "quoteId de la cotizacion"),
                ToolParameter("solicitudId", "integer", true, "solicitudId del aviso reciente o de cotizacion.solicitudes"),
                ToolParameter("tipo", "string", true, "Tipo de esa solicitud (agregar, quitar, cantidad, descuento…), para verificar que es la correcta"),
                ToolParameter("pregunta", "string", true, "Lo que pregunta el cliente, con sus palabras"),
            ),
        ),
        ToolDefinition(
            name = "cotizacion.nueva",
            mode = ToolMode.READ,
            description = "Deja la cotizacion en curso como esta y empieza una nueva para el mismo cliente. Usala " +
                "cuando el cliente pida algo que no va en la cotizacion abierta (pregunta antes si hay una).",
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
            name = ENVIAR,
            mode = ToolMode.READ,
            description = "Da por terminada la cotizacion en curso (pasa a revision de un asesor) y le envia el PDF " +
                "preliminar al cliente. Usala SOLO con el checklist completo y cuando el cliente confirme. Dile que " +
                "un agente de ventas la revisara y aprobara. Despues ya no la modificas: si quiere cambiarla, " +
                "cotizacion.reabrir (si nadie la tomo) o cotizacion.solicitar_cambio.",
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
     * que solo existen en WhatsApp (stickers, enviar el PDF al cliente).
     */
    fun availableFor(contexto: ExecutionContext): List<ToolDefinition> =
        todas.filter { contexto.toolFor(it.name) != null && (it.name !in SOLO_WHATSAPP || conStickers(contexto)) }

    companion object {
        const val STICKER = "tino.sticker"
        const val ENVIAR = "cotizacion.enviar"

        /** Herramientas que solo tienen sentido con un cliente al otro lado del chat. */
        const val RENDER = "cotizacion.render"
        const val APROBAR_DISENO = "cotizacion.aprobar_diseno"
        val SOLO_WHATSAPP = setOf(STICKER, ENVIAR, RENDER, APROBAR_DISENO)
        val MOMENTOS_STICKER = listOf("saludo", "listo", "buscando")

        fun conStickers(contexto: ExecutionContext): Boolean =
            contexto.channel.kind.equals("whatsapp", ignoreCase = true) || contexto.channel.accountRef.startsWith("wa:")
    }
}
