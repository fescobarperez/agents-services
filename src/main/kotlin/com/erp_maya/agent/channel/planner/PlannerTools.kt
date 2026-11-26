package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.api.dto.AgentEvent
import com.erp_maya.agent.channel.web.WebCarts
import com.erp_maya.agent.channel.web.WebException
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.prompt.service.ContextoDeCanal
import com.erp_maya.agent.tools.domain.ToolCall
import com.erp_maya.agent.tools.domain.ToolResult
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Las herramientas `planificador.*` del agente: crean opciones, cambian el
 * presupuesto, anotan detalles y proponen respuestas rapidas sobre la sesion
 * que atiende la conversacion.
 *
 * El canal y la sesion salen del turno (la conversacion del planificador se
 * abre con la sesion como `external_ref`), nunca de lo que mande el modelo.
 * Todo pasa por el mismo nucleo de [PlannerService] que usa el widget, con sus
 * mismas validaciones y precios.
 */
@Singleton
open class PlannerTools(private val planner: PlannerService) {

    /** El resultado para el modelo y, si propone respuestas rapidas, la tarjeta que las lleva al widget. */
    open fun ejecutar(contexto: ExecutionContext, conversationId: Long, call: ToolCall): Pair<ToolResult, AgentEvent.Card?> {
        if (contexto.channel.kind != CANAL) return ToolResult.Failed(call.name, "Esta herramienta solo existe en el planificador") to null
        val w = planner.widgetDeCanal(contexto.channel.id)?.takeIf { it.tenantId == contexto.tenantId }
            ?: return ToolResult.Failed(call.name, "El planificador de este canal no existe") to null
        val s = planner.sesionDeConversacion(w, conversationId)
            ?: return ToolResult.Failed(call.name, "La sesión del visitante ya venció: pídele que empiece un diseño nuevo") to null
        return try {
            when (call.name) {
                TIPO -> ToolResult.Ok(call.name, planner.tipoDe(w, call.stringArg("tipo") ?: s.projectType ?: falta("tipo"))) to null
                PRODUCTOS -> ToolResult.Ok(call.name, productos(w, call)) to null
                OPCION -> ToolResult.Ok(call.name, opcion(w, s, call)) to null
                ACTIVAR -> {
                    val letra = call.stringArg("letra") ?: falta("letra")
                    planner.activar(w, s, letra)
                    ToolResult.Ok(call.name, mapOf("activa" to letra.trim().uppercase())) to null
                }
                RENDER -> {
                    val letra = call.stringArg("letra") ?: s.activeLetter ?: falta("letra")
                    planner.pedirRender(w, s, letra, null)
                    val r = planner.estado(w, planner.sesion(w, s.id)).renders
                    ToolResult.Ok(call.name, mapOf(
                        "render" to "pedido para la opción ${letra.trim().uppercase()}",
                        "usados" to r.used, "de" to r.max,
                        "instruccion" to "Dile que en unos segundos aparece en pantalla. No la describas: todavía no existe.",
                    )) to null
                }
                PRESUPUESTO -> {
                    val monto = monto(call.arguments["monto"]) ?: falta("monto")
                    planner.actualizarSesion(w, s, PlannerUpdateIn(budget = monto))
                    ToolResult.Ok(call.name, mapOf("presupuesto" to PlannerService.presupuesto(monto))) to null
                }
                ANOTAR -> {
                    val clave = call.stringArg("clave")?.trim()?.lowercase() ?: falta("clave")
                    planner.actualizarSesion(w, s, PlannerUpdateIn(notes = mapOf(clave to (call.stringArg("valor") ?: ""))))
                    ToolResult.Ok(call.name, mapOf("anotado" to clave)) to null
                }
                PREGUNTAR -> preguntar(w, s, call)
                else -> ToolResult.Failed(call.name, "La herramienta '${call.name}' no existe") to null
            }
        } catch (e: WebException) {
            // Lo que el visitante pidio no se puede (medida fuera de rango, maximo
            // de opciones, ERP caido): el modelo se lo explica con este motivo.
            log.info("planificador {}: {} no se aplico: {}", w.publicKey, call.name, e.message)
            ToolResult.Failed(call.name, e.message) to null
        }
    }

