package com.erp_maya.agent.erp.client

import com.erp_maya.agent.erp.domain.AiLimit
import com.erp_maya.agent.erp.domain.CatalogCategory
import com.erp_maya.agent.erp.domain.CatalogProduct
import com.erp_maya.agent.erp.domain.ProductImageData
import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.erp.domain.TemplateItemWrite
import com.erp_maya.agent.erp.domain.CompanyInfo
import com.erp_maya.agent.erp.domain.CustomerSummary
import com.erp_maya.agent.erp.domain.LeadAck
import com.erp_maya.agent.erp.domain.LeadWrite
import com.erp_maya.agent.erp.domain.ErpException
import com.erp_maya.agent.erp.domain.ErpQuote
import com.erp_maya.agent.erp.domain.ErpClientPrices
import com.erp_maya.agent.erp.domain.ErpPreviewLine
import com.erp_maya.agent.erp.domain.ErpPreviewPrices
import com.erp_maya.agent.erp.domain.ErpPreviewScenario
import com.erp_maya.agent.erp.domain.ErpScenarioPrices
import com.erp_maya.agent.erp.domain.ErpChangeRequest
import com.erp_maya.agent.erp.domain.ChangeRequestWrite
import com.erp_maya.agent.erp.domain.ErpQuoteSummary
import com.erp_maya.agent.erp.domain.IssuedQuote
import com.erp_maya.agent.erp.domain.ProductProposal
import com.erp_maya.agent.erp.domain.QuoteDesignAck
import com.erp_maya.agent.erp.domain.QuoteDesignWrite
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.QuoteLineRequest
import com.erp_maya.agent.erp.domain.QuotePreview
import com.erp_maya.agent.erp.domain.QuoteLineWrite
import com.erp_maya.agent.erp.domain.StockInfo
import com.erp_maya.agent.erp.domain.SaludAppointment
import com.erp_maya.agent.erp.domain.SaludAppointmentType
import com.erp_maya.agent.erp.domain.SaludAppointmentWrite
import com.erp_maya.agent.erp.domain.SaludAvailability
import com.erp_maya.agent.erp.domain.SaludCandidate
import com.erp_maya.agent.erp.domain.SaludFeedbackResult
import com.erp_maya.agent.erp.domain.SaludFeedbackWrite
import com.erp_maya.agent.erp.domain.SaludPatientCreate
import com.erp_maya.agent.erp.domain.SaludPatientDetail
import com.erp_maya.agent.erp.domain.SaludPatientFromClient
import com.erp_maya.agent.erp.domain.SaludProfessional
import com.erp_maya.agent.erp.domain.SaludRescheduleWrite
import com.erp_maya.agent.erp.domain.SaludStatusWrite
import java.math.BigDecimal

/**
 * Todo lo que este servicio necesita de maya-erp-services, en terminos de
 * dominio y no de rutas.
 *
 * La interfaz existe por una razon concreta: **las rutas del ERP todavia no
 * estan validadas**. Detras de estos metodos, cambiar un path toca un solo
 * archivo en vez de esparcirse por las herramientas del agente.
 *
 * `tenantId` y `conversationId` son parametros explicitos y no estado
 * implicito: es lo que obliga a que ninguna llamada salga sin empresa, que es
 * el error mas caro posible aqui.
 */
interface ErpClient {

    fun searchProducts(tenantId: Long, conversationId: Long, query: String, limit: Int = 10): List<ProductSummary>

    fun getStock(tenantId: Long, conversationId: Long, productId: Long): StockInfo?

    fun findCustomerByPhone(tenantId: Long, conversationId: Long, phone: String): CustomerSummary?

