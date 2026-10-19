package com.erp_maya.agent.channel.whatsapp.service

import com.erp_maya.agent.api.dto.InputImage
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.vision.service.ImageSource
import jakarta.inject.Singleton

/** Las fotos de WhatsApp se bajan de Meta con el media id que trae el webhook. */
@Singleton
class WhatsAppImageSource(private val cliente: WhatsAppCloudClient) : ImageSource {

    override val source: String = InputImage.SOURCE_WHATSAPP

    override fun descargar(ref: String, maxBytes: Long): ByteArray = cliente.descargarMedia(ref, maxBytes).bytes
}
