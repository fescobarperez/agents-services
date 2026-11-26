package com.erp_maya.agent.channel.planner

import io.micronaut.core.type.Argument
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.serde.ObjectMapper
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

@Singleton
open class PlannerWidgetRepository(private val jdbc: JdbcOperations, private val json: ObjectMapper) {

    @Transactional(readOnly = true)
    open fun porLlave(llave: String): PlannerWidget? = buscar("w.public_key = ?") { it.setString(1, llave) }

    /** El planificador de un canal (para el turno del agente, que ya trae el canal resuelto). */
    @Transactional(readOnly = true)
    open fun porCanal(channelId: Long): PlannerWidget? = buscar("w.channel_id = ?") { it.setLong(1, channelId) }

    private fun buscar(condicion: String, bind: (java.sql.PreparedStatement) -> Unit): PlannerWidget? =
        jdbc.prepareStatement(
            """
            SELECT w.channel_id, w.tenant_id, w.public_key, c.is_active, ARRAY(SELECT jsonb_array_elements_text(w.allowed_origins)) AS origins,
                   w.name, w.title, w.accent_color, w.lang, w.project_types::text AS project_types,
                   w.category_ids::text AS category_ids, w.fit_threshold_pct,
                   w.retention_days, w.max_options, w.contact_whatsapp, p.locale
            FROM planner_widgets w
            JOIN channels c ON c.id = w.channel_id
            LEFT JOIN agent_tenant_policies p ON p.tenant_id = w.tenant_id
            WHERE $condicion
            """.trimIndent(),
        ) { st ->
            bind(st)
            val rs = st.executeQuery()
            val out = mutableListOf<PlannerWidget>()
            while (rs.next()) {
                out += PlannerWidget(
                    channelId = rs.getLong("channel_id"),
                    tenantId = rs.getLong("tenant_id"),
                    publicKey = rs.getString("public_key"),
                    active = rs.getBoolean("is_active"),
                    allowedOrigins = (rs.getArray("origins")?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                    name = rs.getString("name"),
                    title = rs.getString("title"),
                    accentColor = rs.getString("accent_color"),
                    lang = rs.getString("lang"),
                    locale = rs.getString("locale"),
                    projectTypes = tipos(rs.getString("project_types")),
                    categoryIds = categorias(rs.getString("category_ids")),
                    fitThresholdPct = rs.getBigDecimal("fit_threshold_pct"),
                    retentionDays = rs.getInt("retention_days"),
                    maxOptions = rs.getInt("max_options"),
                    contactWhatsapp = rs.getString("contact_whatsapp"),
                )
            }
            out
        }.firstOrNull()

    private fun tipos(crudo: String?): List<ProjectType> =
        crudo?.let { runCatching { json.readValue(it, Argument.listOf(ProjectType::class.java)) }.getOrNull() }.orEmpty()

    private fun categorias(crudo: String?): List<Long> =
        crudo?.let { runCatching { json.readValue(it, Argument.listOf(Long::class.javaObjectType)) }.getOrNull() }.orEmpty()
}

/**
 * Sesiones, opciones y eventos del planificador. Las opciones no se borran
 * dentro de la sesion; al vencer la sesion cae todo en cascada.
 */
@Singleton
open class PlannerSessionRepository(private val jdbc: JdbcOperations, private val json: ObjectMapper) {

