package com.erp_maya.agent.admin

import com.erp_maya.agent.context.domain.ToolMode
import com.erp_maya.agent.model.service.CredentialResolver
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.tools.service.ToolCatalog
import io.micronaut.cache.CacheManager
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Modelos y agentes para la consola de plataforma.
 *
 * Cada escritura vacia los caches de configuracion (contexto de ejecucion,
 * agentes de sistema y proveedores): el cambio aplica en el siguiente mensaje,
 * no a los cinco minutos.
 */
@Singleton
open class AdminService(
    private val repo: AdminRepository,
    private val catalog: ToolCatalog,
    private val credentials: CredentialResolver,
    private val caches: CacheManager<Any>,
    private val router: ModelRouter,
) {

    // ── lectura ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    open fun catalog(): Catalog {
        val proveedores = repo.providers()
        val porProveedor = proveedores.associateBy { it.id }
        val uso = repo.modelUsage()
        val empresas = repo.modelTenantUsage()
        return Catalog(
            providers = proveedores.map { p -> vista(p, repo.models().count { it.providerId == p.id }) },
            models = repo.models().map { m -> vista(m, porProveedor[m.providerId], uso, empresas) },
            tools = catalog.all().map { t ->
                ToolView(t.name, if (t.mode == ToolMode.WRITE) "write" else "read", AdminRules.groupOf(t.name), t.description)
            },
            providerCodes = router.codes().sorted(),
        )
    }

    @Transactional(readOnly = true)
    open fun agents(): List<AgentView> {
        val tools = repo.tools()
        val empresas = repo.companiesByAgent()
        val instancias = repo.tenantAgentsByType()
        return repo.agents().map { vista(it, tools[it.id].orEmpty(), empresas[it.id] ?: 0, instancias[it.id] ?: 0) }
    }

    @Transactional(readOnly = true)
    open fun prompts(agentId: Long): List<PromptVersionView> {
        val a = repo.agent(agentId) ?: throw AdminException.notFound("El agente no existe")
        return repo.prompts(a.code).map {
            PromptVersionView(it.id, it.version, it.body, it.createdBy, it.createdAt.toString(), it.id == a.promptId)
        }
    }

    // ── modelos ─────────────────────────────────────────────────────────────

    @Transactional
    open fun createModel(body: Map<String, Any?>): ModelView {
        val providerId = Parse.long(body["providerId"])
        val proveedor = repo.providers().firstOrNull { it.id == providerId }
        val generaImagenes = Parse.bool(body["generatesImages"]) ?: false
        val draft = AdminRules.ModelDraft(
            modelName = Parse.text(body["modelName"]),
            contextWindow = Parse.int(body["contextWindow"]),
            inputCost1k = Parse.decimal(body["inputCost1k"]),
            cachedCost1k = Parse.decimal(body["cachedCost1k"]),
            outputCost1k = Parse.decimal(body["outputCost1k"]),
            generatesImages = generaImagenes,
            imageCost = Parse.decimal(body["imageCost"]) ?: if (generaImagenes) null else BigDecimal.ZERO,
        )
        val f = LinkedHashMap(AdminRules.validateModel(draft))
        if (proveedor == null) f["providerId"] = "Elige un proveedor"
        if (f.isNotEmpty()) throw AdminException.validation(f)
        if (repo.modelNameTaken(proveedor!!.id, draft.modelName!!)) {
            throw AdminException.conflict("MODEL_TAKEN", "Ese modelo ya existe en el proveedor", mapOf("modelName" to "Ya existe en ${nombre(proveedor.code)}"))
        }
        val id = repo.insertModel(
            providerId = proveedor.id,
            modelName = draft.modelName!!,
            contextWindow = draft.contextWindow!!,
            input = draft.inputCost1k!!,
            cached = draft.cachedCost1k,
            output = draft.outputCost1k!!,
            tools = Parse.bool(body["supportsTools"]) ?: false,
            cache = Parse.bool(body["supportsCache"]) ?: false,
            vision = Parse.bool(body["supportsVision"]) ?: false,
            images = generaImagenes,
            imageCost = draft.imageCost!!,
        )
        log.info("modelo creado id={} proveedor={} nombre={}", id, proveedor.code, draft.modelName)
        return modelView(id)
    }

    @Transactional
    open fun updateModel(id: Long, body: Map<String, Any?>): ModelView {
        val actual = repo.model(id) ?: throw AdminException.notFound("El modelo no existe")
        val f = linkedMapOf<String, String>()
        val cambios = linkedMapOf<String, Any?>()

        fun decimal(campo: String, columna: String, nulable: Boolean = false) {
            if (!body.containsKey(campo)) return
            val v = Parse.decimal(body[campo])
            if (v == null && !(nulable && body[campo] == null)) f[campo] = "Número mayor o igual a 0"
            else if (v != null && v.signum() < 0) f[campo] = "Número mayor o igual a 0"
            else cambios[columna] = v
        }
        fun bool(campo: String, columna: String) {
            if (!body.containsKey(campo)) return
            val v = Parse.bool(body[campo])
            if (v == null) f[campo] = "Valor inválido" else cambios[columna] = v
        }

        if (body.containsKey("contextWindow")) {
            val v = Parse.int(body["contextWindow"])
            if (v == null || v < AdminRules.CONTEXT_MIN) f["contextWindow"] = "Entero de 1 000 o más" else cambios["context_window"] = v
        }
        decimal("inputCost1k", "input_cost_1k")
        decimal("cachedCost1k", "cached_cost_1k", nulable = true)
        decimal("outputCost1k", "output_cost_1k")
        decimal("imageCost", "image_cost")
        bool("supportsTools", "supports_tools")
        bool("supportsCache", "supports_cache")
        bool("supportsVision", "supports_vision")
        bool("generatesImages", "generates_images")
        bool("active", "is_active")
        if (f.isNotEmpty()) throw AdminException.validation(f)

        // Lo que no puede cambiar mientras un agente lo use.
        val uso = repo.modelUsage().filter { it.second == id }
        if (cambios["is_active"] == false && uso.isNotEmpty()) {
            throw AdminException.conflict("MODEL_IN_USE", "Lo usa ${uso.map { it.first }.distinct().joinToString()}: cambia esos agentes primero")
        }
        val empresas = repo.modelTenantUsage()[id] ?: 0
        if (cambios["is_active"] == false && empresas > 0) {
            throw AdminException.conflict("MODEL_IN_USE", "Lo usan agentes de $empresas empresa(s): cámbialos primero")
        }
        if (cambios["generates_images"] == false && uso.any { it.third == "image" }) {
            throw AdminException.conflict("MODEL_IN_USE", "Es el modelo de imágenes de ${uso.filter { it.third == "image" }.joinToString { it.first }}")
        }
        if (cambios["supports_tools"] == false) {
            val conTools = repo.tools()
            val agentes = repo.agents().filter { (it.modelId == id || it.fallbackModelId == id) && conTools[it.id].orEmpty().isNotEmpty() }
            if (agentes.isNotEmpty()) {
                throw AdminException.conflict("MODEL_IN_USE", "Lo usa ${agentes.joinToString { it.code }}, que necesita herramientas")
            }
        }
        val imagenes = (cambios["generates_images"] as Boolean?) ?: actual.generatesImages
        if (imagenes && cambios.containsKey("image_cost") && cambios["image_cost"] == null) {
            throw AdminException.validation(mapOf("imageCost" to "Número mayor o igual a 0"))
        }

        repo.updateModel(id, cambios)
        invalidar()
        log.info("modelo actualizado id={} campos={}", id, cambios.keys)
        return modelView(id)
    }

    // ── agentes ─────────────────────────────────────────────────────────────

    @Transactional
    open fun updateAgent(id: Long, body: Map<String, Any?>): AgentView {
        val actual = repo.agent(id) ?: throw AdminException.notFound("El agente no existe")
        val toolsActuales = repo.tools()[id].orEmpty()
        val f = linkedMapOf<String, String>()

        fun idOpcional(campo: String, actualV: Long?): Long? =
            if (!body.containsKey(campo)) actualV
            else body[campo]?.let { Parse.long(it) ?: run { f[campo] = "Elige un modelo activo"; actualV } }

        val tools = if (body.containsKey("tools")) {
            val lista = (body["tools"] as? List<*>)?.map { (it as? Map<*, *>)?.entries?.associate { e -> e.key.toString() to e.value } ?: emptyMap() }
            if (lista == null) { f["tools"] = "Lista inválida"; toolsActuales } else {
                val (grants, errores) = AdminRules.resolveTools(lista, catalog.all())
                f.putAll(errores)
                grants
            }
        } else toolsActuales

        val draft = AdminRules.AgentDraft(
            modelId = if (body.containsKey("modelId")) Parse.long(body["modelId"]) else actual.modelId,
            fallbackModelId = idOpcional("fallbackModelId", actual.fallbackModelId),
            imageModelId = idOpcional("imageModelId", actual.imageModelId),
            temperature = if (body.containsKey("temperature")) Parse.decimal(body["temperature"]) else actual.temperature,
            maxToolLoops = if (body.containsKey("maxToolLoops")) Parse.int(body["maxToolLoops"]) else actual.maxToolLoops,
            responseTimeoutMs = if (body.containsKey("responseTimeoutMs")) Parse.int(body["responseTimeoutMs"]) else actual.responseTimeoutMs,
            tools = tools,
        )
        val modelos = repo.models().map { AdminRules.ModelInfo(it.id, it.active, it.supportsTools, it.generatesImages) }
        f.putAll(AdminRules.validateAgent(draft, modelos))
        val activo = if (body.containsKey("active")) Parse.bool(body["active"]) ?: run { f["active"] = "Valor inválido"; actual.active } else actual.active
        if (f.isNotEmpty()) throw AdminException.validation(f)

        val textos = linkedMapOf<String, Any?>()
        if (body.containsKey("name")) {
            val n = Parse.text(body["name"])
            if (n == null || n.length > 120) throw AdminException.validation(mapOf("name" to "Nombre de 1 a 120 caracteres"))
            textos["name"] = n
        }
        if (body.containsKey("description")) textos["description"] = Parse.text(body["description"])?.take(500)
        repo.updateAgent(
            id,
            textos + mapOf(
                "model_id" to draft.modelId,
                "fallback_model_id" to draft.fallbackModelId,
                "image_model_id" to draft.imageModelId,
                "temperature" to draft.temperature,
                "max_tool_loops" to draft.maxToolLoops,
                "response_timeout_ms" to draft.responseTimeoutMs,
                "is_active" to activo,
            ),
        )
        if (body.containsKey("tools")) repo.replaceTools(id, tools)
        invalidar()
        log.info("agente actualizado id={} code={} campos={}", id, actual.code, body.keys)
        return agentView(id)
    }

    @Transactional
    open fun createPrompt(agentId: Long, body: Map<String, Any?>, createdBy: String?): PromptVersionView {
        val a = repo.agent(agentId) ?: throw AdminException.notFound("El agente no existe")
        val texto = (body["body"] as? String)?.trim().orEmpty()
        if (texto.length < AdminRules.PROMPT_MIN) {
            throw AdminException.validation(mapOf("body" to "Mínimo ${AdminRules.PROMPT_MIN} caracteres"))
        }
        val activar = Parse.bool(body["activate"]) ?: true
        val id = repo.insertPrompt(a.code, texto, createdBy?.take(120))
        if (activar) {
            repo.updateAgent(agentId, mapOf("prompt_version_id" to id))
            invalidar()
        }
        log.info("prompt nuevo agente={} id={} activo={}", a.code, id, activar)
        return prompts(agentId).first { it.id == id }
    }

    @Transactional
    open fun activatePrompt(agentId: Long, promptId: Long): AgentView {
        val a = repo.agent(agentId) ?: throw AdminException.notFound("El agente no existe")
        if (repo.prompts(a.code).none { it.id == promptId }) throw AdminException.notFound("Esa versión no es de este agente")
        repo.updateAgent(agentId, mapOf("prompt_version_id" to promptId))
        invalidar()
        log.info("prompt activado agente={} id={}", a.code, promptId)
        return agentView(agentId)
    }

    // ── proveedores ─────────────────────────────────────────────────────────

    @Transactional
    open fun createProvider(body: Map<String, Any?>): ProviderView {
        val code = Parse.text(body["code"])?.lowercase()
        val f = linkedMapOf<String, String>()
        if (code == null || code !in router.codes()) f["code"] = "Sin implementación para ese proveedor (disponibles: ${router.codes().sorted().joinToString()})"
        else if (repo.providers().any { it.code == code }) f["code"] = "Ese proveedor ya está registrado"
        val name = Parse.text(body["name"]) ?: code?.let(::nombre)
        val datos = AdminRules.validateProvider(name, Parse.text(body["baseUrl"]), Parse.text(body["authType"]) ?: "bearer", Parse.text(body["credentialRef"]))
        f.putAll(datos.second)
        if (f.isNotEmpty()) throw AdminException.validation(f)
        val d = datos.first!!
        val id = repo.insertProvider(code!!, d.name, d.baseUrl, d.authType, d.credentialRef)
        invalidar()
        log.info("proveedor creado id={} code={}", id, code)
        return vista(repo.providers().first { it.id == id }, 0)
    }

    @Transactional
    open fun updateProvider(id: Long, body: Map<String, Any?>): ProviderView {
        val actual = repo.providers().firstOrNull { it.id == id } ?: throw AdminException.notFound("El proveedor no existe")
        val (d, f) = AdminRules.validateProvider(
            if (body.containsKey("name")) Parse.text(body["name"]) else actual.name ?: nombre(actual.code),
            if (body.containsKey("baseUrl")) Parse.text(body["baseUrl"]) else actual.baseUrl,
            if (body.containsKey("authType")) Parse.text(body["authType"]) else actual.authType,
            if (body.containsKey("credentialRef")) Parse.text(body["credentialRef"]) else actual.credentialRef,
        )
        val activo = if (body.containsKey("active")) Parse.bool(body["active"]) else actual.active
        val errores = LinkedHashMap(f)
        if (activo == null) errores["active"] = "Valor inválido"
        if (errores.isNotEmpty()) throw AdminException.validation(errores)
        if (activo == false && actual.active) {
            val modelos = repo.models().filter { it.providerId == id }.map { it.id }.toSet()
            val agentes = repo.modelUsage().filter { it.second in modelos }.map { it.first }.distinct()
            val empresas = repo.modelTenantUsage().filterKeys { it in modelos }.values.sum()
            if (agentes.isNotEmpty() || empresas > 0) {
                throw AdminException.conflict("PROVIDER_IN_USE", "Sus modelos los usa ${(agentes + listOfNotNull(if (empresas > 0) "$empresas empresa(s)" else null)).joinToString()}")
            }
        }
        repo.updateProvider(id, mapOf("name" to d!!.name, "base_url" to d.baseUrl, "auth_type" to d.authType, "credential_ref" to d.credentialRef, "is_active" to activo))
        invalidar()
        log.info("proveedor actualizado id={}", id)
        return vista(repo.providers().first { it.id == id }, repo.models().count { it.providerId == id })
    }

    // ── identidad de Tino ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    open fun identity(): IdentityView {
        val activa = repo.identityPromptId()
        val versiones = repo.prompts(IDENTITY_CODE).map {
            PromptVersionView(it.id, it.version, it.body, it.createdBy, it.createdAt.toString(), it.id == activa)
        }
        return IdentityView(versiones.firstOrNull { it.active }, versiones)
    }

    @Transactional
    open fun createIdentity(body: Map<String, Any?>, createdBy: String?): IdentityView {
        val texto = (body["body"] as? String)?.trim().orEmpty()
        if (texto.length < AdminRules.PROMPT_MIN) throw AdminException.validation(mapOf("body" to "Mínimo ${AdminRules.PROMPT_MIN} caracteres"))
        val id = repo.insertPrompt(IDENTITY_CODE, texto, createdBy?.take(120))
        if (Parse.bool(body["activate"]) ?: true) repo.setIdentity(id)
        invalidar()
        log.info("identidad de Tino: version nueva id={}", id)
        return identity()
    }

    @Transactional
    open fun activateIdentity(promptId: Long): IdentityView {
        if (repo.prompts(IDENTITY_CODE).none { it.id == promptId }) throw AdminException.notFound("Esa versión no es de la identidad")
        repo.setIdentity(promptId)
        invalidar()
        return identity()
    }

    // ── tipos de agente ─────────────────────────────────────────────────────

    @Transactional
    open fun createAgentType(body: Map<String, Any?>, createdBy: String?): AgentView {
        val f = linkedMapOf<String, String>()
        val code = Parse.text(body["code"])?.lowercase()
        if (code == null || !AdminRules.CODE.matches(code) || code == IDENTITY_CODE) f["code"] = "Minúsculas, números y guion bajo (2 a 60)"
        else if (repo.agentCodeTaken(code)) f["code"] = "Ese código ya existe"
        val name = Parse.text(body["name"])
        if (name == null || name.length > 120) f["name"] = "Nombre de 1 a 120 caracteres"
        val kind = Parse.text(body["kind"]) ?: "conversational"
        if (kind !in AdminRules.KINDS) f["kind"] = "Conversa o interno"
        val prompt = (body["prompt"] as? String)?.trim().orEmpty()
        if (prompt.length < AdminRules.PROMPT_MIN) f["prompt"] = "Mínimo ${AdminRules.PROMPT_MIN} caracteres"
        val lista = (body["tools"] as? List<*>)?.map { (it as? Map<*, *>)?.entries?.associate { e -> e.key.toString() to e.value } ?: emptyMap() } ?: emptyList()
        val (tools, errTools) = AdminRules.resolveTools(lista, catalog.all())
        f.putAll(errTools)
        val draft = AdminRules.AgentDraft(
            modelId = Parse.long(body["modelId"]),
            fallbackModelId = Parse.long(body["fallbackModelId"]),
            imageModelId = Parse.long(body["imageModelId"]),
            temperature = Parse.decimal(body["temperature"]) ?: BigDecimal("0.20"),
            maxToolLoops = Parse.int(body["maxToolLoops"]) ?: 5,
            responseTimeoutMs = Parse.int(body["responseTimeoutMs"]) ?: 30_000,
            tools = tools,
        )
        f.putAll(AdminRules.validateAgent(draft, repo.models().map { AdminRules.ModelInfo(it.id, it.active, it.supportsTools, it.generatesImages) }))
        if (f.isNotEmpty()) throw AdminException.validation(f)
        val id = repo.insertAgentType(
            code!!, name!!, kind, Parse.text(body["description"])?.take(500), draft.modelId!!, draft.fallbackModelId,
            draft.imageModelId, draft.temperature!!, draft.maxToolLoops!!, draft.responseTimeoutMs!!, prompt, createdBy?.take(120),
        )
        repo.replaceTools(id, tools)
        invalidar()
        log.info("tipo de agente creado id={} code={}", id, code)
        return agentView(id)
    }

    @Transactional
    open fun archiveAgentType(id: Long, archivar: Boolean): AgentView {
        val a = repo.agent(id) ?: throw AdminException.notFound("El agente no existe")
        if (archivar) {
            if (a.code in SYSTEM_CODES) throw AdminException.conflict("TYPE_SYSTEM", "Lo usa el sistema; no se puede archivar")
            val n = repo.tenantAgentsByType()[id] ?: 0
            if (n > 0) throw AdminException.conflict("TYPE_IN_USE", "Lo usan $n agente(s) de empresa: archívalos o cámbialos primero")
        }
        repo.setArchived("ai_agents", id, archivar)
        invalidar()
        log.info("tipo de agente {} id={}", if (archivar) "archivado" else "restaurado", id)
        return agentView(id)
    }

    // ── vistas ──────────────────────────────────────────────────────────────

    private fun modelView(id: Long): ModelView {
        val m = repo.model(id) ?: throw AdminException.notFound("El modelo no existe")
        return vista(m, repo.providers().firstOrNull { it.id == m.providerId }, repo.modelUsage(), repo.modelTenantUsage())
    }

    private fun agentView(id: Long): AgentView {
        val a = repo.agent(id) ?: throw AdminException.notFound("El agente no existe")
        return vista(a, repo.tools()[id].orEmpty(), repo.companiesByAgent()[id] ?: 0, repo.tenantAgentsByType()[id] ?: 0)
    }

    private fun vista(m: ModelRow, p: ProviderRow?, uso: List<Triple<String, Long, String>>, empresas: Map<Long, Int>) = ModelView(
        id = m.id,
        providerId = m.providerId,
        provider = ProviderRef(m.providerId, p?.code ?: "", p?.name ?: nombre(p?.code ?: ""), p?.let { credentials.isSet(it.credentialRef) } ?: false),
        modelName = m.modelName,
        contextWindow = m.contextWindow,
        inputCost1k = m.inputCost1k,
        cachedCost1k = m.cachedCost1k,
        outputCost1k = m.outputCost1k,
        supportsTools = m.supportsTools,
        supportsCache = m.supportsCache,
        supportsVision = m.supportsVision,
        generatesImages = m.generatesImages,
        imageCost = m.imageCost,
        active = m.active,
        usedBy = uso.filter { it.second == m.id }.map { it.first }.distinct(),
        companiesCount = empresas[m.id] ?: 0,
    )

    private fun vista(a: AgentRow, tools: List<ToolGrantView>, empresas: Int, instancias: Int) = AgentView(
        id = a.id,
        code = a.code,
        name = a.name,
        kind = a.kind,
        description = a.description,
        modelId = a.modelId,
        fallbackModelId = a.fallbackModelId,
        imageModelId = a.imageModelId,
        temperature = a.temperature,
        maxToolLoops = a.maxToolLoops,
        responseTimeoutMs = a.responseTimeoutMs,
        active = a.active,
        archived = a.archived,
        prompt = PromptRef(a.promptId, a.promptVersion, a.promptCreatedBy, a.promptCreatedAt.toString()),
        tools = tools,
        companiesCount = empresas,
        tenantAgentsCount = instancias,
    )

    private fun vista(p: ProviderRow, modelos: Int) = ProviderView(
        id = p.id,
        code = p.code,
        name = p.name ?: nombre(p.code),
        baseUrl = p.baseUrl,
        authType = p.authType,
        credentialRef = p.credentialRef,
        credentialSet = credentials.isSet(p.credentialRef),
        active = p.active,
        modelsCount = modelos,
    )

    /** Los caches de configuracion que leen modelos, agentes o proveedores. */
    internal fun invalidar() {
        CACHES.forEach { nombre ->
            runCatching { caches.getCache(nombre).invalidateAll() }
                .onFailure { log.warn("no se pudo vaciar el cache {}: {}", nombre, it.message) }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AdminService::class.java)
        val CACHES = listOf("execution-context", "agent-definitions", "ai-providers")
        /** Codigo reservado de la identidad de Tino en ai_prompt_versions. */
        const val IDENTITY_CODE = "_tino"
        /** Tipos que el codigo invoca por nombre (resumen de conversaciones). */
        val SYSTEM_CODES = setOf("resumen")
        private val NOMBRES = mapOf("openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google", "mistral" to "Mistral")
        fun nombre(code: String) = NOMBRES[code] ?: code.replaceFirstChar { it.uppercase() }
    }
}
