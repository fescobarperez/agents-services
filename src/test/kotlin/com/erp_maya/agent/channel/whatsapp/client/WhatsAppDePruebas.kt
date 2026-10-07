package com.erp_maya.agent.channel.whatsapp.client

import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import io.micronaut.context.annotation.Replaces
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Graph API simulada. Registra lo que se le manda y puede fallar a pedido,
 * para probar que un envio caido vuelve a la cola sin reejecutar el turno.
 */
@Singleton
@Replaces(WhatsAppCloudClient::class)
@Requires(env = [Environment.TEST])
class WhatsAppDePruebas : WhatsAppCloudClient("http://localhost", "token-de-pruebas") {

    data class Envio(val phoneNumberId: String, val to: String, val mensaje: WhatsAppOutbound)

    val envios = CopyOnWriteArrayList<Envio>()

    @Volatile
    var fallar = false

    private val secuencia = AtomicInteger()

    fun limpiar() {
        envios.clear()
        subidas.clear()
        fallar = false
    }

    override fun send(phoneNumberId: String, to: String, mensaje: WhatsAppOutbound): String {
        if (fallar) throw WhatsAppSendException("fallo simulado de la Graph API")
        envios += Envio(phoneNumberId, to, mensaje)
        return "wamid.salida-${secuencia.incrementAndGet()}-${System.nanoTime()}"
    }

    val subidas = CopyOnWriteArrayList<String>()

    override fun subirMedia(phoneNumberId: String, contenido: ByteArray, mimeType: String, nombre: String): String {
        if (fallar) throw WhatsAppSendException("fallo simulado de la Graph API")
        subidas += nombre
        return "media-${secuencia.incrementAndGet()}"
    }
}
