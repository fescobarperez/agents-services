package com.erp_maya.agent.prompt.service

import com.erp_maya.agent.context.domain.ExecutionContext

/**
 * Lo que un canal le agrega al prompt de cada turno: el estado que el cliente
 * tiene en pantalla y que el historial no cuenta (p. ej. las opciones del
 * planificador, que el visitante tambien cambia con botones).
 *
 * Va en la parte dinamica del prompt, despues de la cotizacion en curso. Un
 * canal sin nada que agregar devuelve null.
 */
interface ContextoDeCanal {
    fun para(contexto: ExecutionContext, conversationId: Long): String?
}
