package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.ErpException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Un token por empresa: cada canal opera solo los datos de la suya, y renovar
 * o invalidar el de una no toca el de otra.
 */
class ErpTokenServiceTest {

    private val config = ErpConfiguration().apply { serviceKey = "llave-de-prueba" }
    private val provider = ErpHttpClientProvider(config)

    @AfterEach
    fun cerrar() = provider.cerrar()

    /** ERP falso: cuenta las peticiones por empresa y controla el reloj. */
    private inner class Falso(val vigencia: Long = 3600) : ErpTokenService(config, provider) {
        val pedidos = mutableListOf<Long>()
        var reloj: Instant = Instant.parse("2026-10-14T12:00:00Z")
        override fun ahora() = reloj
        override fun solicitar(tenantId: Long): ServiceTokenResponse {
            pedidos += tenantId
            return ServiceTokenResponse("jwt-$tenantId-${pedidos.size}", vigencia)
        }
    }

    @Test
    fun cadaEmpresaTieneSuToken() {
        val s = Falso()
        assertEquals("jwt-1-1", s.currentToken(1))
        assertEquals("jwt-2-2", s.currentToken(2))
        assertEquals("jwt-1-1", s.currentToken(1))
        assertEquals(listOf(1L, 2L), s.pedidos)
    }

    @Test
    fun seRenuevaAntesDeVencer() {
        val s = Falso(vigencia = 3600)
        s.currentToken(1)
        s.reloj = s.reloj.plusSeconds(3600 - ErpTokenService.MARGEN_SEGUNDOS - 1)
        s.currentToken(1)
        assertEquals(1, s.pedidos.size)
        s.reloj = s.reloj.plusSeconds(2)
        s.currentToken(1)
        assertEquals(2, s.pedidos.size)
    }

    @Test
    fun invalidarSoloAfectaALaEmpresaIndicada() {
        val s = Falso()
        s.currentToken(1)
        s.currentToken(2)
        s.invalidate(1)
        s.currentToken(1)
        s.currentToken(2)
        assertEquals(listOf(1L, 2L, 1L), s.pedidos)
    }

    @Test
    fun sinLlaveNoSePideNada() {
        config.serviceKey = " "
        val s = Falso()
        val e = assertThrows(ErpException::class.java) { s.currentToken(1) }
        assertTrue(e.message!!.contains("AGENT_KEY_ERP"))
        assertTrue(s.pedidos.isEmpty())
    }
}
