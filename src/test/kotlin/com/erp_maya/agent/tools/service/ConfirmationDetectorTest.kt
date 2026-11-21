package com.erp_maya.agent.tools.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationDetectorTest {

    private val detector = ConfirmationDetector()

    @Test
    fun `el id de la opcion es el camino fiable`() {
        assertTrue(detector.isConfirmation("confirm"))
        assertTrue(detector.isConfirmation("  CONFIRM "))
    }

    @Test
    fun `acepta afirmaciones cortas e inequivocas`() {
        listOf("si", "sí", "Confirmo", "de acuerdo", "dale", "ok", "listo").forEach {
            assertTrue(detector.isConfirmation(it), "debio aceptar '$it'")
        }
    }

    @Test
    fun `acepta confirmaciones naturales con cortesia o destino`() {
        // Casos reales de WhatsApp que el detector anterior rechazaba.
        listOf(
            "Si me parece bien",
            "Si envíala porfavor",
            "Si envíaselo a un agente de ventas",
            "¡Sí, por favor!",
            "Ok, adelante",
            "Está bien, mándala",
            "Perfecto, gracias",
        ).forEach {
            assertTrue(detector.isConfirmation(it), "debio aceptar '$it'")
        }
    }

    @Test
    fun `una frase con matices NO es confirmacion`() {
        // Es el caso que importa: "si" con una condicion detras no autoriza
        // emitir, y es exactamente lo que un detector permisivo dejaria pasar.
        listOf(
            "si pero cambiame la cantidad",
            "ok, primero dime el precio",
            "si me confirmas el plazo lo acepto",
            "no",
            "todavia no",
            "si, pero quita una",
            "si envíala con 20 unidades",
            "¿la envías?",
            "gracias",
            "por favor",
            "",
        ).forEach {
            assertFalse(detector.isConfirmation(it), "no debio aceptar '$it'")
        }
        assertFalse(detector.isConfirmation(null))
    }

    @Test
    fun `aprobar un diseño acepta como lo dice la gente`() {
        listOf("me gusta", "Me encanta!", "así está bien", "ese me gusta mucho", "perfecto", "quedó bonito", "sí, ese")
            .forEach { assertTrue(detector.isApproval(it), it) }
    }

    @Test
    fun `aprobar un diseño no acepta matices ni negaciones`() {
        listOf("no me gusta", "me gusta pero más alto", "¿me gusta?", "cambia el color", "me gusta el otro color azul")
            .forEach { assertFalse(detector.isApproval(it), it) }
    }

    @Test
    fun `me gusta no confirma un envio`() {
        assertFalse(detector.isConfirmation("me gusta"))
    }
}
