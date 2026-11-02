package com.erp_maya.agent.cita

import com.erp_maya.agent.api.dto.Choice
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.conversation.service.ConversationService
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.SaludAppointment
import com.erp_maya.agent.erp.domain.SaludAppointmentWrite
import com.erp_maya.agent.erp.domain.SaludCandidate
import com.erp_maya.agent.erp.domain.SaludClientData
import com.erp_maya.agent.erp.domain.SaludPatientCreate
import com.erp_maya.agent.erp.domain.SaludPatientData
import com.erp_maya.agent.erp.domain.SaludPatientFromClient
import com.erp_maya.agent.erp.domain.SaludProfessional
import com.erp_maya.agent.erp.domain.SaludRescheduleWrite
import com.erp_maya.agent.erp.domain.SaludSlot
import com.erp_maya.agent.erp.domain.SaludStatusWrite
import com.erp_maya.agent.playbook.domain.PlantillaRecordatorio
import com.erp_maya.agent.playbook.service.PlaybookService
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.quote.DraftStep
import com.erp_maya.agent.tools.domain.ToolCall
import com.erp_maya.agent.tools.domain.ToolResult
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Citas de Stackline Salud desde el chat: las herramientas `citas.*` que usa
 * el modelo y los botones del recordatorio, que se resuelven sin el.
 *
 * Quien escribe se identifica por su telefono (en WhatsApp, el de la
 * conversacion). Una cita es «suya» si el telefono de la ficha del paciente
 * coincide: asi una mama puede manejar las citas de sus hijos, que suelen
 * tener su numero.
 *
 * El ERP pone las reglas duras: lo que agenda el asistente queda «solicitada»
 * (la clinica la confirma), solo dentro del horario de trabajo, y el
 * asistente solo puede confirmar o cancelar. Aqui se cuida lo de la
 * conversacion: que el horario siga libre y que la cita sea de quien escribe.
 */
