package com.erp_maya.agent.channel.planner

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

// ── entrada (JSON como text/plain: sin preflight de CORS) ────────────────────

@Serdeable
data class PlannerSessionIn(
    @Nullable val budget: BigDecimal? = null,
    @Nullable val projectType: String? = null,
)

/** Lo que cambia de la sesion; lo que no venga se conserva. En `notes`, un valor vacio borra la nota. */
@Serdeable
data class PlannerUpdateIn(
    @Nullable val budget: BigDecimal? = null,
    @Nullable val projectType: String? = null,
    @Nullable val notes: Map<String, String>? = null,
    /** true quita la foto del espacio. */
    @Nullable val removePhoto: Boolean? = null,
)

/** La foto del espacio, en base64 (JSON como text/plain: sin preflight de CORS). */
@Serdeable
data class PlannerPhotoIn(
    @Nullable val data: String? = null,
    @Nullable val name: String? = null,
)

/**
 * Una opcion nueva. Con `from` se parte de esa opcion y solo se manda lo que
 * cambia; sin `from` es una opcion desde cero (la primera, o un tipo distinto).
 */
@Serdeable
data class PlannerOptionIn(
    @Nullable val from: String? = null,
    @Nullable val projectType: String? = null,
    @Nullable val params: Map<String, BigDecimal>? = null,
    @Nullable val choices: Map<String, String>? = null,
    @Nullable val preset: String? = null,
    @Nullable val quantity: BigDecimal? = null,
    @Nullable val title: String? = null,
)

/** Un mensaje del visitante. `id` lo genera el widget (aleatorio): un reintento no corre dos turnos. */
@Serdeable
data class PlannerMessageIn(
    @Nullable val id: String? = null,
    @Nullable val text: String? = null,
)

// ── salida: solo lo que ve el visitante (nada de subtotal, IVA ni costos) ────

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerConfigView(
    val title: String?,
    val lang: String,
    val accent: String?,
    /** ISO 4217, del locale de la empresa. */
    val currency: String,
    val fitThresholdPct: BigDecimal,
    /**
     * Renders por diseño (sesion de planificacion): `imagenes.renders_max` del
     * playbook del agente asignado, como «imagenes de ejemplo por cotizacion».
     * 0 = sin renders (playbook sin `generar_renders`, sin modelo de imagenes o sin agente).
     */
    val rendersMax: Int,
    val retentionDays: Int,
    val maxOptions: Int,
    val projectTypes: List<PlannerTypeRef>,
    /** Categorias del ERP para elegir producto de una lista; vacia = solo los tipos fijos. */
    val categories: List<PlannerCategoryView> = emptyList(),
    /** Hay un agente que conversa. Sin el, el widget funciona con campos y chips, sin chat. */
    val chat: Boolean = false,
    val maxMessageLength: Int = PlannerService.LARGO_MENSAJE,
    val features: PlannerFeaturesView = PlannerFeaturesView(),
    /** A donde enlaza la firma «Con tecnología de Corvex»; null = solo texto. */
    val poweredByUrl: String? = null,
)

/**
 * Lo que el widget muestra segun la fase en que va el planificador: renders
 * (fase 3), guardar diseño y pedir cotizacion (fase 4). Apagado, el widget lo esconde.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerFeaturesView(
    val renders: Boolean = false,
    val save: Boolean = false,
    /** Decidido con el cliente: no se crea cotizacion en el ERP; el contacto va por WhatsApp. */
    val quote: Boolean = false,
    /** «Contactar a un asesor»: el planificador tiene el WhatsApp del asesor. */
    val contact: Boolean = false,
)

/** El enlace de WhatsApp con el diseño ya escrito (wa.me con el texto). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerContactView(val whatsappUrl: String)

/** El visitante toco «Contactar a un asesor» con esta opcion en pantalla (para medir). */
@Serdeable
data class PlannerContactIn(@Nullable val option: String? = null)

