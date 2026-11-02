package com.erp_maya.agent.cita

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Botones, telefonos y fechas de las citas por WhatsApp. */
class CitaRulesTest {

    @Test
    fun `el boton lleva accion e id y solo se reconocen los nuestros`() {
        assertEquals("cita:confirmar:41", CitaRules.boton(CitaRules.CONFIRMAR, 41))
        assertEquals("cancelar_si" to 41L, CitaRules.leerBoton(" cita:cancelar_si:41 "))
        assertTrue(CitaRules.esBoton("cita:cambiar:7"))
        assertFalse(CitaRules.esBoton("cita:borrar:7"))
        assertFalse(CitaRules.esBoton("cita:confirmar:x"))
        assertFalse(CitaRules.esBoton("cot:aprobar:42:3"))
        assertFalse(CitaRules.esBoton("confirmo mi cita"))
        assertNull(CitaRules.leerBoton(null))
    }

    @Test
    fun `el wa_id de un numero de Guatemala lleva el 502`() {
        assertEquals("50255551234", CitaRules.waId("5555-1234"))
        assertEquals("50255551234", CitaRules.waId("+502 5555 1234"))
        assertEquals("13055551234", CitaRules.waId("1 (305) 555-1234"))
        assertNull(CitaRules.waId("1234"))
        assertNull(CitaRules.waId(null))
    }

    @Test
    fun `las fechas se dicen en hora de Guatemala`() {
        // 05:30 UTC del 4 es todavia el 3 a las 23:30 en Guatemala.
        assertEquals("martes 3 de noviembre", CitaRules.fechaLarga("2026-11-04T05:30:00Z"))
        assertEquals("23:30", CitaRules.hora("2026-11-04T05:30:00Z"))
        assertEquals("09:30", CitaRules.hora("2026-11-03T09:30:00-06:00"))
        assertEquals("2026-11-03", CitaRules.dia("2026-11-04T05:30:00Z"))
    }

    @Test
    fun `el modelo puede pedir hoy, mañana o una fecha`() {
        val ahora = Instant.parse("2026-11-02T15:00:00Z") // lunes 9:00 en Guatemala
        assertEquals(LocalDate.of(2026, 11, 2), CitaRules.leerDia("hoy", ahora))
        assertEquals(LocalDate.of(2026, 11, 3), CitaRules.leerDia("Mañana", ahora))
        assertEquals(LocalDate.of(2026, 11, 4), CitaRules.leerDia("pasado mañana", ahora))
        assertEquals(LocalDate.of(2026, 11, 10), CitaRules.leerDia("2026-11-10", ahora))
        assertNull(CitaRules.leerDia("el martes", ahora))
    }

    @Test
    fun `el inicio sin zona se toma en hora de Guatemala`() {
        val esperado = Instant.parse("2026-11-03T15:30:00Z")
        assertEquals(esperado, CitaRules.leerInicio("2026-11-03T15:30:00Z"))
        assertEquals(esperado, CitaRules.leerInicio("2026-11-03T09:30:00-06:00"))
        assertEquals(esperado, CitaRules.leerInicio("2026-11-03T09:30"))
        assertNull(CitaRules.leerInicio("a las 9"))
        assertTrue(CitaRules.mismoInicio("2026-11-03T15:30:00Z", esperado))
    }

    @Test
    fun `los textos se llenan sin dejar huecos raros`() {
        val v = CitaRules.valores("Ana María López", "2026-11-03T15:30:00Z", "Limpieza", "Dra. Ruiz", null)
        assertEquals(
            "Hola Ana, tu cita de Limpieza el martes 3 de noviembre a las 09:30.",
            CitaRules.llenar("Hola {paciente}, tu cita de {tipo} {clinica} el {fecha} a las {hora}.", v),
        )
    }
}
