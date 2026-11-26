package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.erp.domain.TemplateOption
import java.math.BigDecimal

/** Lo que se pide cambiar respecto de la opcion padre (o todo, si es la primera). */
data class OptionChanges(
    val params: Map<String, BigDecimal>? = null,
    val choices: Map<String, String>? = null,
    /** "" quita el paquete. */
    val preset: String? = null,
    val quantity: BigDecimal? = null,
)

/**
 * Reglas de una opcion, sin IO: combinar con la opcion padre, validar contra
 * la plantilla del producto, armar sus detalles y titulo, y proponer las
 * variantes que se valoran junto con ella.
 *
 * Se valida aqui y no solo en el ERP para devolverle al visitante (o al
 * agente) el dato exacto que esta mal, y para no gastar una llamada al ERP en
 * algo que va a rechazar.
 */
object PlannerSpecs {

    val CODIGO = Regex("^[a-z][a-z0-9_]{0,39}$")
    val MEDIDAS = listOf("ancho", "profundidad", "alto")
    const val MEDIDAS_KEY = "medidas"
    val MAX_CANTIDAD = BigDecimal("1000")
    const val MAX_VALORES = 30
    /** Unidades que no se escriben junto a la cantidad («2», no «2 Unidad»). */
    private val SIN_UNIDAD = setOf("unidad", "unidades", "unid", "u", "pieza", "piezas")

    /** Unidades que se cotizan por area: la cantidad es ancho × profundidad. */
    private val AREA = setOf("m²", "m2", "mt2", "mts2", "metro cuadrado", "metros cuadrados")

    /** ¿El producto se vende por metro cuadrado? */
    fun porArea(unidad: String?): Boolean = unidad?.trim()?.lowercase()?.replace("  ", " ") in AREA

    /**
     * La opcion nueva: la del padre (si es del mismo producto) con los cambios
     * encima. `porArea` (producto sin plantilla que se vende por m²): si cambian
     * las medidas y estan ancho y profundidad, la cantidad es su area.
     */
    fun combinar(productId: Long, plantilla: ProductTemplate?, padre: OptionSpec?, c: OptionChanges, porArea: Boolean = false): OptionSpec {
        val base = padre?.takeIf { it.productId == productId && it.templateId == plantilla?.id }
        val preset = when {
            c.preset != null -> c.preset.trim().takeIf { it.isNotEmpty() }
            else -> base?.preset
        }
        val params = base?.params.orEmpty() + c.params.orEmpty()
        val area = if (porArea && plantilla == null && c.params.orEmpty().keys.any { it == "ancho" || it == "profundidad" }) {
            val ancho = params["ancho"]
            val fondo = params["profundidad"]
            if (ancho != null && fondo != null && ancho.signum() > 0 && fondo.signum() > 0) {
                ancho.multiply(fondo).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            } else null
        } else null
        return OptionSpec(
            productId = productId,
            templateId = plantilla?.id,
            params = params,
            choices = base?.choices.orEmpty() + c.choices.orEmpty().mapValues { it.value.trim() },
            preset = preset,
            quantity = area ?: c.quantity ?: base?.quantity ?: BigDecimal.ONE,
        )
    }

    /** Lanza [PlannerSpecException] con el primer dato invalido. */
    fun validar(spec: OptionSpec, plantilla: ProductTemplate?) {
        if (spec.quantity.signum() <= 0 || spec.quantity > MAX_CANTIDAD) {
            throw PlannerSpecException("quantity", "La cantidad va de 1 a ${MAX_CANTIDAD.toPlainString()}")
        }
        if (spec.params.size > MAX_VALORES || spec.choices.size > MAX_VALORES) {
            throw PlannerSpecException("spec", "Demasiados datos en la opción")
        }
        spec.params.forEach { (k, v) ->
            if (v.signum() <= 0) throw PlannerSpecException("params.$k", "Las medidas deben ser mayores que cero")
        }
        if (plantilla == null) {
            spec.params.keys.firstOrNull { it !in MEDIDAS }?.let { throw PlannerSpecException("params.$it", "Este producto solo acepta alto, ancho y profundidad") }
            if (spec.choices.isNotEmpty() || spec.preset != null) {
                throw PlannerSpecException("choices", "Este producto no tiene opciones de diseño")
            }
            return
        }
        val porParam = plantilla.params.associateBy { it.code }
        spec.params.forEach { (k, v) ->
            val p = porParam[k] ?: throw PlannerSpecException("params.$k", "La plantilla no tiene la medida «$k»")
            val unidad = p.unit?.let { " $it" }.orEmpty()
            p.min?.let { if (v < it) throw PlannerSpecException("params.$k", "${p.label} debe ser al menos ${it.stripTrailingZeros().toPlainString()}$unidad") }
            p.max?.let { if (v > it) throw PlannerSpecException("params.$k", "${p.label} no puede pasar de ${it.stripTrailingZeros().toPlainString()}$unidad") }
        }
        plantilla.params.firstOrNull { it.required == true && it.code !in spec.params && it.defaultValue == null }
            ?.let { throw PlannerSpecException("params.${it.code}", "Falta ${it.label}") }

        val porOpcion = plantilla.options.associateBy { it.code }
        spec.choices.forEach { (k, v) ->
            val o = porOpcion[k] ?: throw PlannerSpecException("choices.$k", "La plantilla no tiene la opción «$k»")
            if (o.choices.none { it.code == v }) throw PlannerSpecException("choices.$k", "${o.label} no tiene «$v»")
        }
        if (spec.preset != null && plantilla.presets.none { it.code == spec.preset }) {
            throw PlannerSpecException("preset", "La plantilla no tiene el paquete «${spec.preset}»")
        }
        plantilla.options.firstOrNull { it.required == true && eleccion(spec, plantilla, it) == null }
            ?.let { throw PlannerSpecException("choices.${it.code}", "Falta elegir ${it.label}") }
    }

