package com.erp_maya.agent.admin

import com.erp_maya.agent.channel.web.WebChatService
import com.erp_maya.agent.channel.whatsapp.client.WaNumero
import com.erp_maya.agent.channel.whatsapp.client.WaNumeroError
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppCloudClient
import com.erp_maya.agent.channel.whatsapp.client.WhatsAppNumeroException
import com.erp_maya.agent.context.domain.Capabilities
import com.erp_maya.agent.playbook.service.PlaybookService
import io.micronaut.context.annotation.Value
import io.micronaut.serde.ObjectMapper
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * La IA de una empresa en la consola: su politica (encendido, tope, tono), sus
 * agentes (instancias de un tipo, con lo propio y lo heredado), sus canales,
 * instrucciones y playbooks. Solo operadores de plataforma; el ERP no llega aqui.
 *
 * Regla de herramientas: el tipo es el techo. Un agente de empresa solo puede
 * quedarse con un subconjunto; modo, tope y auto-aprobacion siguen siendo los
 * del tipo.
 */
@Singleton
open class TenantAdminService(
    private val repo: TenantAdminRepository,
    private val admin: AdminRepository,
    private val adminService: AdminService,
    private val playbooks: PlaybookService,
    private val json: ObjectMapper,
    private val whatsapp: WhatsAppCloudClient,
    @param:Value("\${web.public-url:}") private val publicUrl: String,
) {

    /** Donde se sirve el widget (lo pega la empresa en su pagina). Nulo si no esta configurado. */
    private val scriptUrl: String? = publicUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/v1/web/widget.js" }

    @Transactional(readOnly = true)
    open fun view(tenantId: Long): TenantAiView {
        val p = repo.policy(tenantId)
        val canales = repo.channels(tenantId)
        val herramientasTipo = admin.tools()
        val agentes = repo.agents(tenantId).map { a -> vista(a, herramientasTipo[a.agentId].orEmpty(), canales) }
        val vistas = canales.map { ChannelView(it.id, it.kind, it.accountRef, it.tenantAgentId, it.name) }
        val conErp = if (canales.none { it.accountRef == TenantAdminRepository.erpRef(tenantId) }) {
            // El del ERP siempre se ofrece; se crea al asignarlo.
            vistas + ChannelView(null, "erp", TenantAdminRepository.erpRef(tenantId), null)
        } else {
            vistas
        }
        return TenantAiView(
            tenantId = tenantId,
            // Sin fila el asistente esta apagado (asi lo lee el agente).
            policy = PolicyView(p?.enabled ?: false, p?.quoteLimit, p?.escalationUser, p?.tone, p?.locale ?: "es-GT"),
            channels = conErp,
            agents = agentes,
            webChannels = repo.webChannels(tenantId).map {
                WebChannelView(it.id, it.name, it.publicKey, it.origins, it.title, it.greeting, it.color, it.active,
                    it.tenantAgentId, it.createdAt.toString())
            },
            widgetScriptUrl = scriptUrl,
        )
    }

    // ── numeros de WhatsApp (de la cuenta de Stackline en Meta) ─────────────

    /** Consulta el numero en Meta y dice si esta libre, ya es de la empresa o lo atiende otra. */
    @Transactional(readOnly = true)
    open fun lookupWhatsApp(tenantId: Long, phoneNumberId: String?): WhatsAppNumberView {
        val id = idNumero(phoneNumberId)
        val n = numeroEnMeta(id)
        return vistaNumero(n, estadoNumero(tenantId, TenantAdminRepository.waRef(id)))
    }

    /** Conecta {phoneNumberId, tenantAgentId?}: crea (o recupera) el canal `wa:{id}` y lo asigna. */
    @Transactional
    open fun connectWhatsApp(tenantId: Long, body: Map<String, Any?>): TenantAiView {
        val id = idNumero(body["phoneNumberId"]?.toString())
        val f = linkedMapOf<String, String>()
        val agente = agenteParaCanal(tenantId, body, f)
        if (f.isNotEmpty()) throw AdminException.validation(f)
        val n = numeroEnMeta(id)
        val ref = TenantAdminRepository.waRef(id)
        if (estadoNumero(tenantId, ref) == NUMERO_OCUPADO) {
            throw AdminException.conflict("WA_IN_USE", "Ese número ya lo atiende otra empresa. Desconéctalo allá primero.")
        }
        val etiqueta = listOfNotNull(n.displayPhoneNumber, n.verifiedName).joinToString(" · ").ifBlank { null }
        val existente = repo.channelByRef(ref)
        val canal = if (existente == null) {
            repo.insertWhatsAppChannel(tenantId, ref, etiqueta, json.writeValueAsString(CAPACIDADES_WHATSAPP))
        } else {
            repo.claimChannel(existente.id, tenantId, etiqueta)
            existente.id
        }
        if (agente != null) {
            repo.endAssignments(canal, tenantId)
            repo.assign(canal, tenantId, agente.agentId, agente.id)
        }
        adminService.invalidar()
        log.info("whatsapp conectado empresa={} canal={} numero={} agente={}", tenantId, canal, n.displayPhoneNumber, agente?.id)
        return view(tenantId)
    }

    /** Desconecta el numero: deja de atenderse y desaparece de la empresa (se puede volver a conectar). */
    @Transactional
    open fun disconnectWhatsApp(tenantId: Long, channelId: Long): TenantAiView {
        val canal = repo.channels(tenantId).firstOrNull { it.id == channelId && it.kind == "whatsapp" }
            ?: throw AdminException.notFound("Ese número no está conectado a esta empresa")
        repo.endAssignments(canal.id, tenantId)
        repo.setChannelActive(canal.id, false)
        adminService.invalidar()
        log.info("whatsapp desconectado empresa={} canal={}", tenantId, channelId)
        return view(tenantId)
    }

    private fun idNumero(texto: String?): String {
        val id = texto?.trim().orEmpty()
        if (!Regex("^\\d{6,25}$").matches(id)) {
            throw AdminException.validation(mapOf("phoneNumberId" to "El ID del número son solo dígitos (Meta › WhatsApp › Configuración de la API)"))
        }
        return id
    }

    private fun numeroEnMeta(id: String): WaNumero = try {
        whatsapp.numero(id)
    } catch (e: WhatsAppNumeroException) {
        throw when (e.tipo) {
            WaNumeroError.SIN_TOKEN -> AdminException(503, "WA_NOT_CONFIGURED", e.message ?: "Falta el token de WhatsApp")
            WaNumeroError.RECHAZADO -> AdminException.validation(
                mapOf("phoneNumberId" to "Meta no reconoce ese número en la cuenta de Stackline: ${e.message}"),
            )
            else -> AdminException(502, "WA_UNAVAILABLE", e.message ?: "Meta no respondió")
        }
    }

    private fun estadoNumero(tenantId: Long, ref: String): String {
        val c = repo.channelByRef(ref) ?: return NUMERO_LIBRE
        if (c.active && c.servedBy.any { it != tenantId }) return NUMERO_OCUPADO
        if (c.active && (c.tenantId == tenantId || c.servedBy.contains(tenantId))) return NUMERO_CONECTADO
        return NUMERO_LIBRE
    }

    private fun vistaNumero(n: WaNumero, estado: String) =
        WhatsAppNumberView(n.id, n.displayPhoneNumber, n.verifiedName, n.qualityRating, n.codeVerificationStatus, estado)

    // ── widgets web (canal `web`) ───────────────────────────────────────────

    /** Crea un widget: {name, allowedOrigins, title?, greeting?, color?, tenantAgentId?}. */
    @Transactional
    open fun createWebChannel(tenantId: Long, body: Map<String, Any?>): TenantAiView {
        val (draft, f) = WebChannelRules.validar(body, null)
        val agente = agenteParaCanal(tenantId, body, f)
        if (draft == null || f.isNotEmpty()) throw AdminException.validation(f)
        val llave = WebChannelRules.nuevaLlave()
        val id = repo.insertWebChannel(tenantId, llave, json.writeValueAsString(WebChatService.CAPACIDADES), draft, json.writeValueAsString(draft.origins))
        agente?.let { repo.assign(id, tenantId, it.agentId, it.id) }
        adminService.invalidar()
        log.info("widget web creado empresa={} canal={} agente={}", tenantId, id, agente?.id)
        return view(tenantId)
    }

    /** Edita un widget; ademas {active} lo apaga o enciende y {tenantAgentId} cambia quien lo atiende (null = nadie). */
    @Transactional
    open fun updateWebChannel(tenantId: Long, channelId: Long, body: Map<String, Any?>): TenantAiView {
        val w = repo.webChannels(tenantId).firstOrNull { it.id == channelId }
            ?: throw AdminException.notFound("El widget no existe en esta empresa")
        val (draft, errores) = WebChannelRules.validar(body, WebChannelRules.Draft(w.name, w.origins, w.title, w.greeting, w.color))
        val activo = if (body.containsKey("active")) Parse.bool(body["active"]).also { if (it == null) errores["active"] = "Valor inválido" } else null
        val agente = agenteParaCanal(tenantId, body, errores)
        if (draft == null || errores.isNotEmpty()) throw AdminException.validation(errores)
        repo.updateWebChannel(channelId, draft, json.writeValueAsString(draft.origins))
        activo?.let { repo.setChannelActive(channelId, it) }
        if (body.containsKey("tenantAgentId") && agente?.id != w.tenantAgentId) {
            repo.endAssignments(channelId, tenantId)
            agente?.let { repo.assign(channelId, tenantId, it.agentId, it.id) }
        }
        adminService.invalidar()
        log.info("widget web actualizado empresa={} canal={} campos={}", tenantId, channelId, body.keys)
        return view(tenantId)
    }

    /** El agente de la empresa que pide `tenantAgentId` (null si no viene o viene nulo). */
    private fun agenteParaCanal(tenantId: Long, body: Map<String, Any?>, f: MutableMap<String, String>): TenantAgentRow? {
        val id = body["tenantAgentId"]?.let { Parse.long(it) ?: run { f["tenantAgentId"] = "Agente inválido"; return null } } ?: return null
        val a = repo.agent(tenantId, id)
        if (a == null || a.archived) { f["tenantAgentId"] = "El agente no existe en esta empresa"; return null }
        return a
    }

    @Transactional
    open fun savePolicy(tenantId: Long, body: Map<String, Any?>): TenantAiView {
        val f = linkedMapOf<String, String>()
        val enabled = Parse.bool(body["enabled"]) ?: run { f["enabled"] = "Valor inválido"; false }
        val tope = body["quoteLimit"]?.let { Parse.decimal(it) ?: run { f["quoteLimit"] = "Monto mayor a 0"; null } }
        if (tope != null && tope.signum() <= 0) f["quoteLimit"] = "Monto mayor a 0"
        val escala = Parse.text(body["escalationUser"])
        if (escala != null && escala.length > 120) f["escalationUser"] = "Máximo 120 caracteres"
        val tono = Parse.text(body["tone"])
        if (tono != null && tono.length > 40) f["tone"] = "Máximo 40 caracteres"
        val locale = Parse.text(body["locale"]) ?: "es-GT"
        if (locale !in LOCALES) f["locale"] = "Idioma no soportado"
        if (f.isNotEmpty()) throw AdminException.validation(f)
        repo.upsertPolicy(tenantId, PolicyRow(enabled, tope, escala, tono, locale))
        adminService.invalidar()
        log.info("politica de IA empresa={} encendido={}", tenantId, enabled)
        return view(tenantId)
    }

    @Transactional
    open fun createAgent(tenantId: Long, body: Map<String, Any?>): TenantAiView {
        val tipo = Parse.long(body["agentId"])?.let { admin.agent(it) }
        val f = linkedMapOf<String, String>()
        if (tipo == null || tipo.archived || !tipo.active) f["agentId"] = "Elige un tipo de agente activo"
        else if (tipo.kind != "conversational") f["agentId"] = "Los agentes internos no se asignan a empresas"
        val nombre = Parse.text(body["name"]) ?: tipo?.name
        f.putAll(validarNombre(tenantId, nombre, null))
        if (f.isNotEmpty()) throw AdminException.validation(f)
        val id = repo.insertAgent(tenantId, tipo!!.id, nombre!!)
        // El resto (modelo, herramientas, canales) es una edicion normal.
        val cambios = body - setOf("agentId", "name")
        if (cambios.isNotEmpty()) aplicar(tenantId, id, cambios)
        adminService.invalidar()
        log.info("agente de empresa creado empresa={} id={} tipo={}", tenantId, id, tipo.code)
        return view(tenantId)
    }

    @Transactional
    open fun updateAgent(tenantId: Long, id: Long, body: Map<String, Any?>): TenantAiView {
        aplicar(tenantId, id, body)
        adminService.invalidar()
        log.info("agente de empresa actualizado empresa={} id={} campos={}", tenantId, id, body.keys)
        return view(tenantId)
    }

    @Transactional
    open fun archiveAgent(tenantId: Long, id: Long): TenantAiView {
        val a = repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        if (!a.archived) {
            repo.channels(tenantId).filter { it.tenantAgentId == id }.forEach { repo.endAssignments(it.id, tenantId) }
            repo.archive(id)
            adminService.invalidar()
            log.info("agente de empresa archivado empresa={} id={}", tenantId, id)
        }
        return view(tenantId)
    }

    @Transactional(readOnly = true)
    open fun instructions(tenantId: Long, id: Long): List<PromptVersionView> {
        val a = repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        return repo.instructions(id).map {
            PromptVersionView(it.id, it.version, it.body, it.createdBy, it.createdAt.toString(), it.id == a.instructionsVersionId)
        }
    }

    @Transactional
    open fun createInstructions(tenantId: Long, id: Long, body: Map<String, Any?>, createdBy: String?): List<PromptVersionView> {
        repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        val texto = (body["body"] as? String)?.trim().orEmpty()
        if (texto.length < AdminRules.PROMPT_MIN) throw AdminException.validation(mapOf("body" to "Mínimo ${AdminRules.PROMPT_MIN} caracteres"))
        val nueva = repo.insertInstructions(id, texto, createdBy?.take(120))
        if (Parse.bool(body["activate"]) ?: true) repo.updateAgent(id, mapOf("instructions_version_id" to nueva))
        adminService.invalidar()
        return instructions(tenantId, id)
    }

    @Transactional
    open fun activateInstructions(tenantId: Long, id: Long, versionId: Long?): List<PromptVersionView> {
        repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        // Nulo = sin instrucciones de la empresa (solo identidad y rol).
        if (versionId != null && repo.instructions(id).none { it.id == versionId }) throw AdminException.notFound("Esa versión no es de este agente")
        repo.updateAgent(id, mapOf("instructions_version_id" to versionId))
        adminService.invalidar()
        return instructions(tenantId, id)
    }

    @Transactional
    open fun savePlaybook(tenantId: Long, id: Long, body: Map<String, Any?>, createdBy: String?): TenantAiView {
        val a = repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        val config = body["config"] as? Map<*, *> ?: throw AdminException.validation(mapOf("config" to "Falta el playbook"))
        repo.savePlaybook(id, tenantId, a.agentCode, json.writeValueAsString(config), createdBy?.take(120))
        playbooks.invalidar()
        log.info("playbook guardado empresa={} agente={}", tenantId, id)
        return view(tenantId)
    }

    // ── edicion ─────────────────────────────────────────────────────────────

    /** Valida y guarda lo que venga de {name, modelId, fallbackModelId, imageModelId, temperature, tools, active, channelIds, includeErp}. */
    private fun aplicar(tenantId: Long, id: Long, body: Map<String, Any?>) {
        val a = repo.agent(tenantId, id) ?: throw AdminException.notFound("El agente no existe en esta empresa")
        if (a.archived) throw AdminException.conflict("ARCHIVED", "El agente está archivado")
        val f = linkedMapOf<String, String>()
        val cambios = linkedMapOf<String, Any?>()

        if (body.containsKey("name")) {
            val n = Parse.text(body["name"])
            f.putAll(validarNombre(tenantId, n, id))
            cambios["name"] = n
        }
        fun idPropio(campo: String, columna: String, actual: Long?): Long? {
            if (!body.containsKey(campo)) return actual
            val v = body[campo]?.let { Parse.long(it) ?: run { f[campo] = "Elige un modelo activo"; null } }
            cambios[columna] = v
            return v
        }
        val modelo = idPropio("modelId", "model_id", a.modelId)
        val respaldo = idPropio("fallbackModelId", "fallback_model_id", a.fallbackModelId)
        val imagen = idPropio("imageModelId", "image_model_id", a.imageModelId)
        val temperatura = if (body.containsKey("temperature")) {
            body["temperature"]?.let { Parse.decimal(it) ?: run { f["temperature"] = "Entre 0 y 1"; null } }.also { cambios["temperature"] = it }
        } else a.temperature
        if (body.containsKey("active")) cambios["is_active"] = Parse.bool(body["active"]) ?: run { f["active"] = "Valor inválido"; a.active }

        // Herramientas: null = todas las del tipo; lista = solo esas, dentro del tipo.
        val delTipo = admin.tools()[a.agentId].orEmpty()
        var propias: List<String>? = if (a.toolsOverride) repo.tools(id) else null
        if (body.containsKey("tools")) {
            propias = (body["tools"] as? List<*>)?.map { it.toString().trim() }?.distinct()
            if (body["tools"] != null && propias == null) f["tools"] = "Lista inválida"
            propias?.filter { p -> delTipo.none { g -> AdminRules.cubre(g.pattern, p) || AdminRules.cubre(p, g.pattern) } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { f["tools"] = "Fuera de las herramientas del tipo: ${it.joinToString()}" }
        }
        val efectivas = efectivas(delTipo, propias)

        // Lo efectivo (propio o heredado) tiene que ser un agente valido.
        val draft = AdminRules.AgentDraft(
            modelId = modelo ?: a.typeModelId,
            fallbackModelId = respaldo ?: a.typeFallbackModelId,
            imageModelId = imagen ?: a.typeImageModelId,
            temperature = temperatura ?: a.typeTemperature,
            maxToolLoops = a.typeMaxToolLoops,
            responseTimeoutMs = a.typeResponseTimeoutMs,
            tools = efectivas,
        )
        val modelos = admin.models().map { AdminRules.ModelInfo(it.id, it.active, it.supportsTools, it.generatesImages) }
        AdminRules.validateAgent(draft, modelos).forEach { (k, v) -> if (!k.startsWith("tools.")) f.putIfAbsent(k, v) }

        // Canales: solo los de esta empresa (o el del ERP, que se crea aqui).
        val pideCanales = body.containsKey("channelIds") || body.containsKey("includeErp")
        val canalesEmpresa = repo.channels(tenantId)
        var pedidos: Set<Long> = emptySet()
        if (pideCanales) {
            pedidos = ((body["channelIds"] as? List<*>)?.mapNotNull { Parse.long(it) } ?: emptyList()).toSet()
            val ajenos = pedidos - canalesEmpresa.map { it.id }.toSet()
            if (ajenos.isNotEmpty()) f["channelIds"] = "Canales que no son de esta empresa: ${ajenos.joinToString()}"
        }
        if (f.isNotEmpty()) throw AdminException.validation(f)

        if (body.containsKey("tools")) {
            cambios["tools_override"] = propias != null
            repo.replaceTools(id, propias.orEmpty())
        }
        repo.updateAgent(id, cambios)

        if (pideCanales) {
            val erp = TenantAdminRepository.erpRef(tenantId)
            var conErp = pedidos
            if (Parse.bool(body["includeErp"]) == true) {
                val existente = canalesEmpresa.firstOrNull { it.accountRef == erp }?.id ?: repo.createErpChannel(tenantId)
                conErp = conErp + existente
            } else if (body.containsKey("includeErp")) {
                canalesEmpresa.firstOrNull { it.accountRef == erp }?.let { conErp = conErp - it.id }
            }
            // Los que deja: quedan sin agente. Los nuevos: se le pasan (cerrando la asignacion anterior).
            canalesEmpresa.filter { it.tenantAgentId == id && it.id !in conErp }.forEach { repo.endAssignments(it.id, tenantId) }
            conErp.filter { cid -> canalesEmpresa.firstOrNull { it.id == cid }?.tenantAgentId != id }.forEach { cid ->
                repo.endAssignments(cid, tenantId)
                repo.assign(cid, tenantId, a.agentId, id)
            }
        }
    }

    private fun validarNombre(tenantId: Long, nombre: String?, propio: Long?): Map<String, String> = when {
        nombre == null || nombre.length > 120 -> mapOf("name" to "Nombre de 1 a 120 caracteres")
        repo.agents(tenantId).any { !it.archived && it.id != propio && it.name.equals(nombre, ignoreCase = true) } ->
            mapOf("name" to "Ya hay un agente con ese nombre en la empresa")
        else -> emptyMap()
    }

    private fun efectivas(delTipo: List<ToolGrantView>, propias: List<String>?): List<ToolGrantView> =
        if (propias == null) delTipo
        else delTipo.filter { g -> propias.any { p -> AdminRules.cubre(g.pattern, p) || AdminRules.cubre(p, g.pattern) } }

    // ── vista ───────────────────────────────────────────────────────────────

    private fun vista(a: TenantAgentRow, delTipo: List<ToolGrantView>, canales: List<ChannelRow>): TenantAgentView {
        val propias = if (a.toolsOverride) repo.tools(a.id) else null
        val instr = a.instructionsVersionId?.let { vid -> repo.instructions(a.id).firstOrNull { it.id == vid } }
        val pb = repo.playbook(a.id)
        return TenantAgentView(
            id = a.id,
            tenantId = a.tenantId,
            agentId = a.agentId,
            agentCode = a.agentCode,
            agentName = a.agentName,
            kind = a.kind,
            name = a.name,
            modelId = a.modelId,
            fallbackModelId = a.fallbackModelId,
            imageModelId = a.imageModelId,
            temperature = a.temperature,
            effective = TenantAgentView.Effective(
                modelId = a.modelId ?: a.typeModelId,
                fallbackModelId = a.fallbackModelId ?: a.typeFallbackModelId,
                imageModelId = a.imageModelId ?: a.typeImageModelId,
                temperature = a.temperature ?: a.typeTemperature,
                tools = efectivas(delTipo, propias),
            ),
            tools = propias,
            instructions = instr?.let { PromptVersionView(it.id, it.version, it.body, it.createdBy, it.createdAt.toString(), true) },
            playbook = pb?.let { PlaybookView(it.version, it.createdAt.toString(), it.createdBy, leer(it.config)) }
                ?: repo.defaultPlaybook(a.agentCode)?.let { PlaybookView(0, null, null, leer(it)) },
            channelIds = canales.filter { it.tenantAgentId == a.id }.map { it.id },
            active = a.active,
            archived = a.archived,
            createdAt = a.createdAt.toString(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun leer(raw: String): Map<String, Any?> =
        runCatching { json.readValue(raw, Map::class.java) as Map<String, Any?> }.getOrNull() ?: emptyMap()

    private companion object {
        private val log = LoggerFactory.getLogger(TenantAdminService::class.java)
        val LOCALES = setOf("es-GT", "en-US")
        const val NUMERO_LIBRE = "available"
        const val NUMERO_CONECTADO = "connected"
        const val NUMERO_OCUPADO = "in_use"
        val CAPACIDADES_WHATSAPP = Capabilities(buttons = 3, markdown = false, maxChars = 4096, streaming = false)
    }
}
