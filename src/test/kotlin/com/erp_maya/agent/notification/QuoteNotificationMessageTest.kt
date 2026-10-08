package com.erp_maya.agent.notification

import com.erp_maya.agent.playbook.domain.PlantillaCambios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** El aviso de cambios es de formato fijo: lo que dice sale del ERP, no del modelo. */
class QuoteNotificationMessageTest {

    private val payload = ChangesAppliedPayload(
        quoteId = 2,
        docNumber = "COT-000002",
        clientName = "Fernando Escobar",
        total = "Q 1,234.56",
        results = listOf(
            ChangeResult(kind = "agregar", status = "aplicada", detail = "Agregar 20 × Coca Cola 600ml"),
            ChangeResult(kind = "descuento", status = "ajustada", detail = "Descuento de 5 %", response = "máximo autorizado"),
            ChangeResult(kind = "quitar", status = "rechazada", detail = "Quitar Pepsi 2L", response = "ya va en camino"),
            ChangeResult(kind = "condiciones", status = "respondida", detail = "¿Entregan el sábado?", response = "Sí, en zona 10"),
        ),
    )

    @Test
    fun `arma el mensaje con la plantilla por defecto`() {
        val texto = QuoteNotificationService.componer(PlantillaCambios(), payload)
        assertEquals(
            listOf(
                "Hola Fernando, tu asesor revisó tu cotización COT-000002:",
                "✅ Agregar 20 × Coca Cola 600ml",
                "✏️ Descuento de 5 % — máximo autorizado",
                "❌ Quitar Pepsi 2L — ya va en camino",
                "💬 ¿Entregan el sábado?: Sí, en zona 10",
                "Nuevo total: Q 1,234.56. Te adjunto la cotización actualizada.",
            ).joinToString("\n"),
            texto,
        )
    }

    @Test
    fun `sin respuesta no deja separadores colgando`() {
        val texto = QuoteNotificationService.componer(
            PlantillaCambios(),
            payload.copy(clientName = null, results = listOf(ChangeResult(kind = "descuento", status = "ajustada", detail = "Descuento de 5 %"))),
        )
        assertEquals("Hola, tu asesor revisó tu cotización COT-000002:", texto.lines().first())
        assertEquals("✏️ Descuento de 5 %", texto.lines()[1])
    }

    @Test
    fun `aviso de cotizacion enviada`() {
        val texto = QuoteNotificationService.componerEnviada(
            com.erp_maya.agent.playbook.domain.PlantillaEnviada().texto,
            payload.copy(results = emptyList()),
        )
        assertEquals(
            "Hola Fernando, tu asesor revisó tu cotización COT-000002 y te la envía oficialmente. Total: Q 1,234.56. Te adjunto el documento.",
            texto,
        )
    }
}
