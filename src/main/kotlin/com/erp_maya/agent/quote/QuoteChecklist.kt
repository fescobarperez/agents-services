package com.erp_maya.agent.quote

import com.erp_maya.agent.playbook.domain.DatoRequerido
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.QuoteDraft

/**
 * En que va la cotizacion respecto a lo que pide el playbook.
 *
 * Lo calcula el codigo y no el modelo: el prompt recibe el checklist ya
 * resuelto y `cotizacion.enviar` lo usa como compuerta.
 */
object QuoteChecklist {

    data class Item(val dato: DatoRequerido, val listo: Boolean, val valor: String?)

    fun calcular(borrador: QuoteDraft?, playbook: Playbook): List<Item> =
        playbook.cotizacion.datosRequeridos.map { d ->
            when (d.clave) {
                DatoRequerido.CLIENTE -> Item(d, borrador?.customerId != null, borrador?.customerName)
                DatoRequerido.LINEAS -> Item(
                    d,
                    borrador?.quoteId != null || borrador?.pendientes?.isNotEmpty() == true,
                    borrador?.quoteNumber,
                )
                else -> {
                    val valor = borrador?.datos?.get(d.clave)?.takeIf { it.isNotBlank() }
                    Item(d, valor != null, valor)
                }
            }
        }

    /** Lo obligatorio que aun falta, en el orden del playbook. */
    fun faltantes(borrador: QuoteDraft?, playbook: Playbook): List<DatoRequerido> =
        calcular(borrador, playbook).filter { !it.listo && !it.dato.opcional }.map { it.dato }
}
