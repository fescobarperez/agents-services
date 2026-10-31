package com.erp_maya.agent.tools.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** contacto.registrar: origen del prospecto y lectura del flag de demo. */
class ProspectoTest {

    @Test
    fun `el origen sale del canal`() {
        assertEquals("tino_web", origenProspecto("web"))
        assertEquals("tino_whatsapp", origenProspecto("WhatsApp"))
        assertEquals("manual", origenProspecto("erp"))
    }

    @Test
    fun `demo acepta booleanos y texto`() {
        assertTrue(esVerdadero(true))
        assertTrue(esVerdadero("true"))
        assertTrue(esVerdadero(" Sí "))
        assertTrue(esVerdadero(1))
        assertFalse(esVerdadero("no"))
        assertFalse(esVerdadero(null))
    }

    @Test
    fun `la herramienta esta en el catalogo y no es solo de WhatsApp`() {
        val catalogo = ToolCatalog()
        val def = catalogo.byName(ToolCatalog.CONTACTO)
        assertTrue(def != null)
        assertFalse(ToolCatalog.CONTACTO in ToolCatalog.SOLO_WHATSAPP)
        assertTrue(def!!.parameters.any { it.name == "nombre" && it.required })
    }
}