    /** La eleccion vigente de una opcion de la plantilla: la pedida, la del paquete o la de por defecto. */
    fun eleccion(spec: OptionSpec, plantilla: ProductTemplate, o: TemplateOption): String? =
        spec.choices[o.code]
            ?: spec.preset?.let { p -> plantilla.presets.firstOrNull { it.code == p }?.choices?.get(o.code) }
            ?: o.choices.firstOrNull { it.isDefault == true }?.code

    /**
     * «Detalles de tu proyecto»: medidas, una fila por opcion de diseño y la
     * cantidad si es mas de una. `changed` marca lo que difiere de la opcion
     * padre (el widget lo resalta).
     */
    fun detalles(spec: OptionSpec, plantilla: ProductTemplate?, padre: List<PlannerDetail>?, unidad: String? = null): List<PlannerDetail> {
        val out = mutableListOf<PlannerDetail>()
        val defectos = plantilla?.params.orEmpty().mapNotNull { p -> p.defaultValue?.let { p.code to it } }.toMap()
        val vigentes = defectos + spec.params
        val unidadMedidas = plantilla?.params?.firstOrNull { it.code in MEDIDAS }?.unit ?: "m"
        medidas(vigentes, unidadMedidas)?.let { out += PlannerDetail(MEDIDAS_KEY, "Medidas", it, PlannerDetail.SIZE) }
        plantilla?.params.orEmpty().filter { it.code !in MEDIDAS }.forEach { p ->
            vigentes[p.code]?.let { v ->
                out += PlannerDetail(p.code, p.label, v.stripTrailingZeros().toPlainString() + p.unit?.let { " $it" }.orEmpty(), PlannerDetail.PARAM)
            }
        }
        plantilla?.options.orEmpty().forEach { o ->
            val codigo = eleccion(spec, plantilla!!, o) ?: return@forEach
            o.choices.firstOrNull { it.code == codigo }?.let { out += PlannerDetail(o.code, o.label, it.label, PlannerDetail.CHOICE) }
        }
        // Sin plantilla y con unidad propia (m², metro lineal…), la cantidad va siempre y con su unidad.
        val conUnidad = plantilla == null && unidad != null && unidad.trim().lowercase() !in SIN_UNIDAD
        if (conUnidad || spec.quantity.compareTo(BigDecimal.ONE) != 0) {
            val valor = spec.quantity.stripTrailingZeros().toPlainString() + if (conUnidad) " ${unidad!!.trim()}" else ""
            out += PlannerDetail("cantidad", if (porArea(unidad)) "Área" else "Cantidad", valor, PlannerDetail.QUANTITY)
        }
        if (padre == null) return out
        return out.map { d -> d.copy(changed = padre.none { it.key == d.key && it.value == d.value }) }
    }

    /** «4 × 3 × 2.4 m»: ancho × profundidad × alto, con lo que haya. */
    fun medidas(params: Map<String, BigDecimal>, unidad: String): String? {
        val partes = MEDIDAS.mapNotNull { params[it] }
        if (partes.isEmpty()) return null
        return partes.joinToString(" × ") { it.stripTrailingZeros().toPlainString() } + " " + unidad
    }

    /** «Pérgola · Dos aguas, Pino tratado»: el tipo y las dos primeras elecciones. El agente puede ponerle otro. */
    fun titulo(tipo: ProjectType, detalles: List<PlannerDetail>, pedido: String?): String {
        pedido?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(120) }
        val elecciones = elecciones(detalles).take(2)
        val t = if (elecciones.isEmpty()) tipo.label else "${tipo.label} · ${elecciones.joinToString(", ")}"
        return t.take(120)
    }

    /** «Techo de policarbonato · 4 × 3 m»: lo que no cupo en el titulo y las medidas. */
    fun resumen(detalles: List<PlannerDetail>): String? =
        (elecciones(detalles).drop(2) + listOfNotNull(detalles.firstOrNull { it.kind == PlannerDetail.SIZE }?.value))
            .joinToString(" · ").take(200).takeIf { it.isNotEmpty() }

    private fun elecciones(detalles: List<PlannerDetail>) =
        detalles.filter { it.kind == PlannerDetail.CHOICE }.map { it.value }

    /**
     * Las variantes que se valoran junto con la opcion: cada una cambia UNA
     * eleccion. Primero las opciones no obligatorias (extras como luces, que
     * son lo primero que se quita), y dentro de cada una la eleccion por
     * defecto primero. Hasta `max`.
     */
    fun variantes(spec: OptionSpec, plantilla: ProductTemplate, max: Int): List<PlannerVariant> {
        if (max <= 0) return emptyList()
        val opciones = plantilla.options.sortedBy { if (it.required == true) 1 else 0 }
        return opciones.flatMap { o ->
            val actual = eleccion(spec, plantilla, o)
            o.choices.filter { it.code != actual }
                .sortedBy { if (it.isDefault == true) 0 else 1 }
                .map { c -> PlannerVariant(mapOf(o.code to c.code), "${o.label}: ${c.label}") }
        }.take(max)
    }

    /** A, B, C… */
    fun letra(indice: Int): String = ('A' + indice).toString()
}
