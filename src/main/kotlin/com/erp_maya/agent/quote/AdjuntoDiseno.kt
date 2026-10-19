package com.erp_maya.agent.quote

import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.QuoteDesignSpec
import com.erp_maya.agent.erp.domain.QuoteDesignWrite
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.render.ResultadosRender
import com.erp_maya.agent.vision.service.ImagenService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * El cliente aprobo un render: se anota y se guarda en la cotizacion (ficha +
 * imagen) para que el asesor lo vea en Stackline.
 *
 * El orden en que pasan las cosas varia: a veces ya hay cotizacion cuando el
 * cliente aprueba, a veces se crea despues. Por eso el guardado es
 * [adjuntarSiCorresponde], que se llama tras cada paso del borrador y no hace
 * nada hasta que haya cotizacion y diseño aprobado. Si el ERP o Meta fallan,
 * lo vuelve a intentar en el siguiente paso.
 */
@Singleton
open class AdjuntoDiseno(
    private val resultados: ResultadosRender,
    private val whatsapp: WhatsAppCloudClient,
    private val erp: ErpClient,
) {

    /** `confirmado` sale del mensaje del cliente en ESTE turno, no del modelo. */
    open fun aprobar(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, confirmado: Boolean): DraftStep {
        val borrador = actual ?: throw IllegalArgumentException("No hay diseño en curso")
        val ficha = borrador.diseno ?: throw IllegalArgumentException("No hay diseño en curso")
        val renderId = ficha.ultimoRender
            ?: throw IllegalArgumentException("Todavía no se ha enviado ninguna imagen; no hay nada que aprobar")
        if (!confirmado) {
            throw IllegalArgumentException(
                "El cliente no dijo que sí en este mensaje. Pregúntale si le gusta así y apruébalo cuando lo confirme.",
            )
        }
        val r = resultados.resultado(renderId)
        if (r == null || r.status != ESTADO_ENVIADO) {
            throw IllegalArgumentException("La imagen todavía no le llega al cliente: espera a que la vea antes de aprobarla.")
        }
        val conAprobado = borrador.copy(
            diseno = ficha.copy(renderAprobado = renderId, disenoAdjunto = false),
            actualizadaMs = System.currentTimeMillis(),
        )
        val resultado = adjuntarSiCorresponde(contexto, conversationId, conAprobado) ?: conAprobado
        val pendienteMedidas = ficha.medidasSinConfirmar()
        return DraftStep(
            borrador = resultado,
            mensaje = "Anoté que te gusta este diseño (imagen ${r.renderNumber} de ${r.renderMax}).",
            datos = mapOf(
                "guardado_en_cotizacion" to (resultado.diseno?.disenoAdjunto == true),
                "siguiente" to when {
                    pendienteMedidas -> "Confirma las medidas con el cliente (cotizacion.confirmar_medidas) y luego arma la cotización."
                    resultado.quoteId == null -> "Arma la cotización con cotizacion.agregar (o cotizacion.producto_nuevo si no está en el catálogo)."
                    else -> "El diseño ya quedó en la cotización ${resultado.quoteNumber}; el asesor lo verá."
                },
            ),
        )
    }

    /**
     * Si hay diseño aprobado sin guardar y ya existe la cotizacion, lo guarda.
     * Nunca lanza: un fallo se registra y se reintenta en el siguiente paso.
     */
    open fun adjuntarSiCorresponde(contexto: ExecutionContext, conversationId: Long, borrador: QuoteDraft?): QuoteDraft? {
        val ficha = borrador?.diseno ?: return borrador
        val renderId = ficha.renderAprobado ?: return borrador
        val quoteId = borrador.quoteId ?: return borrador
        if (ficha.disenoAdjunto) return borrador
        return try {
            val r = resultados.resultado(renderId)
            val imagen = r?.resultMediaId?.let { whatsapp.descargarMedia(it, ImagenService.MAX_BYTES) }
            erp.saveQuoteDesign(
                contexto.tenantId, conversationId, quoteId,
                QuoteDesignWrite(
                    spec = spec(ficha),
                    renderNumber = r?.renderNumber,
                    renderMax = r?.renderMax,
                    imageBase64 = imagen?.let { Base64.getEncoder().encodeToString(it.bytes) },
                    imageMime = imagen?.let { it.mimeType ?: "image/png" },
                ),
            )
            log.info("diseño aprobado guardado en la cotizacion {} (render {})", quoteId, renderId)
            borrador.copy(diseno = ficha.copy(disenoAdjunto = true))
        } catch (e: Exception) {
            log.warn("no se pudo guardar el diseño en la cotizacion {}: {} {}", quoteId, e.javaClass.simpleName, e.message)
            borrador
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdjuntoDiseno::class.java)
        const val ESTADO_ENVIADO = "done"

        internal fun spec(f: FichaDiseno) = QuoteDesignSpec(
            tipo = f.tipo,
            descripcion = f.descripcion,
            material = f.material,
            acabado = f.acabado,
            alto = f.medidas?.height,
            ancho = f.medidas?.width,
            profundidad = f.medidas?.depth,
            medidasConfirmadas = f.medidasConfirmadas,
            detalles = f.detalles.takeIf { it.isNotEmpty() },
        )
    }
}
