package com.erp_maya.agent.model.client

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El cuerpo multipart de `/images/edits`: campos de texto y la foto como archivo. */
class MultipartTest {

    @Test
    fun `arma campos y archivo con su tipo`() {
        val cuerpo = String(
            ProviderHttpClients.multipart(
                "LIM",
                listOf(
                    ProviderHttpClients.Parte("model", "gpt-image-1"),
                    ProviderHttpClients.Parte("image", archivo = "foto.png", mimeType = "image/png", contenido = byteArrayOf(1, 2, 3)),
                ),
            ),
            Charsets.ISO_8859_1,
        )
        assertTrue(cuerpo.contains("--LIM\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-image-1\r\n"))
        assertTrue(cuerpo.contains("name=\"image\"; filename=\"foto.png\"\r\nContent-Type: image/png\r\n\r\n"))
        assertTrue(cuerpo.endsWith("--LIM--\r\n"))
    }
}
