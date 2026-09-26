package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.ErpException
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Serdeable
data class TokenRequest(val clientId: String, val clientSecret: String)

@Serdeable
data class TokenResponse(val token: String, @Nullable val expiresIn: Long? = null)

/**
 * Token de servicio contra el ERP (fase 1: client credentials).
 *
 * Una identidad de maquina para todo el servicio, no un usuario por agente:
 * multiplicar usuarios multiplica credenciales que rotar y ensucia la
 * auditoria del ERP haciendo pasar al bot por una persona. Que agente y que
 * empresa actuan viaja en cabeceras, no en la identidad.
 *
 * El token se cachea y se renueva ANTES de expirar: descubrirlo por un 401 a
 * mitad de una emision es descubrirlo tarde.
 */
@Singleton
open class ErpTokenService(
    private val config: ErpConfiguration,
    @param:Nullable private val clientProvider: ErpHttpClientProvider,
) {

    private val cache = AtomicReference<TokenVigente?>(null)

    private data class TokenVigente(val valor: String, val expiraEn: Instant)

    open fun currentToken(): String {
        cache.get()?.let { if (Instant.now().isBefore(it.expiraEn)) return it.valor }

        if (!config.hasCredentials()) {
            throw ErpException(
                "Faltan ERP_CLIENT_ID y ERP_CLIENT_SECRET: el servicio no puede autenticarse contra el ERP",
            )
        }

        val respuesta = try {
            clientProvider.client().toBlocking().retrieve(
                HttpRequest.POST(
                    "${config.baseUrl.trimEnd('/')}$RUTA_TOKEN",
                    TokenRequest(config.clientId!!, config.clientSecret!!),
                ),
                TokenResponse::class.java,
            )
        } catch (e: Exception) {
            throw ErpException("No se pudo obtener el token de servicio del ERP", e)
        }

        val vigencia = respuesta.expiresIn ?: VIGENCIA_POR_DEFECTO
        // Se renueva con margen: si se apurara hasta el ultimo segundo, una
        // llamada lenta saldria con un token ya vencido.
        val expira = Instant.now().plusSeconds((vigencia - MARGEN_SEGUNDOS).coerceAtLeast(30))
        cache.set(TokenVigente(respuesta.token, expira))
        log.info("token de servicio del ERP renovado, vigente {} s", vigencia)
        return respuesta.token
    }

    /** Fuerza la renovacion; se usa cuando el ERP responde 401. */
    open fun invalidate() = cache.set(null)

    companion object {
        /**
         * TODO(ruta por validar): el ERP todavia no expone client credentials.
         * Mientras tanto, apunta a donde deberia vivir.
         */
        const val RUTA_TOKEN = "/api/auth/token"

        const val VIGENCIA_POR_DEFECTO = 900L
        const val MARGEN_SEGUNDOS = 60L
        private val log = LoggerFactory.getLogger(ErpTokenService::class.java)
    }
}