    fun previewQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
    ): QuotePreview

    /**
     * Emite la cotizacion. `idempotencyKey` se deriva del id del mensaje
     * entrante para que un reintento no emita dos documentos.
     */
    fun issueQuote(
        tenantId: Long,
        conversationId: Long,
        customerId: Long?,
        lines: List<QuoteLineRequest>,
        expectedTotal: BigDecimal,
        idempotencyKey: String,
    ): IssuedQuote

    fun getCompany(tenantId: Long, conversationId: Long): CompanyInfo?

    /** Plantilla de calculo activa del producto; null si no tiene. */
    fun templateByProduct(tenantId: Long, conversationId: Long, productId: Long): ProductTemplate? =
        throw ErpException("templateByProduct no implementado")

    /** Agrega a la cotizacion una linea calculada con plantilla (el ERP desglosa su materia prima). */
    fun addTemplateItem(tenantId: Long, conversationId: Long, quoteId: Long, item: TemplateItemWrite): ErpQuote =
        throw ErpException("addTemplateItem no implementado")

    /** Recalcula una linea de plantilla (otra medida, opciones o cantidad). */
    fun recalcTemplateItem(tenantId: Long, conversationId: Long, quoteId: Long, itemId: Long, item: TemplateItemWrite): ErpQuote =
        throw ErpException("recalcTemplateItem no implementado")

    /** Limite mensual de mensajes de IA del plan; nulo si no se pudo leer (no se corta). */
    fun aiLimit(tenantId: Long, conversationId: Long): AiLimit? = null

    // ── Borrador de cotizacion (asistente) ──────────────────────────────────
    // Con cuerpo por defecto para que los dobles de prueba existentes sigan
    // compilando; la implementacion real esta en ErpHttpClient.

    /** Registra (o actualiza, por conversacion) un prospecto para que ventas lo contacte. */
    fun createLead(tenantId: Long, conversationId: Long, lead: LeadWrite): LeadAck =
        throw ErpException("createLead no implementado")

    fun searchCustomers(tenantId: Long, conversationId: Long, query: String, limit: Int = 5): List<CustomerSummary> =
        throw ErpException("searchCustomers no implementado")

    /** Alta de un cliente nuevo (lo pide el asistente cuando nadie tiene ese numero). */
    fun createCustomer(tenantId: Long, conversationId: Long, name: String, nit: String?, phone: String?): CustomerSummary =
        throw ErpException("createCustomer no implementado")

    fun getCustomer(tenantId: Long, conversationId: Long, customerId: Long): CustomerSummary? =
        throw ErpException("getCustomer no implementado")

    fun getProduct(tenantId: Long, conversationId: Long, productId: Long): ProductSummary? =
        throw ErpException("getProduct no implementado")

    /** Las categorias del catalogo de la empresa. */
    fun categories(tenantId: Long, conversationId: Long): List<CatalogCategory> =
        throw ErpException("categories no implementado")

    /** Los productos vendibles de una categoria y de sus subcategorias, hasta `limit`. */
    fun productsInCategory(tenantId: Long, conversationId: Long, categoryId: Long, limit: Int): List<CatalogProduct> =
        throw ErpException("productsInCategory no implementado")

    /** La foto de un producto, completa o miniatura; null si no tiene. */
    fun productImage(tenantId: Long, conversationId: Long, productId: Long, thumb: Boolean): ProductImageData? =
        throw ErpException("productImage no implementado")

    /**
     * Propone un producto que no esta en el catalogo. El ERP lo crea sin
     * precio y pendiente de revision, o devuelve el que ya exista con ese nombre.
     */
    fun proposeProduct(tenantId: Long, conversationId: Long, propuesta: ProductProposal): ProductSummary =
        throw ErpException("proposeProduct no implementado")

    /** Guarda en la cotizacion el diseño que el cliente aprobo (ficha + render). */
    fun saveQuoteDesign(tenantId: Long, conversationId: Long, quoteId: Long, diseno: QuoteDesignWrite): QuoteDesignAck =
        throw ErpException("saveQuoteDesign no implementado")

    /** Crea la cotizacion como PROSPECTO del asistente. */
    fun createProspectQuote(
        tenantId: Long,
        conversationId: Long,
        customer: CustomerSummary,
        lines: List<QuoteLineWrite>,
        channel: String,
        conversationRef: String,
    ): ErpQuote = throw ErpException("createProspectQuote no implementado")

    fun getQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote? =
        throw ErpException("getQuote no implementado")

    /** Precios al cliente con los cargos dentro de cada producto (carrito del chat web). */
    fun getClientPrices(tenantId: Long, conversationId: Long, quoteId: Long): ErpClientPrices? =
        throw ErpException("getClientPrices no implementado")

    /** Precios al cliente de productos en espera (aun sin cotizacion), con los cargos de la empresa dentro. */
    fun previewClientPrices(tenantId: Long, conversationId: Long, lines: List<ErpPreviewLine>): ErpPreviewPrices? =
        throw ErpException("previewClientPrices no implementado")

    /**
     * Varias vistas previas en una llamada (planificador: una opcion y sus
     * variantes). Cada escenario lleva sus cargos; uno invalido vuelve con
     * `error` sin tumbar a los demas.
     */
    fun previewClientPricesBatch(tenantId: Long, conversationId: Long, scenarios: List<ErpPreviewScenario>): List<ErpScenarioPrices> =
        throw ErpException("previewClientPricesBatch no implementado")

    /** Reemplaza las lineas: las que no vengan se eliminan; sin id son nuevas. */
    fun updateQuoteLines(tenantId: Long, conversationId: Long, quote: ErpQuote, lines: List<QuoteLineWrite>): ErpQuote =
        throw ErpException("updateQuoteLines no implementado")

    /** abierta → prospecto: el cliente la dio por terminada. */
    fun finalizeQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote =
        throw ErpException("finalizeQuote no implementado")

    /** prospecto → abierta, solo si ningun vendedor la abrio (si no, ErpException con el motivo). */
    fun reopenQuote(tenantId: Long, conversationId: Long, quoteId: Long): ErpQuote =
        throw ErpException("reopenQuote no implementado")

    /** Las ultimas cotizaciones de un cliente. */
    fun quotesByClient(tenantId: Long, conversationId: Long, clientId: Long): List<ErpQuoteSummary> =
        throw ErpException("quotesByClient no implementado")

    /** Solicitud de cambio sobre una cotizacion que ya tomo un vendedor. */
    fun createChangeRequest(tenantId: Long, conversationId: Long, quoteId: Long, request: ChangeRequestWrite): ErpChangeRequest =
        throw ErpException("createChangeRequest no implementado")

    /** Las solicitudes de cambio de una cotizacion, con su respuesta y motivo. */
    fun listChangeRequests(tenantId: Long, conversationId: Long, quoteId: Long): List<ErpChangeRequest> =
        throw ErpException("listChangeRequests no implementado")

    /** El cliente aprueba la version enviada. ErpException si ya no es esa version, vencio, etc. */
    fun clientApprove(tenantId: Long, conversationId: Long, quoteId: Long, version: Int): ErpQuote =
        throw ErpException("clientApprove no implementado")

    /** El cliente rechaza la version enviada; motivo opcional. */
    fun clientReject(tenantId: Long, conversationId: Long, quoteId: Long, version: Int, reasonCode: String?, note: String?): ErpQuote =
        throw ErpException("clientReject no implementado")

    /** Motivo que el cliente da despues de rechazar. */
    fun clientReason(tenantId: Long, conversationId: Long, quoteId: Long, reasonCode: String?, note: String?): ErpQuote =
        throw ErpException("clientReason no implementado")

    /** El PDF de la cotizacion tal como lo genera el ERP; null si no existe. */
    fun getQuotePdf(tenantId: Long, conversationId: Long, quoteId: Long): ByteArray? =
        throw ErpException("getQuotePdf no implementado")

    /** Anota en la bitacora de la cotizacion sin cambiar su estado. */
    fun addQuoteNote(tenantId: Long, conversationId: Long, quoteId: Long, note: String): Unit =
        throw ErpException("addQuoteNote no implementado")

    // ── Stackline Salud (agenda) ─────────────────────────────────────────
    // El token del asistente solo puede ver y registrar pacientes y manejar
    // citas; el ERP valida el permiso en cada llamada.

    /** Tipos de cita de la clinica. */
    fun saludTypes(tenantId: Long, conversationId: Long): List<SaludAppointmentType> =
        throw ErpException("saludTypes no implementado")

    /** Usuarios con su perfil de profesional (los que no atienden vienen con configured=false). */
    fun saludProfessionals(tenantId: Long, conversationId: Long): List<SaludProfessional> =
        throw ErpException("saludProfessionals no implementado")

    /** Horarios libres de un profesional para un tipo de cita en un dia (yyyy-MM-dd). */
    fun saludAvailability(tenantId: Long, conversationId: Long, professionalId: Long, date: String, typeId: Long): SaludAvailability? =
        throw ErpException("saludAvailability no implementado")

    /** Citas entre dos instantes ISO; de un paciente si viene `patientId`. */
    fun saludAppointments(tenantId: Long, conversationId: Long, patientId: Long?, from: String, to: String): List<SaludAppointment> =
        throw ErpException("saludAppointments no implementado")

    fun saludAppointment(tenantId: Long, conversationId: Long, appointmentId: Long): SaludAppointment? =
        throw ErpException("saludAppointment no implementado")

    fun saludCreateAppointment(tenantId: Long, conversationId: Long, cita: SaludAppointmentWrite): SaludAppointment =
        throw ErpException("saludCreateAppointment no implementado")

    fun saludReschedule(tenantId: Long, conversationId: Long, appointmentId: Long, cambio: SaludRescheduleWrite): SaludAppointment =
        throw ErpException("saludReschedule no implementado")

    /** confirmada | cancelada (lo unico que el ERP le deja hacer al asistente). */
    fun saludChangeStatus(tenantId: Long, conversationId: Long, appointmentId: Long, cambio: SaludStatusWrite): SaludAppointment =
        throw ErpException("saludChangeStatus no implementado")

    /** Lo que contesto el paciente al recordatorio de un control (mejor, igual, peor). */
    fun saludFollowUpFeedback(tenantId: Long, conversationId: Long, followUpId: Long, respuesta: SaludFeedbackWrite): SaludFeedbackResult =
        throw ErpException("saludFollowUpFeedback no implementado")

    /** Clientes que coinciden con el telefono, marcando cuales ya son pacientes. */
    fun saludCandidates(tenantId: Long, conversationId: Long, query: String): List<SaludCandidate> =
        throw ErpException("saludCandidates no implementado")

    fun saludCreatePatient(tenantId: Long, conversationId: Long, alta: SaludPatientCreate): SaludPatientDetail =
        throw ErpException("saludCreatePatient no implementado")

    fun saludPatientFromClient(tenantId: Long, conversationId: Long, clientId: Long, alta: SaludPatientFromClient): SaludPatientDetail =
        throw ErpException("saludPatientFromClient no implementado")
}
