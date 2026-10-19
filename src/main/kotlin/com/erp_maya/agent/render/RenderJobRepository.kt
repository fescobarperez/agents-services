package com.erp_maya.agent.render

import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal

/** Un render por hacer. Solo referencias: la foto base vive en Meta. */
data class RenderJob(
    val id: Long,
    val conversationId: Long,
    val tenantId: Long,
    /** phone_number_id que envia, sin el prefijo `wa:`. */
    val accountRef: String,
    val recipient: String,
    val modelId: Long,
    val baseMediaId: String,
    val prompt: String,
    val renderNumber: Int,
    val renderMax: Int,
    val attempts: Int,
)

/** Donde se encolan los renders (la tabla; en pruebas, un doble). */
interface ColaRenders {
    fun encolar(
        conversationId: Long,
        tenantId: Long,
        accountRef: String,
        recipient: String,
        modelId: Long,
        baseMediaId: String,
        prompt: String,
        renderNumber: Int,
        renderMax: Int,
    ): Long
}

/** En que quedo un render. */
data class RenderResultado(
    val id: Long,
    val conversationId: Long,
    val status: String,
    val resultMediaId: String?,
    val renderNumber: Int,
    val renderMax: Int,
)

/** Consulta del resultado de un render (la tabla; en pruebas, un doble). */
interface ResultadosRender {
    fun resultado(id: Long): RenderResultado?
}

/** El modelo de imagenes con el que se hace el render y su costo por imagen. */
data class ModeloImagen(val id: Long, val providerCode: String, val modelName: String, val imageCost: BigDecimal)

/**
 * Cola `render_jobs`. Mismo patron que `inbound_events`: se reclama con
 * FOR UPDATE SKIP LOCKED y un trabajo que quedo a medias (el proceso murio)
 * se rescata a los 5 minutos.
 */
@Singleton
open class RenderJobRepository(private val jdbc: JdbcOperations) : ColaRenders, ResultadosRender {

    @Transactional
    // prepareStatement exige un resultado no nulo: se devuelve una lista (0 o 1).
    override fun resultado(id: Long): RenderResultado? = jdbc.prepareStatement(SQL_RESULTADO) { stmt ->
        stmt.setLong(1, id)
        val rs = stmt.executeQuery()
        listOfNotNull(
            if (rs.next()) RenderResultado(
                id = rs.getLong("id"),
                conversationId = rs.getLong("conversation_id"),
                status = rs.getString("status"),
                resultMediaId = rs.getString("result_media_id"),
                renderNumber = rs.getInt("render_number"),
                renderMax = rs.getInt("render_max"),
            ) else null,
        )
    }.firstOrNull()

    @Transactional
    override fun encolar(
        conversationId: Long,
        tenantId: Long,
        accountRef: String,
        recipient: String,
        modelId: Long,
        baseMediaId: String,
        prompt: String,
        renderNumber: Int,
        renderMax: Int,
    ): Long = jdbc.prepareStatement(SQL_ENCOLAR) { stmt ->
        stmt.setLong(1, conversationId)
        stmt.setLong(2, tenantId)
        stmt.setString(3, accountRef)
        stmt.setString(4, recipient)
        stmt.setLong(5, modelId)
        stmt.setString(6, baseMediaId)
        stmt.setString(7, prompt)
        stmt.setInt(8, renderNumber)
        stmt.setInt(9, renderMax)
        val rs = stmt.executeQuery()
        check(rs.next()) { "El render no se encolo" }
        rs.getLong(1)
    }

