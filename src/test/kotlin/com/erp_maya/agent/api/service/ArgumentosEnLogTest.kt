package com.erp_maya.agent.api.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** El log de herramientas sigue lo que hizo el agente, sin datos de la persona. */
class ArgumentosEnLogTest {

    @Test
    fun `oculta lo que el visitante anota de si mismo`() {
        assertEquals(mapOf("clave" to "nombre", "valor" to "***"), ArgumentosEnLog.de(mapOf("clave" to "nombre", "valor" to "Fernando Escobar")))
        assertEquals(mapOf("clave" to "whatsapp", "valor" to "***"), ArgumentosEnLog.de(mapOf("clave" to "whatsapp", "valor" to "30063310")))
    }

    @Test
    fun `oculta los datos de un cliente o contacto nuevo`() {
        val log = ArgumentosEnLog.de(mapOf("nombre" to "Ana Pérez", "nit" to "1234567-8", "telefono" to "+502 5555 1234", "correo" to "ana@correo.com"))
        assertEquals(mapOf("nombre" to "***", "nit" to "***", "telefono" to "***", "correo" to "***"), log)
    }

    @Test
    fun `deja lo que sirve para seguir el turno`() {
        val args = mapOf("desde" to "A", "medidas" to "ancho=4; profundidad=5", "tipo" to "p1051", "monto" to 25000, "productId" to 1051L)
        assertEquals(args, ArgumentosEnLog.de(args))
    }

    @Test
    fun `tacha telefonos y correos que aparezcan en otros textos`() {
        val log = ArgumentosEnLog.de(mapOf("detalle" to "llamar al 3006-3310 o escribir a ana@correo.com", "cantidad" to "12"))
        assertEquals(mapOf("detalle" to "llamar al *** o escribir a ***", "cantidad" to "12"), log)
    }

    @Test
    fun `revisa tambien listas y mapas anidados`() {
        val log = ArgumentosEnLog.de(mapOf("lineas" to listOf(mapOf("productId" to 7, "nombre" to "Pérgola"), "nota 55512345")))
        assertEquals(mapOf("lineas" to listOf(mapOf("productId" to 7, "nombre" to "***"), "nota ***")), log)
    }
}
