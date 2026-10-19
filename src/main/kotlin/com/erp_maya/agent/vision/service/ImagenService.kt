package com.erp_maya.agent.vision.service

import com.erp_maya.agent.model.domain.ImagenParaModelo
import jakarta.inject.Singleton
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** La foto no es JPEG, PNG ni WebP, o no se pudo abrir. */
class ImagenNoSoportadaException(mensaje: String) : RuntimeException(mensaje)

/**
 * Deja una foto lista para el modelo: valida el formato por su contenido (no
 * por lo que diga Meta) y la reduce para controlar el costo en tokens.
 *
 * Todo en memoria. Nada de esto se escribe a disco ni a la base.
 */
@Singleton
open class ImagenService {

    open fun preparar(bytes: ByteArray): ImagenParaModelo {
        if (bytes.size > MAX_BYTES) throw ImagenNoSoportadaException("La foto pesa mas de ${MAX_BYTES / 1_048_576} MB")
        return when (formato(bytes)) {
            Formato.JPEG -> reducir(bytes, "image/jpeg")
            Formato.PNG -> reducir(bytes, "image/png")
            // ImageIO no lee WebP sin librerias extra: se manda tal cual (el
            // limite de tamaño ya se reviso); los proveedores lo aceptan.
            Formato.WEBP -> ImagenParaModelo(bytes, "image/webp")
            null -> throw ImagenNoSoportadaException("Formato de imagen no soportado")
        }
    }

    private fun reducir(bytes: ByteArray, mimeOriginal: String): ImagenParaModelo {
        val original = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: throw ImagenNoSoportadaException("No se pudo abrir la imagen")
        val lado = maxOf(original.width, original.height)
        // Ya chica y liviana: se manda como llego.
        if (lado <= LADO_MAXIMO && bytes.size <= BYTES_SIN_RECOMPRIMIR) return ImagenParaModelo(bytes, mimeOriginal)

        val escala = minOf(1.0, LADO_MAXIMO.toDouble() / lado)
        val ancho = maxOf(1, (original.width * escala).toInt())
        val alto = maxOf(1, (original.height * escala).toInt())
        // A RGB con fondo blanco: un PNG con transparencia a JPEG quedaria negro.
        val destino = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB)
        val g = destino.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.WHITE
            g.fillRect(0, 0, ancho, alto)
            g.drawImage(original, 0, 0, ancho, alto, null)
        } finally {
            g.dispose()
        }
        return ImagenParaModelo(jpeg(destino), "image/jpeg")
    }

    private fun jpeg(imagen: BufferedImage): ByteArray {
        val escritor = ImageIO.getImageWritersByFormatName("jpeg").next()
        val salida = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(salida).use { flujo ->
            escritor.output = flujo
            val param = escritor.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = CALIDAD_JPEG
            }
            escritor.write(null, IIOImage(imagen, null, null), param)
        }
        escritor.dispose()
        return salida.toByteArray()
    }

    internal enum class Formato { JPEG, PNG, WEBP }

    companion object {
        /** El mismo tope de WhatsApp para imagenes. */
        const val MAX_BYTES = 5L * 1024 * 1024

        /** Lado mayor con que llega al modelo: suficiente para leerla, barato en tokens. */
        const val LADO_MAXIMO = 1024

        private const val BYTES_SIN_RECOMPRIMIR = 1_000_000
        private const val CALIDAD_JPEG = 0.85f

        /** Por los primeros bytes del archivo, no por el mime que declaren. */
        internal fun formato(b: ByteArray): Formato? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> Formato.JPEG
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
                b[3] == 'G'.code.toByte() -> Formato.PNG
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> Formato.WEBP
            else -> null
        }
    }
}
