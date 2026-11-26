package com.erp_maya.agent.channel.planner

import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal
import java.time.Instant

/**
 * Un tipo de proyecto: de que producto del ERP sale. Los fijos los configura la
 * consola (chip «Pérgola»); los de las categorias se arman al vuelo cuando el
 * visitante elige un producto de la lista (codigo `p{productId}`), con su
 * unidad y descripcion (de ahi el agente y el render saben que es).
 */
@Serdeable
data class ProjectType(
    val code: String,
    val label: String,
    val productId: Long,
    @Nullable val unit: String? = null,
    @Nullable val description: String? = null,
)

/** Un planificador visto desde la entrada publica. Todo lo de negocio viene de la consola. */
data class PlannerWidget(
    val channelId: Long,
    val tenantId: Long,
    val publicKey: String,
    val active: Boolean,
    val allowedOrigins: List<String>,
    val name: String,
    val title: String?,
    val accentColor: String?,
    val lang: String,
    /** Locale de la empresa (moneda de los montos). */
    val locale: String?,
    val projectTypes: List<ProjectType>,
    /** Categorias del ERP que se muestran (cada una con sus subcategorias); el visitante elige un producto de la lista. */
    val categoryIds: List<Long> = emptyList(),
    /** "Justo": el total pasa de este % del presupuesto sin pasarse de el. */
    val fitThresholdPct: BigDecimal,
    val retentionDays: Int,
    val maxOptions: Int,
    /** WhatsApp del asesor (solo digitos, con codigo de pais); null = sin boton de contacto. */
    val contactWhatsapp: String? = null,
)

/**
 * Lo que define una opcion: con esto se vuelve a valorar en el ERP. Con
 * plantilla, `params`/`choices`/`preset` son los de la plantilla; sin ella,
 * `params` solo lleva medidas (alto, ancho, profundidad) para el precio por medida.
 */
@Serdeable
data class OptionSpec(
    val productId: Long,
    @Nullable val templateId: Long? = null,
    val params: Map<String, BigDecimal> = emptyMap(),
    val choices: Map<String, String> = emptyMap(),
    @Nullable val preset: String? = null,
    val quantity: BigDecimal = BigDecimal.ONE,
)

/**
 * Un punto de «Detalles de tu proyecto». `kind`: size | param | choice |
 * quantity. `changed`: es lo que cambio respecto de la opcion padre.
 */
@Serdeable
data class PlannerDetail(
    val key: String,
    val label: String,
    val value: String,
    val kind: String = CHOICE,
    val changed: Boolean = false,
) {
    companion object {
        const val SIZE = "size"
        const val PARAM = "param"
        const val CHOICE = "choice"
        const val QUANTITY = "quantity"
    }
}

/**
 * Un cambio de una sola eleccion sobre la opcion, ya valorado. La sugerencia
 * de «se pasa por» se elige de aqui al leer, con el presupuesto de ese momento.
 */
@Serdeable
data class PlannerVariant(
    /** {opcion de la plantilla: eleccion}. */
    val change: Map<String, String>,
    val label: String,
    @Nullable val total: BigDecimal? = null,
    val review: Boolean = false,
)

data class PlannerSessionRow(
    val id: String,
    val channelId: Long,
    val tenantId: Long,
    val budget: BigDecimal?,
    val projectType: String?,
    val notes: Map<String, String>,
    val activeLetter: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    /** La foto del espacio (web_media), base del render. Opcional. */
    val photoMediaId: String? = null,
)

data class PlannerOptionRow(
    val letter: String,
    val parentLetter: String?,
    val projectType: String,
    val title: String,
    val summary: String?,
    val spec: OptionSpec,
    val details: List<PlannerDetail>,
    /** priced | review */
    val pricing: String,
    val total: BigDecimal?,
    val subtotal: BigDecimal?,
    val tax: BigDecimal?,
    val variants: List<PlannerVariant>,
    val budgetAtCreation: BigDecimal?,
    val createdAt: Instant = Instant.now(),
    /** pending | ready | failed; null = aun no se pidio. */
    val renderStatus: String? = null,
    /** El render terminado (web_media). */
    val renderMediaId: String? = null,
    /** El trabajo de la cola que lo hizo: el mayor es el render mas reciente. */
    val renderJobId: Long? = null,
)

/** Un error del visitante (o del agente) al pedir una opcion: `field` dice cual dato. */
class PlannerSpecException(val field: String, override val message: String) : RuntimeException(message)
