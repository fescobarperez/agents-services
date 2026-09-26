package com.erp_maya.agent.context.domain

/**
 * El turno no se puede atender.
 *
 * Existe para que el fallo sea cerrado: cuando no hay agente vigente, la
 * empresa tiene el bot apagado o la asignacion es ambigua, no se responde con
 * datos ni se asume un valor por defecto. Se escala.
 */
class AgentUnavailableException(val reason: Reason, message: String) : RuntimeException(message) {

    enum class Reason {
        /** No existe el canal, o esta inactivo. */
        UNKNOWN_CHANNEL,

        /** El canal existe pero ninguna fila de channel_agents esta vigente. */
        NO_ACTIVE_AGENT,

        /**
         * El canal atiende a mas de una empresa y el mensaje no dice a cual.
         * Adivinar aqui significaria contestar con datos de otro cliente.
         */
        AMBIGUOUS_TENANT,

        /** La empresa tiene el bot deshabilitado en agent_tenant_policies. */
        TENANT_DISABLED,
    }
}
