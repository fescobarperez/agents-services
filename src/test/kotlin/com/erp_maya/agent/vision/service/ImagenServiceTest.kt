package com.erp_maya.agent.vision.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Preparacion de fotos para el modelo: formato por contenido y tamaño. */
class ImagenServiceTest {

    private val servicio = ImagenService()

    @Test
    fun `reconoce el formato por los bytes, no por el nombre`() {
        assertEquals(ImagenService.Formato.PNG, ImagenService.formato(png(10, 10)))
        assertEquals(ImagenService.Formato.JPEG, ImagenService.formato(jpg(10, 10)))
        val webp = "RIFF0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals(ImagenService.Formato.WEBP, ImagenService.formato(webp))
        assertEquals(null, ImagenService.formato("%PDF-1.7".toByteArray()))
    }

    @Test
    fun `una foto chica pasa tal cual`() {
        val original = png(200, 100)
        val lista = servicio.preparar(original)
        assertSame(original, lista.bytes)
        assertEquals("image/png", lista.mimeType)
    }

    @Test
    fun `una foto grande se reduce a 1024 de lado mayor y sale en jpeg`() {
        val lista = servicio.preparar(png(3000, 1500))
        assertEquals("image/jpeg", lista.mimeType)
        val leida = ImageIO.read(ByteArrayInputStream(lista.bytes))
        assertEquals(ImagenService.LADO_MAXIMO, leida.width)
        assertEquals(512, leida.height)
    }

    @Test
    fun `rechaza lo que no es imagen y lo que pasa del tope`() {
        assertThrows(ImagenNoSoportadaException::class.java) { servicio.preparar("%PDF-1.7 hola".toByteArray()) }
        assertThrows(ImagenNoSoportadaException::class.java) {
            servicio.preparar(ByteArray((ImagenService.MAX_BYTES + 1).toInt()))
        }
    }

    @Test
    fun `un png con transparencia queda sobre fondo blanco al reducirse`() {
        val img = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB) // todo transparente
        val lista = servicio.preparar(codificar(img, "png"))
        val leida = ImageIO.read(ByteArrayInputStream(lista.bytes))
        val rgb = leida.getRGB(10, 10) and 0xFFFFFF
        assertTrue(rgb > 0xF0F0F0, "esperaba blanco y salio %06x".format(rgb))
    }

    private fun png(w: Int, h: Int) = codificar(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png")

    private fun jpg(w: Int, h: Int) = codificar(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg")

    private fun codificar(img: BufferedImage, formato: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, formato, it) }.toByteArray()
}