    /** Crea la opcion y le devuelve al modelo lo que necesita para contarla: total, semaforo y lo que quedo por defecto. */
    private fun opcion(w: PlannerWidget, s: PlannerSessionRow, call: ToolCall): Map<String, Any?> {
        val medidas = pares(call.stringArg("medidas")).mapValues { (k, v) ->
            v.replace(',', '.').toBigDecimalOrNull() ?: throw WebException(400, "BAD_SPEC", "La medida $k=$v no es un número")
        }
        val letra = planner.crearOpcion(w, s, PlannerOptionIn(
            from = call.stringArg("desde"),
            projectType = call.stringArg("tipo"),
            params = medidas.takeIf { it.isNotEmpty() },
            choices = pares(call.stringArg("opciones")).takeIf { it.isNotEmpty() },
            preset = call.arguments["paquete"]?.toString(),
            quantity = call.decimalArg("cantidad"),
            title = call.stringArg("titulo"),
        ))
        val ahora = planner.sesion(w, s.id)
        val fila = planner.opciones(ahora).first { it.letter == letra }
        val vista = PlannerService.vista(fila, ahora.budget, w.fitThresholdPct)
        val moneda = WebCarts.moneda(w.locale)
        val plantilla = planner.plantillaDe(w, fila.projectType)
        val porDefecto = plantilla?.options.orEmpty()
            .filter { o -> o.code !in fila.spec.choices && fila.spec.preset?.let { p -> plantilla!!.presets.firstOrNull { it.code == p }?.choices?.containsKey(o.code) } != true }
            .mapNotNull { o -> PlannerSpecs.eleccion(fila.spec, plantilla!!, o)?.let { c -> "${o.code} (${o.label}): ${o.choices.firstOrNull { it.code == c }?.label ?: c}" } }
        return mapOf(
            "letra" to letra,
            "titulo" to fila.title,
            "total" to fila.total?.let { PlannerPrompt.dinero(it, moneda) },
            "precio" to if (fila.pricing == PlannerService.PRICING_REVIEW) "por confirmar: lo confirma un asesor" else "total final estimado",
            "semaforo" to PlannerPrompt.linea(fila, ahora.budget, w.fitThresholdPct, moneda).substringAfter(" — "),
            "sugerencia" to vista.suggestion?.let { "${it.label} = ${PlannerPrompt.dinero(it.total, moneda)}${if (it.fits) " (encaja)" else " (aun se pasa)"}" },
            "porDefecto" to porDefecto,
        )
    }

    /** Las categorias, o los productos de una: lo justo para que el modelo proponga uno por su tipo. */
    private fun productos(w: PlannerWidget, call: ToolCall): Any {
        val categoria = call.arguments["categoria"]?.toString()?.trim()?.toLongOrNull()
            ?: return mapOf("categorias" to planner.categoriasVisibles(w).map { mapOf("id" to it.id, "nombre" to it.name) })
        val moneda = WebCarts.moneda(w.locale)
        return mapOf("productos" to planner.productosDe(w, categoria).map { p ->
            mapOf(
                "tipo" to p.type,
                "nombre" to p.name,
                "precio" to (p.price?.let { "${PlannerPrompt.dinero(it, moneda)} por ${p.unit ?: "unidad"} (referencia)" } ?: "por confirmar"),
                "resumen" to p.summary,
            )
        })
    }

