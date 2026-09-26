package com.erp_maya.agent.model.repository

import com.erp_maya.agent.model.domain.AiProvider
import io.micronaut.cache.annotation.CacheConfig
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.ReadOnly
import jakarta.inject.Singleton

@Singleton
@CacheConfig("ai-providers")
open class AiProviderRepository(private val jdbc: JdbcOperations) {

    @Cacheable
    @ReadOnly
    open fun byCode(code: String): AiProvider? =
        jdbc.prepareStatement(SQL) { stmt ->
            stmt.setString(1, code)
            val rs = stmt.executeQuery()
            if (!rs.next()) {
                emptyList()
            } else {
                listOf(
                    AiProvider(
                        code = rs.getString("code"),
                        baseUrl = rs.getString("base_url").trimEnd('/'),
                        authType = rs.getString("auth_type"),
                        credentialRef = rs.getString("credential_ref"),
                    ),
                )
            }
        }.firstOrNull()

    private companion object {
        const val SQL = """
            SELECT code, base_url, auth_type, credential_ref
            FROM ai_providers WHERE code = ? AND is_active
        """
    }
}
