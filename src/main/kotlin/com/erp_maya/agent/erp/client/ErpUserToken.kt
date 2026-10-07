package com.erp_maya.agent.erp.client

/**
 * Token del usuario del ERP que origino el turno, cuando el canal lo trae
 * (el widget del ERP lo reenvia en `X-Erp-User-Token`).
 *
 * Con el, las herramientas consultan el ERP COMO ese usuario: el ERP aplica
 * su empresa y sus permisos, y el agente nunca ve mas de lo que la persona
 * que chatea puede ver. Sin el (WhatsApp) se usa el token de servicio.
 *
 * Vive solo mientras dura el turno —el controlador lo fija y lo limpia en un
 * finally— y no se guarda en ninguna parte. Un ThreadLocal alcanza porque el
 * turno completo corre sincrono en el hilo del controlador.
 */
object ErpUserToken {
    private val actual = ThreadLocal<String?>()

    fun get(): String? = actual.get()

    fun <T> con(token: String?, bloque: () -> T): T {
        if (token.isNullOrBlank()) return bloque()
        actual.set(token)
        try {
            return bloque()
        } finally {
            actual.remove()
        }
    }
}
