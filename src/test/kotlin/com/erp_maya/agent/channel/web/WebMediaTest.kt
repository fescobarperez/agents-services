package com.erp_maya.agent.channel.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fotos y archivos del chat web, sin base de datos. */
class WebMediaTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(16)

    @Test
    fun `reconoce la foto por sus bytes y no por lo que dice el navegador`() {
        assertEquals("image/jpeg", WebChatService.tipoImagen(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", WebChatService.tipoImagen(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)))
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + ByteArray(8)
        assertEquals("image/webp", WebChatService.tipoImagen(webp))
        assertNull(WebChatService.tipoImagen("%PDF-1.7 hola mundo".toByteArray()), "un PDF no es foto")
        assertNull(WebChatService.tipoImagen("<svg xmlns='x'/>".toByteArray()), "SVG no: puede llevar scripts")
        assertNull(WebChatService.tipoImagen(ByteArray(2)))
    }

    @Test
    fun `los ids son largos, aleatorios y distinguen la media web de la de Meta`() {
        val a = WebMediaRepository.nuevoId()
        val b = WebMediaRepository.nuevoId()
        assertTrue(WebMediaRepository.ID.matches(a))
        assertEquals(35, a.length)
        assertTrue(a != b)
        assertTrue(WebMediaRepository.esWeb(a))
        assertFalse(WebMediaRepository.esWeb("1234567890123456"), "un media id de Meta")
        assertFalse(WebMediaRepository.esWeb(null))
        assertFalse(WebMediaRepository.ID.matches("wm_../../etc/passwd"))
    }

    @Test
    fun `la ruta del archivo es relativa a la base del widget`() {
        assertEquals("/wk_abc/media/wm_x", WebChatService.ruta("wk_abc", "wm_x"))
    }
}
