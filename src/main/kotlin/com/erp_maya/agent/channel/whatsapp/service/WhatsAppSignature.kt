package com.erp_maya.agent.channel.whatsapp.service

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verificacion de `X-Hub-Signature-256`.
 *
 * Se calcula sobre el cuerpo CRUDO y antes de deserializar: si se validara
 * sobre el objeto ya parseado, cualquier diferencia de serializacion romperia
 * la firma, y peor aun, estariamos parseando datos de un remitente que todavia
 * no hemos autenticado.
 */
@Singleton
class WhatsAppSignature(
    @param:Value("\${whatsapp.app-secret:}") private val appSecret: String,
) {

    fun isConfigured(): Boolean = appSecret.isNotBlank()

    fun isValid(cuerpoCrudo: ByteArray, cabecera: String?): Boolean {
        if (!isConfigured()) {
            // Sin secreto no se puede verificar nada. Aceptar seria dejar el
            // webhook abierto a cualquiera que conozca la URL.
            log.error("WHATSAPP_APP_SECRET no esta configurado: se rechaza el webhook")
            return false
        }
        val recibida = cabecera?.removePrefix(PREFIJO)?.lowercase() ?: return false
        val esperada = hmac(cuerpoCrudo)
        // Comparacion en tiempo constante: un equals normal se rinde en el
        // primer byte distinto y eso deja medir la firma correcta.
        return MessageDigest.isEqual(
            esperada.toByteArray(Charsets.UTF_8),
            recibida.toByteArray(Charsets.UTF_8),
        )
    }

    private fun hmac(cuerpo: ByteArray): String {
        val mac = Mac.getInstance(ALGORITMO)
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), ALGORITMO))
        return mac.doFinal(cuerpo).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ALGORITMO = "HmacSHA256"
        const val PREFIJO = "sha256="
        private val log = LoggerFactory.getLogger(WhatsAppSignature::class.java)
    }
}
