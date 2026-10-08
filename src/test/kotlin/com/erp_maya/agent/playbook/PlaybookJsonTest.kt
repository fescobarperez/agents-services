package com.erp_maya.agent.playbook

import com.erp_maya.agent.playbook.domain.Playbook
import io.micronaut.serde.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** El JSON de `agent_playbooks.config` se lee con los nombres en snake_case y con valores por defecto. */
class PlaybookJsonTest {

    private val json = ObjectMapper.getDefault()

    @Test
    fun `lee el playbook sembrado`() {
        val p = json.readValue(
            """
            {"audiencias": {"whatsapp": "cliente", "erp": "vendedor"},
             "limites": {"monto_maximo": 25000, "lineas_maximas": 30, "fuera_de_alcance": ["credito"]},
             "cotizacion": {
               "datos_requeridos": [
                 {"clave": "cliente", "etiqueta": "Cliente", "origen": "sistema"},
                 {"clave": "direccion_entrega", "etiqueta": "Direccion", "origen": "cliente"},
                 {"clave": "fecha_entrega", "etiqueta": "Fecha", "origen": "cliente", "opcional": true}
               ],
               "cierre": {"al_enviar_pdf": true, "inactividad_horas": 12, "si_cambia_estado_en_erp": false}
             }}
            """.trimIndent(),
            Playbook::class.java,
        )
        assertEquals("vendedor", p.audiencia("erp"))
        assertEquals(0, BigDecimal("25000").compareTo(p.limites.montoMaximo))
        assertEquals(listOf("direccion_entrega", "fecha_entrega"), p.cotizacion.datosDelCliente().map { it.clave })
        assertEquals(12L, p.cotizacion.cierre.inactividadHoras)
        assertFalse(p.cotizacion.cierre.siCambiaEstadoEnErp)
        assertTrue(p.cotizacion.envio.requiereConfirmacion) // no vino: valor por defecto
    }

    @Test
    fun `un JSON vacio da el playbook por defecto`() {
        val p = json.readValue("{}", Playbook::class.java)
        assertEquals(Playbook(), p)
    }
}