/** La foto del espacio de la sesion: `url` relativa a /v1/planner (el widget le antepone su base). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerPhotoView(val url: String, val name: String?)

/** El render de una opcion: pending (en camino), ready (con `url`) o failed (se puede reintentar). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerRenderView(val status: String, val url: String?)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerTypeRef(val code: String, val label: String)

/** Una categoria del planificador (trae tambien lo de sus subcategorias). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerCategoryView(val id: Long, val name: String)

/**
 * Un producto en la lista de una categoria. `type` es el codigo con que se
 * elige (`p{id}`) en la sesion y las opciones. `price` es el de lista por
 * `unit` (referencia: el total de la opcion lo da el ERP con sus cargos); null
 * y `pricing` review = un asesor confirma el precio. `image`: relativa a
 * /v1/planner (miniatura), null sin foto.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerProductView(
    val type: String,
    val id: Long,
    val name: String,
    val summary: String?,
    val category: String?,
    val price: BigDecimal?,
    val unit: String?,
    val pricing: String,
    val image: String?,
)

/** Lo que se puede elegir en un tipo de proyecto (de su plantilla), sin costos. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerTypeView(
    val code: String,
    val label: String,
    val params: List<PlannerParamView>,
    val options: List<PlannerOptionDefView>,
    val presets: List<PlannerTypeRef>,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerParamView(
    val code: String,
    val label: String,
    val unit: String?,
    val min: BigDecimal?,
    val max: BigDecimal?,
    val default: BigDecimal?,
    val required: Boolean,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerOptionDefView(val code: String, val label: String, val required: Boolean, val choices: List<PlannerChoiceView>)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerChoiceView(val code: String, val label: String, val default: Boolean, val benefit: String?)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerStateView(
    val session: String,
    val expiresAt: String,
    val currency: String,
    val budget: BigDecimal?,
    val projectType: String?,
    val notes: List<PlannerNoteView>,
    val activeOption: String?,
    val maxOptions: Int,
    val renders: PlannerRendersView,
    /** La foto del espacio, si la subio. */
    val photo: PlannerPhotoView? = null,
    /** «Contactar a un asesor»: null si el planificador no tiene WhatsApp o aun no hay opciones. */
    val contact: PlannerContactView? = null,
    val options: List<PlannerOptionView>,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerNoteView(val key: String, val value: String)

/** Renders de este diseño (la sesion de planificacion): usados (fase 3) y tope del playbook. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerRendersView(val used: Int, val max: Int)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerOptionView(
    val letter: String,
    val parent: String?,
    val projectType: String,
    val title: String,
    val summary: String?,
    val details: List<PlannerDetail>,
    /** priced | review */
    val pricing: String,
    /** Total al cliente; null si un asesor confirma el precio. */
    val total: BigDecimal?,
    val budget: PlannerBudgetView,
    val suggestion: PlannerSuggestionView?,
    /** Cambios de un toque sobre esta opcion, ya valorados (los chips bajo el chat). */
    val alternatives: List<PlannerAlternativeView> = emptyList(),
    /** null = aun no se pidio. */
    val render: PlannerRenderView? = null,
    val createdAt: String,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerAlternativeView(val label: String, val change: Map<String, String>, val total: BigDecimal)

/**
 * Un boton de respuesta rapida que propone el agente. Con `change` el widget
 * crea la opcion con ese cambio sin pasar por el modelo; si no, manda `text`
 * como mensaje.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerQuickReply(val label: String, val text: String?, val change: Map<String, String>?)

/** La respuesta del asistente a un mensaje, con el estado ya actualizado por sus herramientas. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerReply(val messages: List<String>, val replies: List<PlannerQuickReply>, val state: PlannerStateView)

/** El semaforo: `status` fits | tight | over | review | null (sin presupuesto). */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerBudgetView(val status: String?, val remaining: BigDecimal?, val excess: BigDecimal?, val ratio: BigDecimal?)

/** Un cambio real para volver al monto, con su precio recalculado. `fits` false: acerca pero aun se pasa. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerSuggestionView(val label: String, val change: Map<String, String>, val total: BigDecimal, val fits: Boolean)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlannerOptionCreated(val created: String, val state: PlannerStateView)
