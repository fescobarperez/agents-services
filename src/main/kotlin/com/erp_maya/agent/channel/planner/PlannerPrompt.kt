package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.domain.ProductTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Lo que el agente del planificador sabe en cada turno: lo que el visitante
 * tiene en pantalla (presupuesto, opciones con su semaforo, detalles) y como
 * se trabaja con las herramientas. Sin IO: lo arma [PlannerAgentContext].
 *
 * El tono y el rol los pone el prompt del tipo de agente y el playbook (se
 * editan en la consola); aqui va solo lo que depende del codigo: el estado y
 * el uso de las herramientas `planificador.*`.
 */
object PlannerPrompt {

    fun texto(
        w: PlannerWidget,
        s: PlannerSessionRow,
        opciones: List<PlannerOptionRow>,
        plantilla: ProductTemplate?,
        currency: String,
        /** Renders por diseño (0 = el planificador no hace renders). */
        rendersMax: Int = 0,
        /** El tipo o producto que tiene elegido (resuelto: un producto de la lista no esta en `w.projectTypes`). */
        tipoActivo: ProjectType? = null,
        /** Categorias para elegir producto de una lista. */
        categorias: List<PlannerCategoryView> = emptyList(),
    ): String = buildString {
        val conContacto = w.contactWhatsapp != null
        val m = { v: BigDecimal -> dinero(v, currency) }
        appendLine("Planificador por presupuesto: lo que el visitante ve ahora en pantalla.")
        appendLine("- Presupuesto: ${s.budget?.let(m) ?: "aun no lo indica (preguntalo si hace falta para orientar)"}")
        val tipo = tipoActivo ?: w.projectTypes.firstOrNull { it.code == s.projectType }
        val fijos = if (w.projectTypes.isEmpty()) "" else " Tipos fijos: ${w.projectTypes.joinToString(", ") { "${it.code}=${it.label}" }}."
        val deLista = if (categorias.isEmpty()) "" else " Categorias (elige producto de la lista o con planificador.productos): " +
            categorias.joinToString(", ") { "${it.name} (${it.id})" } + "."
        appendLine("- Proyecto elegido: ${tipo?.let { "${it.label} (${it.code})" + (it.unit?.let { u -> ", se vende por $u" } ?: "") } ?: "aun no elige"}.$fijos$deLista")
        tipo?.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
            appendLine("- De que se trata (ficha del producto): ${it.replace(Regex("\\s+"), " ").take(LARGO_FICHA)}")
        }
        if (s.notes.isNotEmpty()) appendLine("- Notas: ${s.notes.entries.joinToString("; ") { "${it.key}=${it.value}" }}")
        if (opciones.isEmpty()) {
            appendLine("- Opciones: ninguna todavia.")
        } else {
            appendLine("- Opciones (activa: ${s.activeLetter ?: "-"}; maximo ${w.maxOptions}):")
            opciones.forEach { o -> appendLine("  ${linea(o, s.budget, w.fitThresholdPct, currency)}") }
        }
        if (rendersMax > 0) {
            val usados = opciones.count { it.renderStatus == PlannerService.RENDER_EN_CAMINO || it.renderStatus == PlannerService.RENDER_LISTO }
            val con = opciones.filter { it.renderStatus == PlannerService.RENDER_LISTO }.map { it.letter }
            appendLine("- Renders de este diseño: $usados de $rendersMax" + (if (con.isEmpty()) "" else "; ya tienen render: ${con.joinToString(", ")}") +
                (if (s.photoMediaId != null) ". Subio foto de su espacio (el render sale sobre ella)." else ". Sin foto de su espacio (puede subirla para que el render salga sobre ella)."))
        }
        plantilla?.let { appendLine(definicion(it)) }
        appendLine()
        appendLine("Como trabajas en el planificador:")
        appendLine("1. Cuando el visitante describe su idea, crea la primera opcion con planificador.opcion con lo que dijo; lo que no dijo queda por defecto. No esperes a tenerlo todo." +
            " Si el producto se vende por m², pide el largo y el fondo en metros y mandalos como medidas (ancho y profundidad): el area se calcula sola. Si se vende por unidad, usa cantidad.")
        appendLine("2. El visitante ve UNA sola propuesta: la opcion activa. Cada cambio que pida es una opcion NUEVA con desde=<letra activa> (nunca reemplaces ni borres): " +
            "queda activa y es la que ve. No le hables de opciones ni de letras (A, B…): di «tu propuesta». Si quiere volver a como estaba antes, usa planificador.activar.")
        appendLine("3. Despues de armar o cambiar la propuesta, pregunta UNA cosa importante que haya quedado por defecto (por ejemplo una o dos aguas) con planificador.preguntar(opcion=code).")
        appendLine("4. Los precios son totales: ya llevan IVA, cargos e instalacion configurada. Nunca des desglose ni inventes cifras: usa solo los totales de las herramientas.")
        appendLine("5. Si la propuesta se pasa del presupuesto, di por cuanto y ofrece la sugerencia con su precio. Si el precio esta por confirmar, di que un asesor lo confirma.")
        appendLine("6. Si dice su presupuesto en el chat, guardalo con planificador.presupuesto. Lo que no cambia el precio (uso, ubicacion) va con planificador.anotar.")
        appendLine("7. Mensajes de una a tres frases. No narres el proceso (nada de 'estoy calculando').")
        if (rendersMax > 0) {
            appendLine("8. El render se pide con el boton «Ver render» de su propuesta, o con planificador.render si lo pide en el chat. Hay $rendersMax por diseño: " +
                "antes del primero confirma lo que suele olvidarse (por ejemplo una o dos aguas) para no gastarlo. Si se pasa del presupuesto, igual se puede.")
        } else {
            appendLine("8. Este planificador no hace imagenes del diseño: no las ofrezcas.")
        }
        if (conContacto) {
            appendLine("9. Aqui no se crean cotizaciones. Cuando quiera avanzar, cotizar o hablar con alguien, indicale el boton «Contactar a un asesor de ventas»: " +
                "abre el WhatsApp de la empresa con su propuesta ya escrita (la que tiene en pantalla y su ultimo render).")
        } else {
            appendLine("9. Aqui no se crean cotizaciones: si quiere avanzar, dile que un asesor de la empresa lo atiende.")
        }
    }.trimEnd()

    /** «B · Dos aguas, teja + luces — Q 24,800 · se pasa por Q 4,800 · sugerencia …». */
    fun linea(o: PlannerOptionRow, presupuesto: BigDecimal?, umbral: BigDecimal, currency: String): String {
        val s = PlannerBudget.evaluar(o.total, o.pricing == PlannerService.PRICING_REVIEW, presupuesto, umbral)
        val estado = when (s.status) {
            PlannerBudget.FITS -> "encaja, sobran ${dinero(s.remaining!!, currency)}"
            PlannerBudget.TIGHT -> "justo en el presupuesto"
            PlannerBudget.OVER -> "se pasa por ${dinero(s.excess!!, currency)}"
            PlannerBudget.REVIEW -> "precio por confirmar (lo confirma un asesor)"
            else -> "sin presupuesto para comparar"
        }
        val sugerencia = if (s.status == PlannerBudget.OVER) PlannerBudget.sugerencia(o.variants, o.total, presupuesto) else null
        return buildString {
            append(o.letter).append(" · ").append(o.title)
            o.parentLetter?.let { append(" (desde ").append(it).append(")") }
            append(" — ").append(o.total?.let { dinero(it, currency) } ?: "por confirmar").append(" · ").append(estado)
            sugerencia?.let { append(" · sugerencia: ").append(it.variant.label).append(" = ").append(dinero(it.variant.total!!, currency)) }
            append(" · ").append(o.details.joinToString("; ") { "${it.label}: ${it.value}" })
            append(" · codes: ").append(codes(o.spec))
        }
    }

    /** Medidas, opciones y paquetes de la plantilla con sus code, para usarlos en planificador.opcion. */
    fun definicion(p: ProductTemplate): String = buildString {
        append("- Plantilla del tipo activo. Medidas: ")
        append(p.params.joinToString("; ") { x ->
            val rango = listOfNotNull(x.min?.let { "min ${plano(it)}" }, x.max?.let { "max ${plano(it)}" }, x.defaultValue?.let { "por defecto ${plano(it)}" })
            "${x.code} (${x.label}${x.unit?.let { ", $it" }.orEmpty()}${if (rango.isEmpty()) "" else ", " + rango.joinToString(", ")})"
        }.ifEmpty { "ninguna" })
        append(". Opciones: ")
        append(p.options.joinToString(" | ") { o ->
            "${o.code} (${o.label}${if (o.required == true) ", obligatoria" else ""}): " +
                o.choices.joinToString(", ") { c -> "${c.code}=${c.label}${if (c.isDefault == true) " [por defecto]" else ""}" }
        }.ifEmpty { "ninguna" })
        if (p.presets.isNotEmpty()) append(". Paquetes: ").append(p.presets.joinToString(", ") { "${it.code}=${it.label}" })
    }

    private fun codes(spec: OptionSpec): String =
        (spec.params.map { "${it.key}=${plano(it.value)}" } + spec.choices.map { "${it.key}=${it.value}" } +
            listOfNotNull(spec.preset?.let { "paquete=$it" }))
            .joinToString(", ").ifEmpty { "todo por defecto" }

    /** «Q 16,400» (con centavos solo si los hay). */
    fun dinero(v: BigDecimal, currency: String): String {
        val simbolo = if (currency == "GTQ") "Q" else currency
        val entero = v.stripTrailingZeros().scale() <= 0
        val f = DecimalFormat(if (entero) "#,##0" else "#,##0.00", DecimalFormatSymbols(Locale.US))
        return "$simbolo ${f.format(v.setScale(if (entero) 0 else 2, RoundingMode.HALF_UP))}"
    }

    private fun plano(v: BigDecimal) = v.stripTrailingZeros().toPlainString()

    /** Hasta donde se copia la ficha del producto en el prompt de cada turno. */
    private const val LARGO_FICHA = 700
}