    @Transactional
    open fun reclamar(worker: String, limite: Int): List<RenderJob> =
        jdbc.prepareStatement(SQL_RECLAMAR) { stmt ->
            stmt.setString(1, worker)
            stmt.setInt(2, limite)
            val rs = stmt.executeQuery()
            val filas = mutableListOf<RenderJob>()
            while (rs.next()) {
                filas += RenderJob(
                    id = rs.getLong("id"),
                    conversationId = rs.getLong("conversation_id"),
                    tenantId = rs.getLong("tenant_id"),
                    accountRef = rs.getString("account_ref"),
                    recipient = rs.getString("recipient"),
                    modelId = rs.getLong("model_id"),
                    baseMediaId = rs.getString("base_media_id"),
                    prompt = rs.getString("prompt"),
                    renderNumber = rs.getInt("render_number"),
                    renderMax = rs.getInt("render_max"),
                    attempts = rs.getInt("attempts"),
                )
            }
            filas
        }

    @Transactional
    open fun terminar(id: Long, mediaId: String, wamid: String, costo: BigDecimal) {
        jdbc.prepareStatement(SQL_TERMINAR) { stmt ->
            stmt.setString(1, mediaId)
            stmt.setString(2, wamid)
            stmt.setBigDecimal(3, costo)
            stmt.setLong(4, id)
            stmt.executeUpdate()
        }
    }

    /** Lo da por perdido. */
    @Transactional
    open fun fallar(id: Long, error: String) {
        jdbc.prepareStatement(SQL_FALLAR) { stmt ->
            stmt.setString(1, error.take(1000))
            stmt.setLong(2, id)
            stmt.executeUpdate()
        }
    }

    /** Lo devuelve a la cola para otro intento dentro de [segundos]. */
    @Transactional
    open fun reintentar(id: Long, segundos: Int, error: String) {
        jdbc.prepareStatement(SQL_REINTENTAR) { stmt ->
            stmt.setString(1, segundos.toString())
            stmt.setString(2, error.take(1000))
            stmt.setLong(3, id)
            stmt.executeUpdate()
        }
    }

    @Transactional
    open fun modelo(modelId: Long): ModeloImagen? = jdbc.prepareStatement(SQL_MODELO) { stmt ->
        stmt.setLong(1, modelId)
        val rs = stmt.executeQuery()
        listOfNotNull(
            if (rs.next()) ModeloImagen(rs.getLong("id"), rs.getString("code"), rs.getString("model_name"), rs.getBigDecimal("image_cost"))
            else null,
        )
    }.firstOrNull()

    private companion object {
        const val SQL_RESULTADO = """
            SELECT id, conversation_id, status, result_media_id, render_number, render_max
            FROM render_jobs WHERE id = ?
        """

        const val SQL_ENCOLAR = """
            INSERT INTO render_jobs (conversation_id, tenant_id, account_ref, recipient, model_id,
                                     base_media_id, prompt, render_number, render_max)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
        """

        const val SQL_RECLAMAR = """
            UPDATE render_jobs SET status = 'processing', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id IN (
                SELECT id FROM render_jobs
                WHERE (status = 'pending' AND available_at <= now())
                   OR (status = 'processing' AND locked_at < now() - interval '5 minutes')
                ORDER BY available_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, conversation_id, tenant_id, account_ref, recipient, model_id, base_media_id,
                      prompt, render_number, render_max, attempts
        """

        const val SQL_TERMINAR = """
            UPDATE render_jobs SET status = 'done', result_media_id = ?, sent_message_id = ?, cost_usd = ?,
                                   locked_by = NULL, finished_at = now(), last_error = NULL
            WHERE id = ?
        """

        const val SQL_FALLAR = """
            UPDATE render_jobs SET status = 'failed', last_error = ?, locked_by = NULL, finished_at = now()
            WHERE id = ?
        """

        const val SQL_REINTENTAR = """
            UPDATE render_jobs SET status = 'pending', locked_by = NULL, locked_at = NULL,
                                   available_at = now() + (? || ' seconds')::interval, last_error = ?
            WHERE id = ?
        """

        const val SQL_MODELO = """
            SELECT m.id, p.code, m.model_name, m.image_cost
            FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
            WHERE m.id = ? AND m.is_active AND p.is_active
        """
    }
}