@Singleton
open class CitaService(
    private val erp: ErpClient,
    private val conversaciones: ConversationService,
    private val playbooks: PlaybookService,
) {

    // ── Herramientas ─────────────────────────────────────────────────────

    open fun ejecutar(contexto: ExecutionContext, conversationId: Long, call: ToolCall, telefonoCanal: String?): ToolResult {
        // En WhatsApp manda el numero de la conversacion; en otro canal, el que dio el paciente.
        val telefono = telefonoCanal ?: call.stringArg("telefono")
        return try {
            ToolResult.Ok(call.name, invocar(contexto.tenantId, conversationId, call, telefono))
        } catch (e: ErpException) {
            log.warn("la herramienta {} fallo: {}", call.name, e.message)
            ToolResult.Failed(call.name, e.message ?: "El sistema de la clínica no respondió")
        }
    }

    private fun invocar(t: Long, c: Long, call: ToolCall, telefono: String?): Any? = when (call.name) {
        OPCIONES -> opciones(t, c)
        DISPONIBILIDAD -> disponibilidad(
            t, c,
            typeId = requerido(call.longArg("typeId"), "typeId (de citas.opciones)"),
            dia = CitaRules.leerDia(call.stringArg("fecha"))
                ?: throw ErpException("La fecha debe ir como AAAA-MM-DD (o hoy / mañana)"),
            professionalId = call.longArg("professionalId"),
            desde = call.stringArg("desde")?.let { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() },
        )
        AGENDAR -> agendar(t, c, call, requerido(telefono, "el teléfono del paciente"))
        MIS_CITAS -> misCitas(t, c, requerido(telefono, "el teléfono del paciente")).map { resumen(it) }
        REPROGRAMAR -> reprogramar(t, c, call, requerido(telefono, "el teléfono del paciente"))
        CANCELAR -> {
            val cita = suya(t, c, requerido(call.longArg("citaId"), "citaId"), requerido(telefono, "el teléfono del paciente"))
            resumen(cancelar(t, c, cita, call.stringArg("motivo")))
        }
        CONFIRMAR -> {
            val cita = suya(t, c, requerido(call.longArg("citaId"), "citaId"), requerido(telefono, "el teléfono del paciente"))
            resumen(confirmar(t, c, cita))
        }
        else -> throw ErpException("La herramienta '${call.name}' no tiene implementacion")
    }

    /** Tipos de cita y profesionales, con la fecha de hoy para que el modelo no la adivine. */
    private fun opciones(t: Long, c: Long): Map<String, Any?> {
        val hoy = CitaRules.hoy()
        return mapOf(
            "hoy" to "$hoy (${CitaRules.fechaLarga(hoy)})",
            "tipos" to erp.saludTypes(t, c).filter { it.active }.map {
                mapOf("typeId" to it.id, "nombre" to it.name, "minutos" to it.durationMinutes, "precio" to it.price)
            },
            "profesionales" to atienden(t, c).map {
                mapOf("professionalId" to it.userId, "nombre" to it.name, "especialidad" to it.specialty)
            },
        )
    }

    private fun atienden(t: Long, c: Long): List<SaludProfessional> =
        erp.saludProfessionals(t, c).filter { it.configured && it.active }

    /**
     * Horarios libres de un dia. Sin profesional, los de todos los que
     * atienden. Si ese dia no hay nada, busca en los siguientes para ofrecer
     * la fecha libre mas cercana.
     */
    private fun disponibilidad(t: Long, c: Long, typeId: Long, dia: LocalDate, professionalId: Long?, desde: LocalTime?): Map<String, Any?> {
        if (dia.isBefore(CitaRules.hoy())) throw ErpException("Esa fecha ya pasó: hoy es ${CitaRules.hoy()}")
        val profesionales = atienden(t, c).filter { professionalId == null || it.userId == professionalId }
        if (profesionales.isEmpty()) throw ErpException("No encontré a ese profesional en la agenda de la clínica")

        for (salto in 0 until DIAS_A_BUSCAR) {
            val fecha = dia.plusDays(salto.toLong())
            val porProfesional = profesionales.take(MAX_PROFESIONALES).mapNotNull { p ->
                val libres = erp.saludAvailability(t, c, p.userId, fecha.toString(), typeId)?.slots.orEmpty()
                val ofrecer = ofrecer(libres, if (salto == 0) desde else null)
                ofrecer.takeIf { it.isNotEmpty() }?.let {
                    mapOf(
                        "professionalId" to p.userId,
                        "profesional" to p.name,
                        "horarios" to it.map { s -> mapOf("hora" to CitaRules.hora(s.startsAt), "inicio" to s.startsAt) },
                    )
                }
            }
            if (porProfesional.isNotEmpty()) {
                return mapOf(
                    "fecha" to fecha.toString(),
                    "dia" to CitaRules.fechaLarga(fecha),
                    "pedida" to (salto == 0),
                    "nota" to if (salto == 0) null else "No había horarios el ${CitaRules.fechaLarga(dia)}; este es el día libre más cercano.",
                    "disponibles" to porProfesional,
                )
            }
        }
        return mapOf("fecha" to dia.toString(), "disponibles" to emptyList<Any>(),
            "nota" to "Sin horarios libres en los próximos $DIAS_A_BUSCAR días. Ofrece que la clínica le llame.")
    }

    /** Pocos horarios y redondos (en punto y y media): una lista de 30 opciones no ayuda a nadie. */
    private fun ofrecer(libres: List<SaludSlot>, desde: LocalTime?): List<SaludSlot> {
        val posibles = libres.filter { s -> desde == null || LocalTime.parse(CitaRules.hora(s.startsAt)) >= desde }
        val redondos = posibles.filter { CitaRules.hora(it.startsAt).endsWith(":00") || CitaRules.hora(it.startsAt).endsWith(":30") }
        return redondos.ifEmpty { posibles }.take(MAX_HORARIOS)
    }

    private fun agendar(t: Long, c: Long, call: ToolCall, telefono: String): Map<String, Any?> {
        val typeId = requerido(call.longArg("typeId"), "typeId")
        val professionalId = requerido(call.longArg("professionalId"), "professionalId")
        val inicio = CitaRules.leerInicio(call.stringArg("inicio"))
            ?: throw ErpException("Falta el inicio: usa el valor 'inicio' de citas.disponibilidad")
        val slot = libre(t, c, professionalId, typeId, inicio)
        val paciente = paciente(t, c, telefono, call.stringArg("nombre"))
        val cita = erp.saludCreateAppointment(
            t, c,
            SaludAppointmentWrite(
                patientId = paciente.first,
                professionalId = professionalId,
                typeId = typeId,
                resourceId = slot.resourceId,
                startsAt = slot.startsAt,
                reason = call.stringArg("motivo")?.take(300),
                followUpId = call.longArg("followUpId"),
            ),
        )
        log.info("cita {} agendada por el asistente para el paciente {}", cita.id, cita.patientId)
        return resumen(cita) + mapOf(
            "nota" to "Queda como solicitada: la clínica la confirma. Díselo al paciente y que le llegará un recordatorio.",
        )
    }

    private fun reprogramar(t: Long, c: Long, call: ToolCall, telefono: String): Map<String, Any?> {
        val cita = suya(t, c, requerido(call.longArg("citaId"), "citaId"), telefono)
        val inicio = CitaRules.leerInicio(call.stringArg("inicio"))
            ?: throw ErpException("Falta el nuevo inicio: usa el valor 'inicio' de citas.disponibilidad")
        val professionalId = call.longArg("professionalId") ?: cita.professionalId
        val slot = libre(t, c, professionalId, cita.typeId, inicio)
        val movida = erp.saludReschedule(
            t, c, cita.id,
            SaludRescheduleWrite(startsAt = slot.startsAt, professionalId = professionalId, resourceId = slot.resourceId),
        )
        return resumen(movida) + mapOf("antes" to "${CitaRules.fechaLarga(cita.startsAt)} ${CitaRules.hora(cita.startsAt)}")
    }

    private fun cancelar(t: Long, c: Long, cita: SaludAppointment, motivo: String?): SaludAppointment =
        erp.saludChangeStatus(
            t, c, cita.id,
            SaludStatusWrite("cancelada", ("Cancelada por el paciente por WhatsApp" + (motivo?.let { ": $it" } ?: "")).take(300)),
        )

    private fun confirmar(t: Long, c: Long, cita: SaludAppointment): SaludAppointment =
        if (cita.status == "confirmada") cita
        else erp.saludChangeStatus(t, c, cita.id, SaludStatusWrite("confirmada", null))

    /** El horario sigue libre: se toma de la disponibilidad (con su consultorio), no del modelo. */
    private fun libre(t: Long, c: Long, professionalId: Long, typeId: Long, inicio: Instant): SaludSlot {
        if (inicio.isBefore(Instant.now())) throw ErpException("Ese horario ya pasó")
        val dia = inicio.atZone(CitaRules.GUATEMALA).toLocalDate().toString()
        val libres = erp.saludAvailability(t, c, professionalId, dia, typeId)?.slots.orEmpty()
        return libres.firstOrNull { CitaRules.mismoInicio(it.startsAt, inicio) }
            ?: throw ErpException(
                "Ese horario ya no está libre. Libres ese día: " +
                    ofrecer(libres, null).joinToString { CitaRules.hora(it.startsAt) }.ifEmpty { "ninguno" },
            )
    }

    /** Citas vigentes de quien escribe (y de quienes tienen su numero), de hoy a dos meses. */
    private fun misCitas(t: Long, c: Long, telefono: String): List<SaludAppointment> {
        val ahora = Instant.now()
        return erp.saludAppointments(t, c, null, ahora.toString(), ahora.plus(VENTANA_CITAS).toString())
            .filter { it.status in CitaRules.VIGENTES && mismoTelefono(it.patientPhone, telefono) }
            .sortedBy { it.startsAt }
    }

    /** La cita existe y es de quien escribe; si no, no se le dice nada mas de ella. */
    private fun suya(t: Long, c: Long, citaId: Long, telefono: String): SaludAppointment {
        val cita = erp.saludAppointment(t, c, citaId) ?: throw ErpException("No existe la cita $citaId")
        if (!mismoTelefono(cita.patientPhone, telefono)) {
            throw ErpException("La cita $citaId no es de quien escribe: usa citas.mis_citas para ver las suyas")
        }
        if (cita.status !in CitaRules.VIGENTES) {
            throw ErpException("La cita $citaId ya está ${CitaRules.estadoLegible(cita.status)}")
        }
        return cita
    }

    /**
     * El paciente que se agenda: (id, nombre). Con nombre, se busca a esa
     * persona entre las fichas con este telefono (puede ser un hijo); sin
     * nombre, al dueño del numero. Si no existe se registra.
     */
    private fun paciente(t: Long, c: Long, telefono: String, nombre: String?): Pair<Long, String> {
        val buscado = nombre?.trim()?.takeIf { it.length >= 3 }
        val conNumero = buscado?.let { n ->
            erp.saludCandidates(t, c, n).filter { mismoTelefono(it.phone, telefono) && pareceElMismo(it.name, n) }
        }.orEmpty()
        val delNumero = erp.saludCandidates(t, c, telefono).filter { buscado == null || pareceElMismo(it.name, buscado) }
        val elegido: SaludCandidate? = (conNumero + delNumero).let { lista ->
            lista.firstOrNull { it.patient } ?: lista.firstOrNull()
        }
        if (elegido != null) {
            if (!elegido.patient) {
                erp.saludPatientFromClient(t, c, elegido.clientId, SaludPatientFromClient(SaludPatientData()))
                log.info("cliente {} convertido en paciente desde el chat", elegido.clientId)
            }
            return elegido.clientId to elegido.name
        }
        val n = buscado ?: throw ErpException(
            "Es la primera vez que agenda con este número: pregúntale su nombre completo (o el del paciente) y vuelve a llamar con 'nombre'",
        )
        val alta = erp.saludCreatePatient(
            t, c,
            SaludPatientCreate(SaludClientData(name = n.take(150), phone = telefonoLegible(telefono)), SaludPatientData()),
        )
        log.info("paciente {} registrado desde el chat", alta.client.id)
        return alta.client.id to alta.client.name
    }

    private fun resumen(a: SaludAppointment): Map<String, Any?> = mapOf(
        "citaId" to a.id,
        "paciente" to a.patientName,
        "fecha" to CitaRules.fechaLarga(a.startsAt),
        "hora" to CitaRules.hora(a.startsAt),
        "tipo" to a.typeName,
        "profesional" to a.professionalName,
        "consultorio" to a.resourceName,
        "estado" to CitaRules.estadoLegible(a.status),
    )

    // ── Botones del recordatorio ─────────────────────────────────────────

    /**
     * Un boton `cita:accion:id` del recordatorio. Se resuelve sin el modelo:
     * confirmar y cancelar son decisiones del paciente, no algo que
     * interpretar. «Cambiar fecha» deja la conversacion lista para que el
     * modelo ofrezca horarios.
     */
    open fun boton(contexto: ExecutionContext, conversationId: Long, actual: QuoteDraft?, boton: String): DraftStep {
        val (accion, citaId) = CitaRules.leerBoton(boton) ?: return DraftStep(actual, "No reconozco esa opción.")
        val m = playbooks.para(contexto).mensajes.recordatorioCita
        val t = contexto.tenantId
        val telefono = conversaciones.destino(conversationId)?.externalRef
        val cita = erp.saludAppointment(t, conversationId, citaId)
        if (cita == null || telefono == null || !mismoTelefono(cita.patientPhone, telefono)) {
            return DraftStep(actual, m.noEsTuya)
        }
        val v = CitaRules.valores(cita.patientName, cita.startsAt, cita.typeName, cita.professionalName, null)
        if (cita.status !in CitaRules.VIGENTES) {
            return DraftStep(actual, CitaRules.llenar(m.noVigente, v + ("estado" to CitaRules.estadoLegible(cita.status))))
        }
        return when (accion) {
            CitaRules.CONFIRMAR -> {
                confirmar(t, conversationId, cita)
                DraftStep(actual, CitaRules.llenar(m.confirmada, v))
            }
            CitaRules.CAMBIAR -> DraftStep(actual, CitaRules.llenar(m.cambiar, v))
            CitaRules.CANCELAR -> DraftStep(
                actual,
                CitaRules.llenar(m.confirmarCancelar, v),
                opciones = confirmarCancelacion(citaId, m),
            )
            CitaRules.SI_CANCELAR -> {
                cancelar(t, conversationId, cita, null)
                DraftStep(actual, CitaRules.llenar(m.cancelada, v))
            }
            else -> DraftStep(actual, CitaRules.llenar(m.mantener, v))
        }
    }

    /** Texto con el que el boton queda en el historial: lleva el id para que el modelo pueda seguir. */
    fun describir(boton: String): String {
        val (accion, id) = CitaRules.leerBoton(boton) ?: return "[Botón] $boton"
        return when (accion) {
            CitaRules.CONFIRMAR -> "[Botón] Confirmar la cita #$id"
            CitaRules.CAMBIAR -> "[Botón] Cambiar la fecha de la cita #$id (usa citas.disponibilidad y citas.reprogramar con citaId $id)"
            CitaRules.CANCELAR -> "[Botón] Cancelar la cita #$id"
            CitaRules.SI_CANCELAR -> "[Botón] Sí, cancelar la cita #$id"
            else -> "[Botón] Mantener la cita #$id"
        }
    }

    companion object {
        const val PREFIJO = "citas."
        const val OPCIONES = "citas.opciones"
        const val DISPONIBILIDAD = "citas.disponibilidad"
        const val AGENDAR = "citas.agendar"
        const val MIS_CITAS = "citas.mis_citas"
        const val REPROGRAMAR = "citas.reprogramar"
        const val CANCELAR = "citas.cancelar"
        const val CONFIRMAR = "citas.confirmar"

        /** Las que cambian algo en la agenda (para el control de «dijo que lo hizo»). */
        val MUTANTES = setOf(AGENDAR, REPROGRAMAR, CANCELAR, CONFIRMAR)

        private const val DIAS_A_BUSCAR = 7
        private const val MAX_PROFESIONALES = 5
        private const val MAX_HORARIOS = 12
        private val VENTANA_CITAS: Duration = Duration.ofDays(60)
        private val log = LoggerFactory.getLogger(CitaService::class.java)

        /** Los tres botones del recordatorio. */
        fun botonesRecordatorio(citaId: Long, m: PlantillaRecordatorio): List<Choice> = listOf(
            Choice(CitaRules.boton(CitaRules.CONFIRMAR, citaId), CitaRules.titulo(m.botonConfirmar)),
            Choice(CitaRules.boton(CitaRules.CAMBIAR, citaId), CitaRules.titulo(m.botonCambiar)),
            Choice(CitaRules.boton(CitaRules.CANCELAR, citaId), CitaRules.titulo(m.botonCancelar)),
        )

        fun confirmarCancelacion(citaId: Long, m: PlantillaRecordatorio): List<Choice> = listOf(
            Choice(CitaRules.boton(CitaRules.SI_CANCELAR, citaId), CitaRules.titulo(m.botonSiCancelar)),
            Choice(CitaRules.boton(CitaRules.MANTENER, citaId), CitaRules.titulo(m.botonMantener)),
        )

        /** Mismo numero si coinciden los ultimos 8 digitos (con o sin 502, guiones o espacios). */
        fun mismoTelefono(a: String?, b: String?): Boolean {
            val x = a?.filter { it.isDigit() }.orEmpty()
            val y = b?.filter { it.isDigit() }.orEmpty()
            return x.length >= 8 && y.length >= 8 && x.takeLast(8) == y.takeLast(8)
        }

        /** ¿La ficha es de esa persona? Basta que el primer nombre que dio aparezca en la ficha. */
        fun pareceElMismo(ficha: String, nombre: String): Boolean {
            val palabras = sinTildes(ficha).split(' ').filter { it.isNotBlank() }.toSet()
            val primero = sinTildes(nombre).split(' ').firstOrNull { it.isNotBlank() } ?: return false
            return primero in palabras
        }

        /** «50255551234» -> «5555-1234»; otros paises, con su prefijo. */
        fun telefonoLegible(telefono: String): String {
            val d = telefono.filter { it.isDigit() }
            return when {
                d.length == 11 && d.startsWith("502") -> "${d.substring(3, 7)}-${d.substring(7)}"
                d.length == 8 -> "${d.substring(0, 4)}-${d.substring(4)}"
                else -> "+$d"
            }
        }

        private fun sinTildes(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        private fun <T> requerido(v: T?, que: String): T = v ?: throw ErpException("Falta $que")
    }
}