    /** Botones bajo el mensaje: las elecciones de una opcion de la plantilla, o textos cortos. */
    private fun preguntar(w: PlannerWidget, s: PlannerSessionRow, call: ToolCall): Pair<ToolResult, AgentEvent.Card?> {
        val codigo = call.stringArg("opcion")?.trim()
        val items: List<Map<String, Any?>> = if (codigo != null) {
            val tipo = s.projectType ?: falta("tipo de proyecto")
            val plantilla = planner.plantillaDe(w, tipo) ?: throw WebException(400, "BAD_SPEC", "Este tipo de proyecto no tiene opciones de diseño")
            val o = plantilla.options.firstOrNull { it.code == codigo }
                ?: throw WebException(400, "BAD_SPEC", "La plantilla no tiene la opción «$codigo»")
            o.choices.take(MAX_RESPUESTAS).map { mapOf("label" to it.label, "text" to null, "change" to mapOf(o.code to it.code)) }
        } else {
            (call.stringArg("respuestas") ?: falta("opcion o respuestas")).split(';')
                .map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_RESPUESTAS)
                .map { mapOf("label" to it.take(LARGO_RESPUESTA), "text" to it.take(LARGO_RESPUESTA), "change" to null) }
        }
        if (items.isEmpty()) throw WebException(400, "BAD_SPEC", "No hay respuestas que mostrar")
        return ToolResult.Ok(call.name, mapOf("botones" to items.map { it["label"] })) to
            AgentEvent.Card(CARD_RESPUESTAS, mapOf("items" to items))
    }

    private fun falta(dato: String): Nothing = throw WebException(400, "BAD_SPEC", "Falta $dato")

    companion object {
        const val CANAL = "planner"
        const val PREFIJO = "planificador."
        const val TIPO = "planificador.tipo"
        const val PRODUCTOS = "planificador.productos"
        const val OPCION = "planificador.opcion"
        const val ACTIVAR = "planificador.activar"
        const val PRESUPUESTO = "planificador.presupuesto"
        const val RENDER = "planificador.render"
        const val ANOTAR = "planificador.anotar"
        const val PREGUNTAR = "planificador.preguntar"

        /** Las que cambian la sesion: tras una de ellas el modelo puede anunciar el cambio. */
        val MUTANTES = setOf(OPCION, ACTIVAR, PRESUPUESTO, ANOTAR, RENDER)

        /** La tarjeta con las respuestas rapidas; el chat web la ignora, el planificador la pinta. */
        const val CARD_RESPUESTAS = "planner_replies"
        const val MAX_RESPUESTAS = 3
        const val LARGO_RESPUESTA = 40

        /**
         * Un monto como lo manda el modelo: numero, o texto como «Q 25,000».
         * En un monto la coma es de miles (25,000 = veinticinco mil), no decimal.
         */
        fun monto(v: Any?): BigDecimal? = when (v) {
            is BigDecimal -> v
            is Number -> BigDecimal(v.toString())
            is String -> v.replace(Regex("[^0-9.]"), "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
            else -> null
        }

        /** "ancho=4; profundidad=3" → {ancho: 4, profundidad: 3}. */
        fun pares(texto: String?): Map<String, String> =
            texto.orEmpty().split(';', '\n').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i).trim().lowercase() to p.substring(i + 1).trim()
            }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()

        /** Las respuestas rapidas de la tarjeta (en memoria o releida del JSON del turno). */
        fun respuestas(data: Map<String, Any?>): List<PlannerQuickReply> =
            (data["items"] as? List<*>).orEmpty().mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                val label = m["label"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val cambio = (m["change"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
                PlannerQuickReply(label, m["text"]?.toString(), cambio?.takeIf { it.isNotEmpty() })
            }

        private val log = LoggerFactory.getLogger(PlannerTools::class.java)
    }
}

/** El estado del planificador en el prompt de cada turno de su agente. */
@Singleton
open class PlannerAgentContext(private val planner: PlannerService) : ContextoDeCanal {

    override fun para(contexto: ExecutionContext, conversationId: Long): String? {
        if (contexto.channel.kind != PlannerTools.CANAL) return null
        val w = planner.widgetDeCanal(contexto.channel.id)?.takeIf { it.tenantId == contexto.tenantId } ?: return null
        val s = planner.sesionDeConversacion(w, conversationId) ?: return null
        val plantilla = s.projectType?.let { t -> runCatching { planner.plantillaDe(w, t) }.getOrNull() }
        val tipo = s.projectType?.let { t -> runCatching { planner.tipoProyecto(w, t) }.getOrNull() }
        val categorias = if (w.categoryIds.isEmpty()) emptyList() else planner.categoriasVisibles(w)
        return PlannerPrompt.texto(w, s, planner.opciones(s), plantilla, WebCarts.moneda(w.locale), planner.limiteRenders(w), tipo, categorias)
    }
}
