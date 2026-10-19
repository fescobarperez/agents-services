package com.erp_maya.agent.render

import com.erp_maya.agent.channel.whatsapp.client.MediaDemasiadoGrandeException
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.channel.whatsapp.service.WhatsAppOutbound
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.vision.service.ImagenNoSoportadaException
import com.erp_maya.agent.vision.service.ImagenService
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID

/**
 * Genera los renders encolados y los envia por WhatsApp.
 *
 * Corre aparte del consumidor de mensajes: un render tarda 10-30 s y, dentro
 * del turno, dejaria esperando a los demas clientes. Por cada trabajo: baja
 * la foto base de Meta, la edita con el modelo de imagenes, sube el resultado
 * a Meta y lo envia con un pie. Nada se escribe a disco ni a la base salvo
 * las referencias (media id, wamid) y el costo.
 */
@Singleton
open class RenderJobConsumer(
    private val trabajos: RenderJobRepository,
    private val whatsapp: WhatsAppCloudClient,
    private val imagenes: ImagenService,
    private val router: ModelRouter,
    private val conversaciones: ConversationService,
    @param:Value("\${renders.consumer.enabled:true}") private val habilitado: Boolean,
    @param:Value("\${renders.timeout-ms:120000}") private val timeoutMs: Int,
) {

    private val worker = "${runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("agents")}-render-" +
        UUID.randomUUID().toString().take(8)

    @Scheduled(fixedDelay = "\${renders.consumer.interval:2s}", initialDelay = "\${renders.consumer.initial-delay:15s}")
    open fun alTick() {
        if (!habilitado) return
        try {
            procesar()
        } catch (e: Exception) {
            log.error("fallo el ciclo de renders", e)
        }
    }

    /** Un ciclo: reclama y atiende. Devuelve cuantos trabajos tomo. */
    open fun procesar(): Int {
        val lote = trabajos.reclamar(worker, LOTE)
        lote.forEach { atender(it) }
        return lote.size
    }

    private fun atender(job: RenderJob) {
        val modelo = trabajos.modelo(job.modelId)
        if (modelo == null) {
            terminarConAviso(job, "el modelo de imagenes ${job.modelId} ya no esta activo", MENSAJE_FALLO)
            return
        }
        try {
            val base = whatsapp.descargarMedia(job.baseMediaId, ImagenService.MAX_BYTES)
            val foto = imagenes.preparar(base.bytes)
            val ref = ModelRef(modelo.id, modelo.providerCode, modelo.modelName, 0, supportsTools = false, supportsCache = false)
            val render = router.editImage(ref, job.prompt, foto, timeoutMs)
            val mediaId = whatsapp.subirMedia(job.accountRef, render.bytes, render.mimeType, "render-${job.id}.png")
            val pie = pie(job)
            val wamid = whatsapp.send(job.accountRef, job.recipient, WhatsAppOutbound.Image(mediaId, pie))
            // Queda en el hilo para que el modelo sepa que el cliente ya la vio.
            conversaciones.registrarAviso(job.conversationId, wamid, "[Render ${job.renderNumber} de ${job.renderMax} enviado al cliente] $pie")
            trabajos.terminar(job.id, mediaId, wamid, modelo.imageCost)
            log.info("render {} enviado (conversacion {}, {} de {})", job.id, job.conversationId, job.renderNumber, job.renderMax)
        } catch (e: MediaDemasiadoGrandeException) {
            terminarConAviso(job, e.message ?: "foto demasiado grande", MENSAJE_FOTO)
        } catch (e: ImagenNoSoportadaException) {
            terminarConAviso(job, e.message ?: "foto no soportada", MENSAJE_FOTO)
        } catch (e: Exception) {
            val detalle = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            if (job.attempts < MAX_INTENTOS) {
                log.warn("render {} fallo (intento {}): {}; se reintenta", job.id, job.attempts, detalle)
                trabajos.reintentar(job.id, ESPERA_SEGUNDOS * job.attempts, detalle)
            } else {
                terminarConAviso(job, detalle, MENSAJE_FALLO)
            }
        }
    }

    /** Lo da por perdido y se lo dice al cliente, para que no se quede esperando. */
    private fun terminarConAviso(job: RenderJob, error: String, mensaje: String) {
        log.warn("render {} perdido: {}", job.id, error)
        trabajos.fallar(job.id, error)
        runCatching {
            val wamid = whatsapp.send(job.accountRef, job.recipient, WhatsAppOutbound.Text(mensaje))
            conversaciones.registrarAviso(job.conversationId, wamid, "[Render ${job.renderNumber} no se pudo generar] $mensaje")
        }.onFailure { log.warn("no se pudo avisar del render {} perdido: {}", job.id, it.message) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RenderJobConsumer::class.java)

        private const val LOTE = 2
        private const val MAX_INTENTOS = 2
        private const val ESPERA_SEGUNDOS = 20

        const val MENSAJE_FALLO =
            "No pude generar la imagen en este momento. Si quieres lo intento de nuevo, o un asesor te comparte fotos de trabajos similares."
        const val MENSAJE_FOTO =
            "No pude abrir la foto para hacer la imagen. ¿Me la mandas de nuevo?"

        /** El pie del render: siempre como ilustrativo y con cuantos quedan. */
        fun pie(job: RenderJob): String = buildString {
            append("Imagen ilustrativa (").append(job.renderNumber).append(" de ").append(job.renderMax).append("). ")
            append(
                if (job.renderNumber < job.renderMax) "¿Te gusta así o qué le cambiamos?"
                else "Es la última imagen que puedo generar; si quieres más cambios, los anotamos para tu cotización.",
            )
        }
    }
}
