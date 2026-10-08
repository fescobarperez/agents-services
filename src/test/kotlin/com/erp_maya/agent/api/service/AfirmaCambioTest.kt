package com.erp_maya.agent.api.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El agente no puede anunciar un cambio que ninguna herramienta hizo. */
class AfirmaCambioTest {

    @Test
    fun `detecta anuncios de cambios hechos`() {
        assertTrue(AgentLoop.afirmaCambio("¡Listo! Actualicé tu cotización a 25 Coca Colas 600ml."))
        assertTrue(AgentLoop.afirmaCambio("Registré tu solicitud de descuento."))
        assertTrue(AgentLoop.afirmaCambio("Agregué 20 unidades."))
        assertTrue(AgentLoop.afirmaCambio("Listo, 25 Coca Cola 600ml confirmadas.\n\nAhora me falta completar el checklist"))
        assertTrue(AgentLoop.afirmaCambio("Tu pedido quedó actualizado a 25 unidades."))
    }

    @Test
    fun `no confunde preguntas ni intenciones con anuncios`() {
        assertFalse(AgentLoop.afirmaCambio("Perfecto, voy a cambiar tu pedido a 25. ¿Confirmás este cambio?"))
        assertFalse(AgentLoop.afirmaCambio("¿Quieres que cambie la cantidad?"))
        assertFalse(AgentLoop.afirmaCambio("Tenemos 100 unidades disponibles."))
        assertFalse(AgentLoop.afirmaCambio("¿Las dejamos confirmadas en 25?"))
    }
}
