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
    fun `una frase con matices NO es confirmacion`() {
        // Es el caso que importa: "si" con una condicion detras no autoriza
        // emitir, y es exactamente lo que un detector permisivo dejaria pasar.
        listOf(
            "si pero cambiame la cantidad",
            "ok, primero dime el precio",
            "si me confirmas el plazo lo acepto",
            "no",
            "todavia no",
            "",
        ).forEach {
            assertFalse(detector.isConfirmation(it), "no debio aceptar '$it'")
        }
        assertFalse(detector.isConfirmation(null))
    }
}
