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

    /**
     * Mas amplio que [isConfirmation]: el cliente aprueba algo que ya vio
     * ("me gusta", "asi esta bien", "ese me encanta"). Mismo criterio cerrado:
     * todas las palabras conocidas y al menos una que apruebe. "No me gusta" o
     * "me gusta pero mas alto" no pasan ("no" y "pero" no estan en la lista).
     * Solo para aprobar un diseño; enviar o aprobar una cotizacion siguen con
     * [isConfirmation].
     */
    fun isApproval(entrada: String?): Boolean {
        if (isConfirmation(entrada)) return true
        val texto = entrada?.trim()?.lowercase() ?: return false
        if (texto.isEmpty() || texto.length > LARGO_MAXIMO || '?' in texto) return false
        val palabras = normalizar(texto).split(' ').filter { it.isNotBlank() }
        if (palabras.isEmpty() || palabras.size > PALABRAS_MAXIMAS) return false
        return palabras.all { it in AFIRMACIONES || it in VOCABULARIO || it in APROBACIONES || it in VOCABULARIO_APROBAR } &&
            palabras.any { it in AFIRMACIONES || it in APROBACIONES }
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

        /** Palabras que aprueban algo que el cliente ya vio. */
        private val APROBACIONES = setOf(
            "gusta", "gusto", "encanta", "encanto", "fascina", "quedo", "queda", "bonito", "bonita", "lindo", "linda",
            "hermoso", "hermosa", "bueno", "buena", "exacto", "justo", "ideal", "eso",
        )

        /** Lo que acompaña a una aprobacion: "ese", "asi", "mucho"... */
        private val VOCABULARIO_APROBAR = setOf(
            "ese", "esa", "este", "esto", "muy", "mucho", "nos", "le", "quiero", "queremos", "seria",
            "lo", "quedaria", "igual", "como",
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
