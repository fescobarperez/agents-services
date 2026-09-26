package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.channel.whatsapp.domain.MensajeEntrante
import com.erp_maya.agent.channel.whatsapp.domain.WaMessage
import com.erp_maya.agent.channel.whatsapp.domain.WhatsAppWebhook
import jakarta.inject.Singleton

/** Convierte el cuerpo de Meta en mensajes normalizados. */
@Singleton
class WhatsAppParser {

    fun extraer(webhook: WhatsAppWebhook): List<MensajeEntrante> =
        webhook.entry.orEmpty()
            .flatMap { it.changes.orEmpty() }
            .mapNotNull { it.value }
            .flatMap { valor ->
                val cuenta = valor.metadata?.phoneNumberId ?: return@flatMap emptyList()
                // `statuses` (entregado, leido) se ignora sin mas: llega en
                // volumen y no abre conversacion con nadie.
                valor.messages.orEmpty().mapNotNull { m -> normalizar(m, cuenta) }
            }

    private fun normalizar(m: WaMessage, cuenta: String): MensajeEntrante? {
        val id = m.id ?: return null
        val de = m.from ?: return null
        return MensajeEntrante(wamid = id, de = de, cuenta = "wa:$cuenta", texto = textoDe(m))
    }

    /**
     * De un boton se toma el ID, no el titulo.
     *
     * Es lo que permite que la compuerta reconozca una confirmacion sin
     * interpretar lenguaje: el boton se llama "Confirmar" en pantalla pero
     * viaja como `confirm`.
     */
    private fun textoDe(m: WaMessage): String? = when (m.type) {
        "text" -> m.text?.body
        "interactive" -> m.interactive?.buttonReply?.id
            ?: m.interactive?.listReply?.id
            ?: m.interactive?.buttonReply?.title
        else -> null
    }
}
