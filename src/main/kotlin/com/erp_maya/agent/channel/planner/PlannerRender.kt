package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.domain.ProductTemplate

/**
 * Instrucciones para el modelo de imagenes del render de una opcion.
 *
 * Se arman SOLO desde la opcion: lo que se ve es lo que se cotiza. Con
 * plantilla, cada eleccion aporta su `renderText` (lo que la empresa escribio
 * para dibujarla); sin el, su etiqueta. Con foto del espacio se edita la foto
 * real; sin ella se genera una escena neutra con lo cotizado al centro.
 */
object PlannerRender {

    fun instrucciones(
        tipo: ProjectType,
        o: PlannerOptionRow,
        plantilla: ProductTemplate?,
        notas: Map<String, String>,
        conFoto: Boolean,
    ): String = buildString {
        if (conFoto) {
            append("Edita esta foto real del espacio del cliente para mostrar cómo quedaría su proyecto. Resultado fotorrealista, ")
            append("con la misma luz, perspectiva y escala de la foto.\n")
        } else {
            append("Genera una imagen fotorrealista del proyecto terminado, en un espacio residencial sencillo y bien iluminado, ")
            append("con el proyecto como protagonista y vista de tres cuartos.\n")
        }
        append("Qué mostrar: ").append(plantilla?.productName ?: tipo.label).append(".\n")
        if (plantilla == null) ficha(tipo.description)?.let { append("Cómo es (ficha del producto): ").append(it).append("\n") }
        elecciones(o, plantilla).takeIf { it.isNotEmpty() }?.let { append("Diseño: ").append(it.joinToString("; ")).append(".\n") }
        o.details.firstOrNull { it.kind == PlannerDetail.SIZE }?.let { append("Medidas aproximadas (ancho × fondo × alto): ").append(it.value).append(".\n") }
        o.details.filter { it.kind == PlannerDetail.PARAM }.takeIf { it.isNotEmpty() }?.let { ps ->
            append(ps.joinToString("; ") { "${it.label}: ${it.value}" }).append(".\n")
        }
        o.details.firstOrNull { it.kind == PlannerDetail.QUANTITY }?.let { append(it.label).append(": ").append(it.value).append(".\n") }
        notas.takeIf { it.isNotEmpty() }?.let { append("Contexto del cliente: ").append(it.entries.joinToString("; ") { e -> "${e.key}: ${e.value}" }).append(".\n") }
        if (conFoto) append("Si el cliente dibujó marcas en la foto, coloca el proyecto en esa zona y borra las marcas.\n")
        append("Reglas: muestra SOLO lo descrito. No agregues muebles, cojines, plantas, alfombras, lámparas, decoración ni personas")
        append(if (conFoto) ". No cambies la casa, las ventanas, el jardín ni lo que ya existe." else ".")
        append(" Sin textos, logotipos ni marcas de agua.")
    }

    /**
     * De la descripcion del producto, lo que se dibuja: el primer parrafo (que
     * es) y las lineas de materiales y estilo. Sin beneficios ni precios.
     */
    fun ficha(descripcion: String?): String? {
        val texto = descripcion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lineas = texto.lines().map { it.trim() }
        val extra = lineas.filter { it.startsWith("Materiales:", true) || it.startsWith("Estilo:", true) }
        return (listOf(texto.substringBefore("\n\n").replace(Regex("\\s+"), " ")) + extra)
            .distinct().joinToString(" ").take(LARGO_FICHA)
    }

    private const val LARGO_FICHA = 500

    /** Cada eleccion vigente: su renderText si la empresa lo escribio; si no, «Opcion: Eleccion». */
    fun elecciones(o: PlannerOptionRow, plantilla: ProductTemplate?): List<String> {
        if (plantilla == null) return emptyList()
        return plantilla.options.mapNotNull { op ->
            val codigo = PlannerSpecs.eleccion(o.spec, plantilla, op) ?: return@mapNotNull null
            val c = op.choices.firstOrNull { it.code == codigo } ?: return@mapNotNull null
            c.renderText?.trim()?.takeIf { it.isNotEmpty() } ?: "${op.label}: ${c.label}"
        }
    }
}
