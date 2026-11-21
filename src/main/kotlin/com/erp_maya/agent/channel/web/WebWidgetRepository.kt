package com.erp_maya.agent.channel.web

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

/** Un widget visto desde la entrada publica. */
data class WebWidget(
    val channelId: Long,
    val tenantId: Long,
    val publicKey: String,
    val active: Boolean,
    val allowedOrigins: List<String>,
    val title: String?,
    val greeting: String?,
    val color: String?,
    /** El carrito muestra precios y total (si no, solo productos y cantidades). */
    val showPrices: Boolean = true,
)

@Singleton
open class WebWidgetRepository(private val jdbc: JdbcOperations) {

    @Transactional(readOnly = true)
    open fun porLlave(llave: String): WebWidget? =
        jdbc.prepareStatement(
            """
            SELECT w.channel_id, w.tenant_id, w.public_key, c.is_active, ARRAY(SELECT jsonb_array_elements_text(w.allowed_origins)) AS origins,
                   w.title, w.greeting, w.color, w.show_prices
            FROM web_widgets w JOIN channels c ON c.id = w.channel_id
            WHERE w.public_key = ?
            """.trimIndent(),
        ) { st ->
            st.setString(1, llave)
            val rs = st.executeQuery()
            val out = mutableListOf<WebWidget>()
            while (rs.next()) {
                out += WebWidget(
                    channelId = rs.getLong("channel_id"),
                    tenantId = rs.getLong("tenant_id"),
                    publicKey = rs.getString("public_key"),
                    active = rs.getBoolean("is_active"),
                    allowedOrigins = (rs.getArray("origins")?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                    title = rs.getString("title"),
                    greeting = rs.getString("greeting"),
                    color = rs.getString("color"),
                    showPrices = rs.getBoolean("show_prices"),
                )
            }
            out
        }.firstOrNull()

    /** La conversacion de esa sesion del widget, si ya escribio algo. */
    @Transactional(readOnly = true)
    open fun conversacion(channelId: Long, sesion: String): Long? =
        jdbc.prepareStatement("SELECT id FROM conversations WHERE channel_id = ? AND external_ref = ? ORDER BY id DESC LIMIT 1") { st ->
            st.setLong(1, channelId); st.setString(2, sesion)
            val rs = st.executeQuery()
            if (rs.next()) listOf(rs.getLong(1)) else emptyList<Long>()
        }.firstOrNull()
}
