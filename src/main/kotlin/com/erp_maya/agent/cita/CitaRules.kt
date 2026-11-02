package com.erp_maya.agent.cita

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reglas puras de las citas por WhatsApp: horas de Guatemala, botones,
 * telefonos y textos. Sin infraestructura, para probarlas sin nada alrededor.
 */
object CitaRules {

    val GUATEMALA: ZoneId = ZoneId.of("America/Guatemala")

    /** Citas que siguen en pie: las unicas que el paciente puede confirmar, mover o cancelar. */
    val VIGENTES = setOf("solicitada", "confirmada")

    // ── Botones ──────────────────────────────────────────────────────────

    const val PREFIJO = "cita:"
    const val CONFIRMAR = "confirmar"
    const val CAMBIAR = "cambiar"
    const val CANCELAR = "cancelar"
    const val SI_CANCELAR = "cancelar_si"
    const val MANTENER = "mantener"
    private val ACCIONES = setOf(CONFIRMAR, CAMBIAR, CANCELAR, SI_CANCELAR, MANTENER)
    private val FORMATO = Regex("^cita:([a-z_]+):(\\d+)$")

    /** `cita:accion:id`, el id que viaja en los botones del recordatorio. */
    fun boton(accion: String, appointmentId: Long) = "$PREFIJO$accion:$appointmentId"

    fun esBoton(texto: String?): Boolean =
        texto != null && FORMATO.matchEntire(texto.trim())?.groupValues?.get(1) in ACCIONES

    /** (accion, id) de un boton; null si no es uno de los nuestros. */
    fun leerBoton(texto: String?): Pair<String, Long>? {
        val m = texto?.trim()?.let { FORMATO.matchEntire(it) } ?: return null
        val accion = m.groupValues[1].takeIf { it in ACCIONES } ?: return null
        return accion to m.groupValues[2].toLong()
    }

    /** WhatsApp corta en 20 caracteres el titulo de un boton. */
    fun titulo(texto: String) = texto.trim().take(20)

    // ── Telefonos ────────────────────────────────────────────────────────

    /**
     * El `wa_id` de WhatsApp para un telefono de la ficha: solo digitos y, si
     * es un numero local de Guatemala (8 digitos), con el 502 delante.
     */
    fun waId(telefono: String?): String? {
        val digitos = telefono?.filter { it.isDigit() }?.trimStart('0').orEmpty()
        return when {
            digitos.length == 8 -> "502$digitos"
            digitos.length in 10..15 -> digitos
            else -> null
        }
    }

    // ── Fechas ───────────────────────────────────────────────────────────

    private val DIAS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val HORA = DateTimeFormatter.ofPattern("HH:mm")

    /** Un instante ISO del ERP («2026-11-03T15:30:00Z» o con desfase). */
    fun instante(iso: String): Instant =
        runCatching { Instant.parse(iso) }.getOrElse { OffsetDateTime.parse(iso).toInstant() }

    fun hoy(ahora: Instant = Instant.now()): LocalDate = ahora.atZone(GUATEMALA).toLocalDate()

    /** «martes 3 de noviembre», en hora de Guatemala. */
    fun fechaLarga(iso: String): String = fechaLarga(instante(iso).atZone(GUATEMALA).toLocalDate())

    fun fechaLarga(d: LocalDate): String =
        "${DIAS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} de ${MESES[d.monthValue - 1]}"

    /** «09:30», en hora de Guatemala. */
    fun hora(iso: String): String = instante(iso).atZone(GUATEMALA).format(HORA)

    /** El dia (Guatemala) en que cae un instante, como lo pide la disponibilidad. */
    fun dia(iso: String): String = instante(iso).atZone(GUATEMALA).toLocalDate().toString()

    /**
     * El dia que pide el modelo: «2026-11-03», «hoy», «mañana» o «pasado
     * mañana». Null si no se entiende (se le pide que mande la fecha).
     */
    fun leerDia(texto: String?, ahora: Instant = Instant.now()): LocalDate? {
        val t = texto?.trim()?.lowercase()?.replace("ñ", "n") ?: return null
        val base = hoy(ahora)
        return when (t) {
            "hoy" -> base
            "manana" -> base.plusDays(1)
            "pasado manana" -> base.plusDays(2)
            else -> runCatching { LocalDate.parse(t.take(10)) }.getOrNull()
        }
    }

    /**
     * El inicio que manda el modelo: un instante ISO con zona (el de
     * citas.disponibilidad) o una hora local sin zona («2026-11-03T09:30»),
     * que se toma en hora de Guatemala.
     */
    fun leerInicio(texto: String?): Instant? {
        val t = texto?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { instante(t) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.take(16)).atZone(GUATEMALA).toInstant() }.getOrNull()
    }

    /** ¿El horario libre empieza en ese instante? (los del ERP vienen en UTC, el modelo puede mandar -06:00). */
    fun mismoInicio(slotIso: String, inicio: Instant): Boolean =
        runCatching { instante(slotIso) == inicio }.getOrDefault(false)

    // ── Textos ───────────────────────────────────────────────────────────

    /** Reemplaza {clave} por su valor; las claves sin valor quedan vacias. */
    fun llenar(molde: String, valores: Map<String, String?>): String =
        valores.entries.fold(molde) { acc, (k, v) -> acc.replace("{$k}", v.orEmpty()) }
            .replace(Regex(" {2,}"), " ")
            .replace(" ,", ",")
            .replace(" .", ".")
            .trim()

    /** El primer nombre, para saludar sin sonar a formulario. */
    fun primerNombre(nombre: String?): String =
        nombre?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }.orEmpty()

    /** Lo que se le cuenta al paciente de una cita, en sus palabras. */
    fun valores(
        paciente: String?, fechaIso: String, tipo: String?, profesional: String?, clinica: String?,
    ): Map<String, String?> = mapOf(
        "paciente" to primerNombre(paciente),
        "fecha" to fechaLarga(fechaIso),
        "hora" to hora(fechaIso),
        "tipo" to tipo,
        "profesional" to profesional,
        "clinica" to clinica,
    )

    /** Estado de una cita como lo diria la recepcionista. */
    fun estadoLegible(status: String): String = when (status) {
        "solicitada" -> "pendiente de confirmar"
        "confirmada" -> "confirmada"
        "en_sala" -> "en sala de espera"
        "en_atencion" -> "en atención"
        "completada" -> "ya fue atendida"
        "cancelada" -> "cancelada"
        "no_asistio" -> "marcada como no asistida"
        else -> status
    }
}