    @Transactional
    open fun crear(id: String, w: PlannerWidget, budget: BigDecimal?, projectType: String?, vence: Instant) {
        jdbc.prepareStatement(
            """
            INSERT INTO planner_sessions (id, channel_id, tenant_id, budget, project_type, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { st ->
            st.setString(1, id); st.setLong(2, w.channelId); st.setLong(3, w.tenantId)
            if (budget == null) st.setNull(4, Types.NUMERIC) else st.setBigDecimal(4, budget)
            st.setString(5, projectType); st.setTimestamp(6, Timestamp.from(vence))
            st.executeUpdate()
        }
        evento(id, EV_INICIO, mapOf("budget" to budget, "projectType" to projectType))
    }

    /** La sesion de ese planificador, si existe y no ha vencido. */
    @Transactional(readOnly = true)
    open fun sesion(id: String, channelId: Long): PlannerSessionRow? =
        jdbc.prepareStatement(
            """
            SELECT id, channel_id, tenant_id, budget, project_type, notes::text AS notes, active_letter, created_at, expires_at,
                   photo_media_id
            FROM planner_sessions WHERE id = ? AND channel_id = ? AND expires_at > now()
            """.trimIndent(),
        ) { st ->
            st.setString(1, id); st.setLong(2, channelId)
            val rs = st.executeQuery()
            if (!rs.next()) emptyList() else listOf(PlannerSessionRow(
                id = rs.getString("id"),
                channelId = rs.getLong("channel_id"),
                tenantId = rs.getLong("tenant_id"),
                budget = rs.getBigDecimal("budget"),
                projectType = rs.getString("project_type"),
                notes = leer(rs.getString("notes"), Argument.mapOf(String::class.java, String::class.java)).orEmpty(),
                activeLetter = rs.getString("active_letter"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                photoMediaId = rs.getString("photo_media_id"),
            ))
        }.firstOrNull()

    /**
     * Guarda presupuesto, tipo y notas (los nulos se conservan) y renueva el
     * vencimiento. Registra un evento por cada cosa que cambio.
     */
    @Transactional
    open fun actualizar(
        actual: PlannerSessionRow,
        budget: BigDecimal?,
        projectType: String?,
        notes: Map<String, String>?,
        vence: Instant,
    ) {
        jdbc.prepareStatement(
            """
            UPDATE planner_sessions SET budget = coalesce(?, budget), project_type = coalesce(?, project_type),
                   notes = coalesce(?::jsonb, notes), last_activity_at = now(), expires_at = ?
            WHERE id = ?
            """.trimIndent(),
        ) { st ->
            if (budget == null) st.setNull(1, Types.NUMERIC) else st.setBigDecimal(1, budget)
            st.setString(2, projectType)
            st.setString(3, notes?.let { json.writeValueAsString(it) })
            st.setTimestamp(4, Timestamp.from(vence))
            st.setString(5, actual.id)
            st.executeUpdate()
        }
        if (budget != null && (actual.budget == null || budget.compareTo(actual.budget) != 0)) {
            evento(actual.id, EV_PRESUPUESTO, mapOf("from" to actual.budget, "to" to budget))
        }
        if (projectType != null && projectType != actual.projectType) {
            evento(actual.id, EV_TIPO, mapOf("from" to actual.projectType, "to" to projectType))
        }
        if (notes != null && notes != actual.notes) evento(actual.id, EV_NOTA, mapOf("notes" to notes))
    }

    @Transactional(readOnly = true)
    open fun opciones(sessionId: String): List<PlannerOptionRow> =
        jdbc.prepareStatement(
            """
            SELECT letter, parent_letter, project_type, title, summary, spec::text AS spec, details::text AS details, pricing,
                   total, subtotal, tax, variants::text AS variants, budget_at_creation, created_at, render_status, render_media_id, render_job_id
            FROM planner_options WHERE session_id = ? ORDER BY id
            """.trimIndent(),
        ) { st ->
            st.setString(1, sessionId)
            val rs = st.executeQuery()
            val out = mutableListOf<PlannerOptionRow>()
            while (rs.next()) out += opcion(rs)
            out
        }

    /**
     * Guarda la opcion con la letra que le toca, la deja activa y renueva el
     * vencimiento. La sesion se bloquea mientras tanto: dos opciones pedidas a
     * la vez no se llevan la misma letra. Null si ya llego al maximo.
     */
    @Transactional
    open fun guardarOpcion(sessionId: String, maximo: Int, o: PlannerOptionRow, vence: Instant): String? {
        jdbc.prepareStatement("SELECT id FROM planner_sessions WHERE id = ? FOR UPDATE") { st ->
            st.setString(1, sessionId); st.executeQuery().close()
        }
        val cuantas = jdbc.prepareStatement("SELECT count(*) FROM planner_options WHERE session_id = ?") { st ->
            st.setString(1, sessionId)
            val rs = st.executeQuery(); rs.next(); rs.getInt(1)
        }
        if (cuantas >= maximo.coerceAtMost(MAX_LETRAS)) return null
        val letra = PlannerSpecs.letra(cuantas)
        jdbc.prepareStatement(
            """
            INSERT INTO planner_options (session_id, letter, parent_letter, project_type, title, summary, spec, details, pricing,
                                         total, subtotal, tax, variants, budget_at_creation, priced_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, now())
            """.trimIndent(),
        ) { st ->
            st.setString(1, sessionId); st.setString(2, letra); st.setString(3, o.parentLetter); st.setString(4, o.projectType)
            st.setString(5, o.title); st.setString(6, o.summary)
            st.setString(7, json.writeValueAsString(o.spec)); st.setString(8, json.writeValueAsString(o.details))
            st.setString(9, o.pricing)
            decimal(st, 10, o.total); decimal(st, 11, o.subtotal); decimal(st, 12, o.tax)
            st.setString(13, json.writeValueAsString(o.variants))
            decimal(st, 14, o.budgetAtCreation)
            st.executeUpdate()
        }
        jdbc.prepareStatement(
            "UPDATE planner_sessions SET active_letter = ?, project_type = ?, last_activity_at = now(), expires_at = ? WHERE id = ?",
        ) { st ->
            st.setString(1, letra); st.setString(2, o.projectType); st.setTimestamp(3, Timestamp.from(vence)); st.setString(4, sessionId)
            st.executeUpdate()
        }
        evento(sessionId, EV_OPCION, mapOf(
            "letter" to letra, "parent" to o.parentLetter, "projectType" to o.projectType, "pricing" to o.pricing,
            "total" to o.total, "budget" to o.budgetAtCreation, "spec" to o.spec,
        ))
        return letra
    }

    /** Deja activa una opcion de la sesion. False si esa letra no existe. */
    @Transactional
    open fun activar(sessionId: String, letra: String, vence: Instant): Boolean {
        val n = jdbc.prepareStatement(
            """
            UPDATE planner_sessions SET active_letter = ?, last_activity_at = now(), expires_at = ?
            WHERE id = ? AND EXISTS (SELECT 1 FROM planner_options o WHERE o.session_id = ? AND o.letter = ?)
            """.trimIndent(),
        ) { st ->
            st.setString(1, letra); st.setTimestamp(2, Timestamp.from(vence)); st.setString(3, sessionId)
            st.setString(4, sessionId); st.setString(5, letra)
            st.executeUpdate()
        }
        if (n > 0) evento(sessionId, EV_ACTIVA, mapOf("letter" to letra))
        return n > 0
    }

    /** La foto del espacio de la sesion (null la quita). */
    @Transactional
    open fun guardarFoto(sessionId: String, mediaId: String?, vence: Instant) {
        jdbc.prepareStatement("UPDATE planner_sessions SET photo_media_id = ?, last_activity_at = now(), expires_at = ? WHERE id = ?") { st ->
            st.setString(1, mediaId); st.setTimestamp(2, Timestamp.from(vence)); st.setString(3, sessionId); st.executeUpdate()
        }
        if (mediaId != null) evento(sessionId, EV_FOTO, mapOf("media" to mediaId))
    }

    /** Renders de la sesion que cuentan contra el tope: los pedidos y los listos (un fallido no cuenta). */
    @Transactional(readOnly = true)
    open fun rendersUsados(sessionId: String): Int =
        jdbc.prepareStatement("SELECT count(*) FROM planner_options WHERE session_id = ? AND render_status IN ('pending', 'ready')") { st ->
            st.setString(1, sessionId)
            val rs = st.executeQuery(); rs.next(); rs.getInt(1)
        }

    /**
     * Reserva el render de una opcion: solo si no tiene uno en camino o listo,
     * y si la sesion aun no llega al tope. La sesion se bloquea mientras tanto:
     * dos toques a la vez no pasan el tope. Devuelve el numero de render (1, 2…)
     * o, si no se puede, [RESERVA_NO_EXISTE], [RESERVA_YA] o [RESERVA_TOPE].
     */
    @Transactional
    open fun reservarRender(sessionId: String, letra: String, maximo: Int): Int {
        jdbc.prepareStatement("SELECT id FROM planner_sessions WHERE id = ? FOR UPDATE") { st ->
            st.setString(1, sessionId); st.executeQuery().close()
        }
        val estado = jdbc.prepareStatement("SELECT coalesce(render_status, '') FROM planner_options WHERE session_id = ? AND letter = ?") { st ->
            st.setString(1, sessionId); st.setString(2, letra)
            val rs = st.executeQuery()
            if (rs.next()) listOf(rs.getString(1)) else emptyList()
        }.firstOrNull() ?: return RESERVA_NO_EXISTE
        if (estado == "pending" || estado == "ready") return RESERVA_YA
        val usados = rendersUsados(sessionId)
        if (usados >= maximo) return RESERVA_TOPE
        jdbc.prepareStatement(
            "UPDATE planner_options SET render_status = 'pending', render_job_id = NULL, render_media_id = NULL WHERE session_id = ? AND letter = ?",
        ) { st -> st.setString(1, sessionId); st.setString(2, letra); st.executeUpdate() }
        evento(sessionId, EV_RENDER, mapOf("letter" to letra, "numero" to usados + 1, "de" to maximo))
        return usados + 1
    }

    /** El trabajo de la cola que hace el render reservado. */
    @Transactional
    open fun asignarTrabajo(sessionId: String, letra: String, jobId: Long) {
        jdbc.prepareStatement("UPDATE planner_options SET render_job_id = ? WHERE session_id = ? AND letter = ?") { st ->
            st.setLong(1, jobId); st.setString(2, sessionId); st.setString(3, letra); st.executeUpdate()
        }
    }

    /** La cola termino el render: queda listo con su imagen. */
    @Transactional
    open fun renderListo(jobId: Long, mediaId: String): Boolean =
        jdbc.prepareStatement("UPDATE planner_options SET render_status = 'ready', render_media_id = ? WHERE render_job_id = ?") { st ->
            st.setString(1, mediaId); st.setLong(2, jobId); st.executeUpdate()
        } > 0

    /** No se pudo (o no se pudo encolar): queda fallido y no cuenta contra el tope. */
    @Transactional
    open fun renderFallido(jobId: Long?, sessionId: String? = null, letra: String? = null) {
        if (jobId != null) {
            jdbc.prepareStatement("UPDATE planner_options SET render_status = 'failed' WHERE render_job_id = ?") { st ->
                st.setLong(1, jobId); st.executeUpdate()
            }
        } else if (sessionId != null && letra != null) {
            jdbc.prepareStatement("UPDATE planner_options SET render_status = 'failed' WHERE session_id = ? AND letter = ?") { st ->
                st.setString(1, sessionId); st.setString(2, letra); st.executeUpdate()
            }
        }
    }

    /** El visitante toco «Contactar a un asesor»: que opcion llevaba y contra que presupuesto (para medir). */
    @Transactional
    open fun contacto(sessionId: String, datos: Map<String, Any?>) = evento(sessionId, EV_CONTACTO, datos)

    /** Renueva el vencimiento: el diseño se guarda N dias desde la ultima actividad. */
    @Transactional
    open fun tocar(sessionId: String, vence: Instant) {
        jdbc.prepareStatement("UPDATE planner_sessions SET last_activity_at = now(), expires_at = ? WHERE id = ?") { st ->
            st.setTimestamp(1, Timestamp.from(vence)); st.setString(2, sessionId); st.executeUpdate()
        }
    }

    /**
     * La sesion que atiende una conversacion del agente: la conversacion del
     * planificador se abre con la sesion como `external_ref`.
     */
    @Transactional(readOnly = true)
    open fun sesionDeConversacion(conversationId: Long, channelId: Long): String? =
        jdbc.prepareStatement("SELECT external_ref FROM conversations WHERE id = ? AND channel_id = ?") { st ->
            st.setLong(1, conversationId); st.setLong(2, channelId)
            val rs = st.executeQuery()
            if (rs.next()) listOfNotNull(rs.getString(1)) else emptyList()
        }.firstOrNull()

    /** Borra las sesiones vencidas (sus opciones y eventos caen en cascada). Devuelve cuantas. */
    @Transactional
    open fun limpiar(): Int =
        jdbc.prepareStatement("DELETE FROM planner_sessions WHERE expires_at <= now()") { it.executeUpdate() }

    /** Los eventos de una sesion, en orden (para el asesor y las pruebas). */
    @Transactional(readOnly = true)
    open fun eventos(sessionId: String): List<String> =
        jdbc.prepareStatement("SELECT kind FROM planner_events WHERE session_id = ? ORDER BY id") { st ->
            st.setString(1, sessionId)
            val rs = st.executeQuery()
            val out = mutableListOf<String>()
            while (rs.next()) out += rs.getString(1)
            out
        }

    private fun evento(sessionId: String, tipo: String, datos: Map<String, Any?>) {
        jdbc.prepareStatement("INSERT INTO planner_events (session_id, kind, data) VALUES (?, ?, ?::jsonb)") { st ->
            st.setString(1, sessionId); st.setString(2, tipo); st.setString(3, json.writeValueAsString(datos.filterValues { it != null }))
            st.executeUpdate()
        }
    }

    private fun opcion(rs: ResultSet) = PlannerOptionRow(
        letter = rs.getString("letter"),
        parentLetter = rs.getString("parent_letter"),
        projectType = rs.getString("project_type"),
        title = rs.getString("title"),
        summary = rs.getString("summary"),
        spec = leer(rs.getString("spec"), Argument.of(OptionSpec::class.java)) ?: error("opción sin spec"),
        details = leer(rs.getString("details"), Argument.listOf(PlannerDetail::class.java)).orEmpty(),
        pricing = rs.getString("pricing"),
        total = rs.getBigDecimal("total"),
        subtotal = rs.getBigDecimal("subtotal"),
        tax = rs.getBigDecimal("tax"),
        variants = leer(rs.getString("variants"), Argument.listOf(PlannerVariant::class.java)).orEmpty(),
        budgetAtCreation = rs.getBigDecimal("budget_at_creation"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        renderStatus = rs.getString("render_status"),
        renderMediaId = rs.getString("render_media_id"),
        renderJobId = rs.getObject("render_job_id")?.let { rs.getLong("render_job_id") },
    )

    private fun <T : Any> leer(crudo: String?, tipo: Argument<T>): T? = crudo?.let { json.readValue(it, tipo) }

    private fun decimal(st: java.sql.PreparedStatement, i: Int, v: BigDecimal?) {
        if (v == null) st.setNull(i, Types.NUMERIC) else st.setBigDecimal(i, v)
    }

    companion object {
        const val PREFIJO = "ps_"
        val ID = Regex("^ps_[A-Za-z0-9]{32}$")
        const val MAX_LETRAS = 26

        const val EV_INICIO = "session_started"
        const val EV_PRESUPUESTO = "budget_changed"
        const val EV_TIPO = "type_changed"
        const val EV_NOTA = "note_changed"
        const val EV_OPCION = "option_created"
        const val EV_ACTIVA = "option_activated"
        const val EV_FOTO = "photo_uploaded"
        const val EV_RENDER = "render_requested"
        const val EV_CONTACTO = "contact_requested"

        /** Respuestas de [reservarRender] que no son un numero de render. */
        const val RESERVA_NO_EXISTE = -1
        const val RESERVA_YA = -2
        const val RESERVA_TOPE = -3

        private val azar = SecureRandom()
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun nuevoId(): String = PREFIJO + (1..32).map { ABC[azar.nextInt(ABC.length)] }.joinToString("")
    }
}
