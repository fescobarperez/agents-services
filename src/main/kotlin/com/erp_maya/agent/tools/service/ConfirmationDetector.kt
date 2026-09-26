package com.erp_maya.agent.tools.service

import jakarta.inject.Singleton

/**
 * Decide si el mensaje del cliente confirma una operacion.
 *
 * El camino fiable es el id de la opcion: cuando el turno anterior ofrecio un
 * `choices` con `{"id":"confirm"}`, el canal devuelve ese id y no hay nada que
 * interpretar. El texto libre es el camino de respaldo y se mantiene corto y
 * cerrado a proposito: ampliarlo para "entender mejor" es como se acaba
 * emitiendo una cotizacion porque alguien escribio "ok pero espera".
 */
@Singleton
class ConfirmationDetector {

    fun isConfirmation(entrada: String?): Boolean {
        val texto = entrada?.trim()?.lowercase() ?: return false
        if (texto == ID_CONFIRMAR) return true

        // Solo frases cortas: "si" confirma, "si pero cambiame la cantidad" no.
        val limpio = texto.trim('.', ',', '!', '¡', ' ')
        if (limpio.length > LARGO_MAXIMO) return false
        return limpio in AFIRMACIONES
    }

    companion object {
        const val ID_CONFIRMAR = "confirm"

        /** Mas alla de esto ya no es una confirmacion, es una frase con matices. */
        const val LARGO_MAXIMO = 24

        private val AFIRMACIONES = setOf(
            "si", "sí", "si por favor", "sí por favor", "confirmo", "confirmado",
            "de acuerdo", "dale", "adelante", "correcto", "esta bien", "está bien",
            "ok", "okay", "listo", "procede", "emitela", "emítela",
        )
    }
}
