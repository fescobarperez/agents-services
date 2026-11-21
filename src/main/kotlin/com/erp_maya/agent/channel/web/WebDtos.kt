package com.erp_maya.agent.channel.web

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable

/** Lo que el widget necesita para pintarse. Nada interno: lo lee cualquier visitante. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebConfigView(
    val title: String,
    val greeting: String?,
    val color: String?,
    val buttons: Int,
    val maxLength: Int,
    /** Fotos por mensaje que acepta (0 = el widget no muestra el boton de adjuntar). */
    val maxImages: Int = 0,
    /** El carrito muestra precios y total; si no, solo productos y cantidades. */
    val showPrices: Boolean = true,
    /** Moneda de los montos del carrito (ISO 4217), del locale de la empresa. */
    val currency: String = "GTQ",
)

/** Un mensaje del visitante. `session` e `id` los genera el widget (aleatorios). */
@Serdeable
data class WebMessageIn(
    @Nullable val session: String? = null,
    @Nullable val id: String? = null,
    @Nullable val text: String? = null,
    /** Ids de web_media que el visitante subio antes con /media. */
    @Nullable val images: List<String>? = null,
)

/** Una foto del visitante, en base64 (JSON como text/plain: sin preflight de CORS). */
@Serdeable
data class WebMediaIn(
    @Nullable val session: String? = null,
    @Nullable val mime: String? = null,
    @Nullable val data: String? = null,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebMediaAck(val id: String, val url: String)

/**
 * Un adjunto que el visitante ve en el chat: una imagen (render) o un
 * documento (PDF de la cotizacion), con su enlace publico y temporal.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebAttachment(
    val type: String,
    val url: String,
    val name: String?,
    val mime: String?,
    val size: Int?,
    val caption: String?,
)

/** Lo que llega despues del turno (renders, avisos), en orden. */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebUpdates(val items: List<WebUpdate>, val cursor: Long, val pending: Boolean)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebUpdate(val text: String?, val attachment: WebAttachment?)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebChoice(val id: String, val label: String)

/**
 * La respuesta, ya traducida para el visitante: textos y opciones. Las
 * tarjetas del panel (ids, estados, banderas internas) no salen nunca por aqui.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebReply(
    val messages: List<String>,
    val choices: List<WebChoice>,
    val attachments: List<WebAttachment> = emptyList(),
    /** Hay algo en camino (p. ej. una imagen): el widget consulta /updates desde `cursor`. */
    val pending: Boolean = false,
    val cursor: Long = 0,
    /** Lo confirmado hasta ahora (null = vacio). Viene en cada respuesta: el widget lo reemplaza. */
    @Nullable val cart: WebCart? = null,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebCartView(@Nullable val cart: WebCart?)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebError(val code: String, val message: String)

class WebException(val status: Int, val code: String, override val message: String, val retryAfter: Long? = null) :
    RuntimeException(message)

/** Formulario de contacto de la pagina (POST /v1/web/{key}/contact). */
@Serdeable
data class WebContactIn(
    @Nullable val name: String? = null,
    @Nullable val email: String? = null,
    @Nullable val phone: String? = null,
    @Nullable val company: String? = null,
    @Nullable val interest: String? = null,
    @Nullable val message: String? = null,
    /** Trampa para bots: un campo oculto que una persona nunca llena. */
    @Nullable val website: String? = null,
)

@Serdeable
data class WebContactAck(val ok: Boolean)
