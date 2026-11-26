package com.erp_maya.agent.channel.planner

import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * «Contactar a un asesor»: el mensaje que el visitante le manda a la empresa
 * por WhatsApp, ya escrito con lo que armo en el planificador. No se crea
 * cotizacion ni prospecto en el ERP: la conversacion sigue con el asesor.
 *
 * Lleva la opcion que tiene en pantalla con sus detalles y su semaforo, las
 * demas opciones que exploro, sus notas y el enlace a su ultimo render. Sin IO.
 */
object PlannerContacto {

    /** Tope del texto: un enlace wa.me muy largo falla en algunos telefonos. */
    const val MAX_TEXTO = 1500

    data class Textos(
        val saludo: String,
        val presupuesto: String,
        val proyecto: String,
        val opcion: String,
        val detalles: String,
        val notas: String,
        val otras: String,
        val render: String,
        val porConfirmar: String,
        val aviso: String,
        val referencia: String,
    )

    val ES = Textos(
        saludo = "Hola, armé este proyecto en el planificador de su página y quiero hablar con un asesor.",
        presupuesto = "Presupuesto", proyecto = "Proyecto", opcion = "Opción elegida", detalles = "Detalles",
        notas = "Notas", otras = "Otras opciones que vi", render = "Render", porConfirmar = "precio por confirmar",
        aviso = "Los precios son estimados del planificador.", referencia = "Ref.",
    )

    val EN = Textos(
        saludo = "Hi, I put together this project in the planner on your website and I'd like to talk to an advisor.",
        presupuesto = "Budget", proyecto = "Project", opcion = "Chosen option", detalles = "Details",
        notas = "Notes", otras = "Other options I looked at", render = "Render", porConfirmar = "price to be confirmed",
        aviso = "Prices are estimates from the planner.", referencia = "Ref.",
    )

    fun textos(lang: String) = if (lang == "en") EN else ES

    /**
     * El mensaje. `elegida` es la opcion que tiene en pantalla; `renderUrl`, el
     * enlace absoluto a su ultimo render (null si no tiene o si el servicio no
     * conoce su direccion publica).
     */
    fun mensaje(
        w: PlannerWidget,
        s: PlannerSessionRow,
        opciones: List<PlannerOptionRow>,
        elegida: PlannerOptionRow?,
        renderUrl: String?,
        renderLetra: String?,
        currency: String,
    ): String {
        val t = textos(w.lang)
        val m = { v: BigDecimal -> PlannerPrompt.dinero(v, currency) }
        val lineas = mutableListOf(t.saludo, "")
        s.budget?.let { lineas += "${t.presupuesto}: ${m(it)}" }
        w.projectTypes.firstOrNull { it.code == (elegida?.projectType ?: s.projectType) }?.let { lineas += "${t.proyecto}: ${it.label}" }
        elegida?.let { o ->
            lineas += "${t.opcion}: ${o.letter} · ${o.title} — ${o.total?.let(m) ?: t.porConfirmar}${estado(o, s.budget, w, currency)}"
            o.details.takeIf { it.isNotEmpty() }?.let { d -> lineas += "${t.detalles}: ${d.joinToString("; ") { "${it.label}: ${it.value}" }}" }
        }
        s.notes.takeIf { it.isNotEmpty() }?.let { n -> lineas += "${t.notas}: ${n.entries.joinToString("; ") { "${it.key}: ${it.value}" }}" }
        opciones.filter { it.letter != elegida?.letter }.takeIf { it.isNotEmpty() }?.let { otras ->
            lineas += "${t.otras}: ${otras.joinToString("; ") { "${it.letter} · ${it.total?.let(m) ?: t.porConfirmar}" }}"
        }
        renderUrl?.let { lineas += "${t.render}${renderLetra?.let { l -> " ($l)" }.orEmpty()}: $it" }
        lineas += ""
        lineas += t.aviso
        lineas += "${t.referencia} ${referencia(s.id)}"
        return lineas.joinToString("\n").let { if (it.length <= MAX_TEXTO) it else it.take(MAX_TEXTO - 1) + "…" }
    }

    /** « (encaja)» / « (se pasa por Q 4,800)»: lo mismo que ve en la tarjeta. */
    private fun estado(o: PlannerOptionRow, presupuesto: BigDecimal?, w: PlannerWidget, currency: String): String {
        val s = PlannerBudget.evaluar(o.total, o.pricing == PlannerService.PRICING_REVIEW, presupuesto, w.fitThresholdPct)
        val en = w.lang == "en"
        return when (s.status) {
            PlannerBudget.FITS -> if (en) " (fits my budget)" else " (encaja en mi presupuesto)"
            PlannerBudget.TIGHT -> if (en) " (right at my budget)" else " (justo en mi presupuesto)"
            PlannerBudget.OVER -> " (" + (if (en) "over by " else "se pasa por ") + PlannerPrompt.dinero(s.excess!!, currency) + ")"
            else -> ""
        }
    }

    /**
     * Referencia corta para que el asesor ubique el diseño. NO es la sesion
     * completa: esa funciona como llave para leer el diseño.
     */
    fun referencia(sessionId: String): String = sessionId.removePrefix(PlannerSessionRepository.PREFIJO).take(8).uppercase()

    /** El enlace de WhatsApp (click to chat) con el mensaje ya escrito. */
    fun enlace(numero: String, texto: String): String =
        "https://wa.me/$numero?text=" + URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20")

    /** El render mas reciente de la sesion (el de la cola mas nueva entre los listos). */
    fun ultimoRender(opciones: List<PlannerOptionRow>): PlannerOptionRow? =
        opciones.filter { it.renderStatus == PlannerService.RENDER_LISTO && it.renderMediaId != null }
            .maxByOrNull { it.renderJobId ?: 0L }
}
