package com.erp_maya.agent.channel.web

import com.erp_maya.agent.api.dto.InputImage
import com.erp_maya.agent.channel.whatsapp.client.MediaNoDisponibleException
import com.erp_maya.agent.vision.service.ImageSource
import io.micronaut.context.annotation.Value
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Una foto o archivo del chat web, con sus bytes. */
class WebMediaArchivo(
    val id: String,
    val widgetKey: String,
    val sessionRef: String,
    val kind: String,
    val mimeType: String,
    val fileName: String?,
    val bytes: ByteArray,
)

/** Algo que el visitante recibe despues de su turno (un render, un aviso). */
data class WebEntrega(
    val id: Long,
    val kind: String,
    val text: String?,
    val mediaId: String?,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Int?,
)

/**
 * Media del chat web. En WhatsApp las fotos viven en Meta; aqui no hay quien
 * las guarde y el render necesita la foto base despues, asi que se guardan
 * en la base con vencimiento y [WebMediaLimpieza] borra lo vencido.
 *
 * Los ids son aleatorios y largos (`wm_` + 32): saberlo es la llave para
 * verlo, igual que un enlace de Meta. Ademas se exige la llave del widget.
 */
@Singleton
open class WebMediaRepository(
    private val jdbc: JdbcOperations,
    @param:Value("\${web.media.retention-days:7}") private val diasRetencion: Long,
) {

    @Transactional
    open fun guardar(widgetKey: String, session: String, kind: String, mime: String, nombre: String?, bytes: ByteArray): String {
        val id = nuevoId()
        val vence = Timestamp.from(Instant.now().plus(diasRetencion.coerceAtLeast(1), ChronoUnit.DAYS))
        jdbc.prepareStatement(
            """
            INSERT INTO web_media (id, widget_key, session_ref, kind, mime_type, file_name, size_bytes, content, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { st ->
            st.setString(1, id); st.setString(2, widgetKey); st.setString(3, session); st.setString(4, kind)
            st.setString(5, mime); st.setString(6, nombre?.take(160)); st.setInt(7, bytes.size); st.setBytes(8, bytes)
            st.setTimestamp(9, vence)
            st.executeUpdate()
        }
        return id
    }

    /** Null si no existe o ya vencio. */
    @Transactional(readOnly = true)
    open fun leer(id: String): WebMediaArchivo? {
        if (!ID.matches(id)) return null
        // prepareStatement no admite resultado nulo: se envuelve en lista.
        return jdbc.prepareStatement(
            """
            SELECT id, widget_key, session_ref, kind, mime_type, file_name, content
            FROM web_media WHERE id = ? AND expires_at > now()
            """.trimIndent(),
        ) { st ->
            st.setString(1, id)
            val rs = st.executeQuery()
            if (!rs.next()) emptyList<WebMediaArchivo>() else listOf(WebMediaArchivo(
                id = rs.getString("id"),
                widgetKey = rs.getString("widget_key"),
                sessionRef = rs.getString("session_ref"),
                kind = rs.getString("kind"),
                mimeType = rs.getString("mime_type"),
                fileName = rs.getString("file_name"),
                bytes = rs.getBytes("content"),
            ))
        }.firstOrNull()
    }

    @Transactional
    open fun entregar(widgetKey: String, session: String, kind: String, texto: String?, mediaId: String?): Long =
        jdbc.prepareStatement(
            "INSERT INTO web_outbox (widget_key, session_ref, kind, text, media_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
        ) { st ->
            st.setString(1, widgetKey); st.setString(2, session); st.setString(3, kind)
            st.setString(4, texto); st.setString(5, mediaId)
            val rs = st.executeQuery(); rs.next(); rs.getLong(1)
        }

    /** Lo entregado a esa sesion despues de `despues`, en orden. */
    @Transactional(readOnly = true)
    open fun entregas(widgetKey: String, session: String, despues: Long): List<WebEntrega> =
        jdbc.prepareStatement(
            """
            SELECT o.id, o.kind, o.text, o.media_id, m.mime_type, m.file_name, m.size_bytes
            FROM web_outbox o LEFT JOIN web_media m ON m.id = o.media_id AND m.expires_at > now()
            WHERE o.widget_key = ? AND o.session_ref = ? AND o.id > ?
            ORDER BY o.id LIMIT 20
            """.trimIndent(),
        ) { st ->
            st.setString(1, widgetKey); st.setString(2, session); st.setLong(3, despues)
            val rs = st.executeQuery()
            val out = mutableListOf<WebEntrega>()
            while (rs.next()) {
                out += WebEntrega(
                    id = rs.getLong("id"),
                    kind = rs.getString("kind"),
                    text = rs.getString("text"),
                    mediaId = rs.getString("media_id"),
                    mimeType = rs.getString("mime_type"),
                    fileName = rs.getString("file_name"),
                    sizeBytes = rs.getObject("size_bytes")?.let { (it as Number).toInt() },
                )
            }
            out
        }

    /** La ultima entrega de la sesion: el widget arranca a consultar desde ahi. */
    @Transactional(readOnly = true)
    open fun ultimaEntrega(widgetKey: String, session: String): Long =
        jdbc.prepareStatement("SELECT coalesce(max(id), 0) FROM web_outbox WHERE widget_key = ? AND session_ref = ?") { st ->
            st.setString(1, widgetKey); st.setString(2, session)
            val rs = st.executeQuery(); rs.next(); rs.getLong(1)
        }

    /** La sesion del widget de una conversacion: a quien entregarle un render. */
    @Transactional(readOnly = true)
    open fun sesionDe(conversationId: Long): String? =
        jdbc.prepareStatement("SELECT external_ref FROM conversations WHERE id = ?") { st ->
            st.setLong(1, conversationId)
            val rs = st.executeQuery()
            if (rs.next()) listOfNotNull(rs.getString(1)) else emptyList<String>()
        }.firstOrNull()

    /** Renders encolados o en curso de la conversacion de esa sesion: si hay, el widget sigue consultando. */
    @Transactional(readOnly = true)
    open fun rendersEnCamino(channelId: Long, session: String): Boolean =
        jdbc.prepareStatement(
            """
            SELECT EXISTS (
                SELECT 1 FROM render_jobs r JOIN conversations c ON c.id = r.conversation_id
                WHERE c.channel_id = ? AND c.external_ref = ? AND r.status IN ('pending', 'processing')
            )
            """.trimIndent(),
        ) { st ->
            st.setLong(1, channelId); st.setString(2, session)
            val rs = st.executeQuery(); rs.next(); rs.getBoolean(1)
        }

    /** Borra lo vencido y las entregas de mas de [diasRetencion]. Devuelve cuantas fotos/archivos borro. */
    @Transactional
    open fun limpiar(): Int {
        val media = jdbc.prepareStatement("DELETE FROM web_media WHERE expires_at <= now()") { it.executeUpdate() }
        jdbc.prepareStatement("DELETE FROM web_outbox WHERE created_at < now() - make_interval(days => ?)") { st ->
            st.setInt(1, diasRetencion.toInt().coerceAtLeast(1)); st.executeUpdate()
        }
        return media
    }

    companion object {
        const val PREFIJO = "wm_"
        val ID = Regex("^wm_[A-Za-z0-9]{32}$")
        private val azar = SecureRandom()
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun nuevoId(): String = PREFIJO + (1..32).map { ABC[azar.nextInt(ABC.length)] }.joinToString("")

        fun esWeb(ref: String?): Boolean = ref != null && ref.startsWith(PREFIJO)
    }
}

/** Las fotos del chat web se leen de [WebMediaRepository], como las de WhatsApp se bajan de Meta. */
@Singleton
class WebImageSource(private val media: WebMediaRepository) : ImageSource {

    override val source: String = InputImage.SOURCE_WEB

    override fun descargar(ref: String, maxBytes: Long): ByteArray {
        val archivo = media.leer(ref) ?: throw MediaNoDisponibleException(ref, "vencida o inexistente en el chat web")
        if (archivo.bytes.size > maxBytes) throw MediaNoDisponibleException(ref, "pasa del tamaño máximo")
        return archivo.bytes
    }
}

/** Borra cada hora las fotos y archivos vencidos del chat web. */
@Singleton
open class WebMediaLimpieza(private val media: WebMediaRepository) {

    @Scheduled(fixedDelay = "\${web.media.cleanup-interval:1h}", initialDelay = "\${web.media.cleanup-initial-delay:5m}")
    open fun alTick() {
        runCatching { media.limpiar() }
            .onSuccess { if (it > 0) log.info("chat web: {} foto(s)/archivo(s) vencidos borrados", it) }
            .onFailure { log.error("no se pudo limpiar la media vencida del chat web", it) }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WebMediaLimpieza::class.java)
    }
}
