package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.ErpException
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Cuerpo de POST /api/internal/service-token del ERP (ServiceTokenDtos.Request). */
@Serdeable
data class ServiceTokenRequest(val companyId: Long)

/** Respuesta del ERP: JWT de la empresa y su vigencia en segundos. */
@Serdeable
data class ServiceTokenResponse(val token: String, val expiresIn: Long)

/**
 * Token de servicio contra el ERP, uno POR EMPRESA.
 *
 * El ERP lo emite con la llave de servicio ([ErpConfiguration.serviceKey],
 * la misma AGENT_KEY_ERP con la que el ERP llama a este servicio) para el
 * usuario de sistema «Asistente» de esa empresa. Asi cada canal opera solo
 * los datos de su empresa: el companyId va dentro del JWT y el ERP lo aplica
 * igual que con un usuario normal. Reemplaza al usuario bot global, que solo
 * podia atender a una empresa.
 *
 * Cada token se cachea y se renueva ANTES de expirar: descubrirlo por un 401
 * a mitad de una emision es descubrirlo tarde.
 */
@Singleton
open class ErpTokenService(
    private val config: ErpConfiguration,
    private val clientProvider: ErpHttpClientProvider,
) {

    private val cache = ConcurrentHashMap<Long, TokenVigente>()

    private data class TokenVigente(val valor: String, val expiraEn: Instant)

    open fun currentToken(tenantId: Long): String {
        cache[tenantId]?.let { if (ahora().isBefore(it.expiraEn)) return it.valor }

        if (!config.hasServiceKey()) {
            throw ErpException("Falta AGENT_KEY_ERP: el servicio no puede pedir tokens al ERP")
        }
        val respuesta = solicitar(tenantId)
        // Se renueva con margen: si se apurara hasta el ultimo segundo, una
        // llamada lenta saldria con un token ya vencido.
        val expira = ahora().plusSeconds((respuesta.expiresIn - MARGEN_SEGUNDOS).coerceAtLeast(30))
        cache[tenantId] = TokenVigente(respuesta.token, expira)
        log.info("token de servicio del ERP renovado para la empresa {}, vigente {} s", tenantId, respuesta.expiresIn)
        return respuesta.token
    }

    /** Fuerza la renovacion del token de esa empresa; se usa cuando el ERP responde 401. */
    open fun invalidate(tenantId: Long) {
        cache.remove(tenantId)
    }

    protected open fun ahora(): Instant = Instant.now()

    protected open fun solicitar(tenantId: Long): ServiceTokenResponse = try {
        clientProvider.client().toBlocking().retrieve(
            HttpRequest.POST("${config.baseUrl.trimEnd('/')}$RUTA_TOKEN", ServiceTokenRequest(tenantId))
                .header(CABECERA_LLAVE, config.serviceKey!!),
            ServiceTokenResponse::class.java,
        )
    } catch (e: HttpClientResponseException) {
        throw ErpException(
            when (e.status) {
                HttpStatus.UNAUTHORIZED -> "El ERP rechazo la llave de servicio (AGENT_KEY_ERP)"
                HttpStatus.NOT_FOUND -> "La empresa $tenantId no existe en el ERP"
                HttpStatus.FORBIDDEN -> "La empresa $tenantId esta suspendida o cancelada"
                else -> "No se pudo obtener el token de servicio del ERP (${e.status.code})"
            },
            e,
        )
    } catch (e: Exception) {
        throw ErpException("No se pudo obtener el token de servicio del ERP", e)
    }

    companion object {
        const val RUTA_TOKEN = "/api/internal/service-token"
        const val CABECERA_LLAVE = "X-Api-Key"
        const val MARGEN_SEGUNDOS = 60L
        private val log = LoggerFactory.getLogger(ErpTokenService::class.java)
    }
}
