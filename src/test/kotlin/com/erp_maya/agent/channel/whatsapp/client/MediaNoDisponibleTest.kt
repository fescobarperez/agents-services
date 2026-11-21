package com.erp_maya.agent.channel.whatsapp.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaNoDisponibleTest {

    private val graph400 = """{"error":{"message":"Unsupported get request. Object with ID '1616775497163340' does not exist, """ +
        """cannot be loaded due to missing permissions","type":"GraphMethodException","code":100}}"""

    @Test
    fun `media vencida o de otra app no se reintenta`() {
        assertTrue(esMediaNoDisponible(400, graph400))
        assertTrue(esMediaNoDisponible(404, null))
    }

    @Test
    fun `fallas de token o de Meta si se reintentan`() {
        assertFalse(esMediaNoDisponible(400, """{"error":{"type":"OAuthException"}}"""))
        assertFalse(esMediaNoDisponible(401, graph400))
        assertFalse(esMediaNoDisponible(403, null))
        assertFalse(esMediaNoDisponible(500, null))
    }

    @Test
    fun `es una WhatsAppSendException para quien ya la atrapa`() {
        assertTrue(MediaNoDisponibleException("m-1", "404") is WhatsAppSendException)
    }
}
