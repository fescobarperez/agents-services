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
)

/** Un mensaje del visitante. `session` e `id` los genera el widget (aleatorios). */
@Serdeable
data class WebMessageIn(
    @Nullable val session: String? = null,
    @Nullable val id: String? = null,
    @Nullable val text: String? = null,
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebChoice(val id: String, val label: String)

/**
 * La respuesta, ya traducida para el visitante: textos y opciones. Las
 * tarjetas del panel (ids, estados, banderas internas) no salen nunca por aqui.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebReply(val messages: List<String>, val choices: List<WebChoice>)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WebError(val code: String, val message: String)

class WebException(val status: Int, val code: String, override val message: String, val retryAfter: Long? = null) :
    RuntimeException(message)
