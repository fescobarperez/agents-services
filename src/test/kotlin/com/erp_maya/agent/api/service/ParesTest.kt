package com.erp_maya.agent.api.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** El formato 'clave=valor; clave2=valor2' de las medidas y opciones de cotizacion.agregar_plantilla. */
class ParesTest {

    @Test
    fun `separa por punto y coma o salto de linea y normaliza la clave`() {
        assertEquals(
            mapOf("ancho" to "3", "largo" to "4.5", "techo" to "shingle"),
            AgentLoop.pares(" Ancho = 3; largo=4.5\ntecho=shingle ;"),
        )
    }

    @Test
    fun `ignora lo vacio y lo que no tiene igual`() {
        assertEquals(mapOf("alto" to "2.5"), AgentLoop.pares("alto=2.5; cielo=; =x; algo"))
        assertEquals(emptyMap<String, String>(), AgentLoop.pares(null))
    }
}
