package com.erp_maya.agent.channel.whatsapp.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** La firma es lo unico que separa el webhook de estar abierto a internet. */
class WhatsAppSignatureTest {

    private val secreto = "secreto-de-pruebas"
    private val firmas = WhatsAppSignature(secreto)
    private val cuerpo = """{"entry":[{"changes":[]}]}""".toByteArray()

    private fun firmar(datos: ByteArray, clave: String = secreto): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clave.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(datos).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `acepta una firma correcta`() {
        assertTrue(firmas.isValid(cuerpo, firmar(cuerpo)))
    }

    @Test
    fun `rechaza si el cuerpo cambio un solo byte`() {
        val firma = firmar(cuerpo)
        val alterado = """{"entry":[{"changes":[ ]}]}""".toByteArray()
        assertFalse(firmas.isValid(alterado, firma))
    }

    @Test
    fun `rechaza una firma hecha con otro secreto`() {
        assertFalse(firmas.isValid(cuerpo, firmar(cuerpo, "otro-secreto")))
    }

    @Test
    fun `rechaza si falta la cabecera`() {
        assertFalse(firmas.isValid(cuerpo, null))
        assertFalse(firmas.isValid(cuerpo, ""))
    }

    @Test
    fun `sin secreto configurado rechaza todo`() {
        // Aceptar sin poder verificar dejaria el webhook abierto a cualquiera
        // que conozca la URL.
        val sinSecreto = WhatsAppSignature("")
        assertFalse(sinSecreto.isValid(cuerpo, firmar(cuerpo)))
        assertFalse(sinSecreto.isConfigured())
    }
}
