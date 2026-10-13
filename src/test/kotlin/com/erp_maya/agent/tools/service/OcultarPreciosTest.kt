package com.erp_maya.agent.tools.service

import com.erp_maya.agent.playbook.domain.Limites
import com.erp_maya.agent.playbook.domain.Playbook
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Política de precios del playbook (`limites.mostrar_precios`).
 *
 * Con "total" el cliente solo conoce el total que calcula el ERP (con
 * ganancia, cargos y redondeo): `productos.search` le quita el precio unitario
 * a los resultados para que el modelo no lo repita. El vendedor sigue viéndolo.
 */
class OcultarPreciosTest {

    private val soloTotal = Playbook(limites = Limites(mostrarPrecios = Limites.PRECIOS_TOTAL))

    @Test
    fun `con solo total el cliente no ve precios de catalogo`() {
        assertTrue(ocultaPrecios(soloTotal, "whatsapp"))
    }

    @Test
    fun `el vendedor si los ve aunque la politica sea solo total`() {
        val conVendedor = soloTotal.copy(audiencias = mapOf("panel" to Playbook.AUDIENCIA_VENDEDOR))
        assertFalse(ocultaPrecios(conVendedor, "panel"))
        assertTrue(ocultaPrecios(conVendedor, "whatsapp"))
    }

    @Test
    fun `por defecto se muestran los precios unitarios`() {
        assertFalse(ocultaPrecios(Playbook(), "whatsapp"))
    }

    @Test
    fun `el valor del playbook no distingue mayusculas`() {
        assertTrue(ocultaPrecios(Playbook(limites = Limites(mostrarPrecios = "TOTAL")), "whatsapp"))
    }
}
