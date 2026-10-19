package com.erp_maya.agent.vision.service

/**
 * De donde se baja una foto que llego por un canal. Cada canal que reciba
 * fotos aporta su implementacion (hoy solo WhatsApp).
 */
interface ImageSource {

    /** Valor de `InputImage.source` que atiende. */
    val source: String

    /** Los bytes de la foto, solo en memoria. Falla si pasa de [maxBytes]. */
    fun descargar(ref: String, maxBytes: Long): ByteArray
}
