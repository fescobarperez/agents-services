package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.domain.ErpPreviewLine
import com.erp_maya.agent.erp.domain.ProductTemplate
import com.erp_maya.agent.erp.domain.TemplateChoice
import com.erp_maya.agent.erp.domain.TemplateOption
import com.erp_maya.agent.erp.domain.TemplateParam
import com.erp_maya.agent.erp.domain.TemplatePreset
import java.math.BigDecimal

/**
 * La pergola de las imagenes del SPEC (precios ilustrativos): A dos aguas con
 * policarbonato = Q 16,400; B con teja y luces LED = Q 24,800; sin las luces,
 * B queda en Q 21,300.
 */
object PlannerFixtures {

    const val PRODUCTO_PERGOLA = 501L
    const val PLANTILLA_PERGOLA = 77L
    const val PRODUCTO_DECK = 502L

    fun q(v: String) = BigDecimal(v)

    fun pergola() = ProductTemplate(
        id = PLANTILLA_PERGOLA,
        productId = PRODUCTO_PERGOLA,
        productName = "Pérgola",
        name = "Pérgola a la medida",
        active = true,
        params = listOf(
            TemplateParam("ancho", "Ancho", "m", q("1"), q("8"), q("4"), true),
            TemplateParam("profundidad", "Fondo", "m", q("1"), q("6"), q("3"), true),
            TemplateParam("alto", "Alto", "m", q("2"), q("3.5"), q("2.4"), false),
        ),
        options = listOf(
            TemplateOption("techo", "Techo", true, listOf(
                TemplateChoice("una_agua", "Una agua", isDefault = true),
                TemplateChoice("dos_aguas", "Dos aguas"),
            )),
            TemplateOption("cubierta", "Cubierta", true, listOf(
                TemplateChoice("policarbonato", "Policarbonato", isDefault = true),
                TemplateChoice("teja", "Teja", benefit = "Más fresca y silenciosa con lluvia"),
            )),
            TemplateOption("luces", "Iluminación", false, listOf(
                TemplateChoice("sin", "Sin luces", isDefault = true),
                TemplateChoice("led", "Luces LED"),
            )),
        ),
        presets = listOf(TemplatePreset("premium", "Premium", mapOf("cubierta" to "teja", "luces" to "led"))),
    )

    /** Total al cliente (cargos, ganancia e IVA dentro) de una linea de la pergola. */
    fun tarifa(l: ErpPreviewLine): BigDecimal? {
        if (l.templateId == null) return l.lineTotal.takeIf { it.signum() > 0 }
        val paquete = pergola().presets.firstOrNull { it.code == l.preset }?.choices.orEmpty()
        val c = paquete + l.choices.orEmpty()
        var t = if (c["techo"] == "dos_aguas") q("16400") else q("15900")
        if (c["cubierta"] == "teja") t += q("4900")
        if (c["luces"] == "led") t += q("3500")
        return t.multiply(l.quantity)
    }
}
