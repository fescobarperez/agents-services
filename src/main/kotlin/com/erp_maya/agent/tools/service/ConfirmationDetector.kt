package com.erp_maya.agent.tools.service

import jakarta.inject.Singleton
import java.text.Normalizer

/**
 * Decide si el mensaje del cliente confirma una operacion.
 *
 * El camino fiable es el id de la opcion: cuando el turno anterior ofrecio un
 * `choices` con `{"id":"confirm"}`, el canal devuelve ese id y no hay nada que
 * interpretar.
 *
 * El texto libre es el respaldo y sigue siendo CERRADO, pero por vocabulario y
 * no por frase exacta: el mensaje confirma si TODAS sus palabras son de un
 * vocabulario conocido (afirmaciones, verbos de envio, cortesia) y al menos una
 * es una afirmacion. Asi pasan "Si me parece bien", "Si envíala porfavor" o
 * "Sí, envíaselo a un agente de ventas", y no pasa nada que traiga una palabra
 * fuera de la lista: "si pero cambia la cantidad", "ok, primero dime el
 * precio", "si me confirmas el plazo", un numero o una pregunta.
 */
@Singleton
class ConfirmationDetector {

    fun isConfirmation(entrada: String?): Boolean {
        val texto = entrada?.trim()?.lowercase() ?: return false
        if (texto == ID_CONFIRMAR) return true
        if (texto.isEmpty() || texto.length > LARGO_MAXIMO || '?' in texto) return false

        val palabras = normalizar(texto).split(' ').filter { it.isNotBlank() }
        if (palabras.isEmpty() || palabras.size > PALABRAS_MAXIMAS) return false
        return palabras.all { it in AFIRMACIONES || it in VOCABULARIO } && palabras.any { it in AFIRMACIONES }
    }

    companion object {
        const val ID_CONFIRMAR = "confirm"

        /** Mas alla de esto ya no es una confirmacion, es un mensaje con contenido. */
        const val LARGO_MAXIMO = 80
        const val PALABRAS_MAXIMAS = 12

        /** Minusculas, sin tildes y sin signos: "¡Sí, envíala!" -> "si enviala". */
        fun normalizar(texto: String): String =
            Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9ñ ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Palabras que por si solas dicen "si". */
        private val AFIRMACIONES = setOf(
            "si", "sii", "sip", "simon", "ok", "okay", "okey", "oki", "dale", "listo", "confirmo", "confirmado",
            "acuerdo", "adelante", "correcto", "claro", "perfecto", "bien", "procede", "va", "sale", "vale",
            "emitela", "enviala", "envialo", "enviasela", "enviaselo", "mandala", "mandalo", "mandasela",
            "mandaselo", "hazlo",
        )

        /** Lo que puede acompañar a una afirmacion sin cambiar su sentido. */
        private val VOCABULARIO = setOf(
            // conectores y pronombres
            "me", "te", "se", "la", "lo", "las", "los", "a", "al", "un", "una", "el", "de", "del", "ya", "y",
            "es", "esta", "eso", "asi", "todo", "mi", "tu", "con", "para",
            // cortesia
            "por", "favor", "porfa", "porfavor", "plis", "please", "gracias", "muchas",
            // valoracion
            "parece", "excelente", "genial", "super", "estupendo",
            // verbos de envio
            "puedes", "envia", "enviar", "enviarla", "enviarsela", "manda", "mandar", "mandarla", "mandarsela",
            "emitir", "procedemos", "proceder", "proceda",
            // destino
            "agente", "asesor", "vendedor", "ventas", "revision", "cotizacion",
        )
    }
}
