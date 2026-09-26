package com.erp_maya.agent.channel.whatsapp.service

import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.erp_maya.agent.channel.whatsapp.domain.WhatsAppWebhook

@MicronautTest(startApplication = false)
class WhatsAppParserTest {

    @Inject
    lateinit var json: ObjectMapper

    private val parser = WhatsAppParser()

    private fun parsear(cuerpo: String) =
        parser.extraer(requireNotNull(json.readValue(cuerpo, WhatsAppWebhook::class.java)))

    @Test
    fun `extrae un mensaje de texto con su cuenta y remitente`() {
        val mensajes = parsear(
            """
            {"entry":[{"changes":[{"value":{
              "metadata":{"phone_number_id":"15550001111"},
              "messages":[{"id":"wamid.AAA","from":"50255551234","type":"text",
                           "text":{"body":"tienen cemento?"}}]}}]}]}
            """.trimIndent(),
        )
        assertEquals(1, mensajes.size)
        assertEquals("wamid.AAA", mensajes[0].wamid)
        assertEquals("50255551234", mensajes[0].de)
        // La cuenta se normaliza al formato de `channels.account_ref`.
        assertEquals("wa:15550001111", mensajes[0].cuenta)
        assertEquals("tienen cemento?", mensajes[0].texto)
    }

    @Test
    fun `de un boton toma el id, no la etiqueta`() {
        // Es lo que permite reconocer una confirmacion sin interpretar texto:
        // el boton se ve "Confirmar" pero viaja como `confirm`.
        val mensajes = parsear(
            """
            {"entry":[{"changes":[{"value":{
              "metadata":{"phone_number_id":"15550001111"},
              "messages":[{"id":"wamid.BBB","from":"50255551234","type":"interactive",
                "interactive":{"type":"button_reply",
                               "button_reply":{"id":"confirm","title":"Confirmar"}}}]}}]}]}
            """.trimIndent(),
        )
        assertEquals("confirm", mensajes.single().texto)
    }

    @Test
    fun `los acuses de entrega no abren turno`() {
        val mensajes = parsear(
            """
            {"entry":[{"changes":[{"value":{
              "metadata":{"phone_number_id":"15550001111"},
              "statuses":[{"id":"wamid.CCC","status":"delivered"}]}}]}]}
            """.trimIndent(),
        )
        assertTrue(mensajes.isEmpty())
    }

    @Test
    fun `un cuerpo sin nada util no revienta`() {
        assertTrue(parsear("""{"entry":[]}""").isEmpty())
        assertTrue(parsear("""{}""").isEmpty())
    }

    @Test
    fun `un tipo que no manejamos se descarta sin texto`() {
        val mensajes = parsear(
            """
            {"entry":[{"changes":[{"value":{
              "metadata":{"phone_number_id":"15550001111"},
              "messages":[{"id":"wamid.DDD","from":"502","type":"audio"}]}}]}]}
            """.trimIndent(),
        )
        // Llega el mensaje pero sin texto: la transcripcion esta fuera de alcance.
        assertEquals(1, mensajes.size)
        assertEquals(null, mensajes.single().texto)
    }
}
