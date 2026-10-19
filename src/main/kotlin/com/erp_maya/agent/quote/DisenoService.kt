package com.erp_maya.agent.quote

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.Dimensions
import com.erp_maya.agent.erp.domain.ProductProposal
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.QuoteDraft
import jakarta.inject.Singleton
import java.text.Normalizer

/**
 * La ficha de diseño de la cotizacion en curso y los productos que el
 * asistente propone cuando lo que pide el cliente no esta en el catalogo.
 *
 * Las reglas las aplica el codigo, no el prompt:
 *  - medidas estimadas de una foto no se cotizan sin que el cliente las
 *    confirme en su propio mensaje;
 *  - un producto nuevo solo se propone si el playbook lo permite y cae en
 *    una de las lineas de la empresa; queda sin precio y lo revisa un asesor.
 */
@Singleton
open class DisenoService(private val erp: ErpClient) {

    /**
     * Agrega o corrige datos de la ficha. Lo que no venga se conserva. Si
     * cambian las medidas, vuelven a quedar sin confirmar salvo que las haya
     * dicho el cliente.
     */
    open fun actualizar(
        actual: QuoteDraft?,
        tipo: String?,
        descripcion: String?,
        material: String?,
        acabado: String?,
        medidas: Dimensions?,
        origenMedidas: String?,
        detalles: String?,
    ): DraftStep {
        medidas?.let { m ->
            listOfNotNull(m.height, m.width, m.depth).forEach {
                require(it.signum() > 0) { "Las medidas deben ser mayores que cero" }
            }
        }
        val origen = origenMedidas?.trim()?.lowercase()
        require(origen == null || origen in ORIGENES) { "origen_medidas debe ser 'foto' o 'cliente'" }

        val borrador = actual ?: QuoteDraft()
        val antes = borrador.diseno ?: FichaDiseno()
        val cambianMedidas = medidas != null && !medidas.igualA(antes.medidas)
        // Sin origen se asume foto: lo conservador es pedir confirmacion.
        val origenFinal = if (cambianMedidas) origen ?: FichaDiseno.ORIGEN_FOTO else origen ?: antes.origenMedidas
        val ficha = antes.copy(
            tipo = tipo.limpio() ?: antes.tipo,
            descripcion = descripcion.limpio() ?: antes.descripcion,
            material = material.limpio() ?: antes.material,
            acabado = acabado.limpio() ?: antes.acabado,
            medidas = if (cambianMedidas) medidas else antes.medidas,
            origenMedidas = origenFinal,
            medidasConfirmadas = when {
                cambianMedidas -> origenFinal == FichaDiseno.ORIGEN_CLIENTE
                origen == FichaDiseno.ORIGEN_CLIENTE && antes.medidas != null -> true
                else -> antes.medidasConfirmadas
            },
            detalles = mezclar(antes.detalles, detalles),
        )
        return DraftStep(
            borrador = borrador.copy(diseno = ficha, actualizadaMs = System.currentTimeMillis()),
            mensaje = "Anoté el diseño: ${ficha.texto()}.",
            datos = datos(ficha),
        )
    }

    /**
     * El cliente confirmo las medidas de la ficha. `confirmado` sale del
     * mensaje del cliente en ESTE turno, no de lo que diga el modelo.
     */
    open fun confirmarMedidas(actual: QuoteDraft?, confirmado: Boolean): DraftStep {
        val sinMedidas = IllegalArgumentException("La ficha no tiene medidas: anótalas primero con cotizacion.diseno")
        val borrador = actual ?: throw sinMedidas
        val ficha = borrador.diseno ?: throw sinMedidas
        val medidas = ficha.medidas ?: throw sinMedidas
        if (ficha.medidasConfirmadas) {
            return DraftStep(borrador, "Las medidas ya estaban confirmadas: ${medidas.texto()}.", datos = datos(ficha))
        }
        if (!confirmado) {
            throw IllegalArgumentException(
                "El cliente no confirmó en este mensaje. Muéstrale las medidas (${medidas.texto()}) y pregúntale si " +
                    "son correctas; confírmalas cuando él responda que sí.",
            )
        }
        val nueva = ficha.copy(medidasConfirmadas = true)
        return DraftStep(
            borrador = borrador.copy(diseno = nueva, actualizadaMs = System.currentTimeMillis()),
            mensaje = "Medidas confirmadas: ${medidas.texto()}.",
            datos = datos(nueva),
        )
    }

    /**
     * Propone al ERP un producto que no esta en el catalogo, con lo que dice
     * la ficha. El ERP lo crea sin precio y pendiente de revision (o devuelve
     * el que ya exista con ese nombre).
     */
    open fun productoNuevo(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        playbook: Playbook,
        nombre: String,
        categoria: String,
    ): DraftStep {
        val cfg = playbook.cotizacion.productosNuevos
        val paraElCliente = cfg.mensajeFueraDeLinea?.takeIf { it.isNotBlank() }
            ?: "que un asesor revisará su pedido y lo contactará"
        if (!cfg.permitido) {
            throw IllegalArgumentException(
                "Esta empresa no crea productos fuera del catálogo desde el asistente. Dile al cliente $paraElCliente.",
            )
        }
        require(nombre.isNotBlank()) { "Falta el nombre del producto" }
        require(categoria.isNotBlank()) { "Falta la categoría (línea de producto)" }
        if (!enLineas(categoria, cfg.lineas)) {
            throw IllegalArgumentException(
                "'$categoria' no es una de las líneas que vende la empresa (${cfg.lineas.joinToString()}). " +
                    "No lo propongas; dile al cliente $paraElCliente.",
            )
        }
        val ficha = actual?.diseno
        if (ficha?.medidasSinConfirmar() == true) {
            throw IllegalArgumentException(
                "Las medidas (${ficha?.medidas?.texto()}) no están confirmadas: pregúntale al cliente si son " +
                    "correctas y usa cotizacion.confirmar_medidas antes de proponer el producto.",
            )
        }
        val medidas = ficha?.medidas
        val producto = erp.proposeProduct(
            contexto.tenantId, conversationId,
            ProductProposal(
                name = nombre.trim().take(200),
                category = categoria.trim(),
                notes = ficha?.texto()?.takeIf { it.isNotBlank() }?.let { "Propuesto por el asistente. $it" }?.take(4000),
                height = medidas?.height,
                width = medidas?.width,
                depth = medidas?.depth,
                dimensionUnit = medidas?.let { "m" },
            ),
        )
        val pendiente = producto.pendingReview == true
        val borrador = (actual ?: QuoteDraft()).let { b ->
            b.copy(
                diseno = (b.diseno ?: FichaDiseno()).copy(
                    productoId = producto.id,
                    productoNombre = producto.name,
                    productoPendiente = pendiente,
                ),
                actualizadaMs = System.currentTimeMillis(),
            )
        }
        return DraftStep(
            borrador = borrador,
            mensaje = if (pendiente) "Registré «${producto.name}» para que un asesor le ponga precio."
            else "«${producto.name}» ya está en el catálogo.",
            datos = mapOf(
                "productId" to producto.id,
                "nombre" to producto.name,
                "estado" to if (pendiente) "pendiente_revision" else "en_catalogo",
                "siguiente" to "Agrégalo con cotizacion.agregar(productId=${producto.id}, quantity) " +
                    (if (medidas != null) "y las medidas confirmadas. " else ". ") +
                    if (pendiente) "La línea queda por cotizar: el asesor le pone el precio; no inventes un monto."
                    else "Usa el precio que devuelva el sistema.",
            ),
        )
    }

    private fun datos(f: FichaDiseno): Map<String, Any?> = mapOf(
        "ficha" to f.texto(),
        "medidas_confirmadas" to f.medidasConfirmadas,
        "falta" to when {
            f.medidas == null -> "medidas"
            !f.medidasConfirmadas -> "que el cliente confirme las medidas"
            else -> null
        },
    )

    companion object {
        private val ORIGENES = setOf(FichaDiseno.ORIGEN_FOTO, FichaDiseno.ORIGEN_CLIENTE)

        /**
         * Antes de agregar una linea con medidas: si son las estimadas de una
         * foto y el cliente no las confirmo, se rechaza. Otras medidas (las
         * que el cliente dijo con sus palabras) pasan.
         */
        fun validarAgregar(actual: QuoteDraft?, medidas: Dimensions?) {
            val ficha = actual?.diseno ?: return
            if (medidas == null || !ficha.medidasSinConfirmar()) return
            if (medidas.igualA(ficha.medidas)) {
                throw IllegalArgumentException(
                    "Esas medidas salieron de una foto y el cliente no las ha confirmado. Muéstraselas, pregúntale " +
                        "si son correctas y usa cotizacion.confirmar_medidas antes de agregarlas.",
                )
            }
        }

        /** "aguas=2; cubierta=teja" sobre lo que ya habia. Un valor vacio borra la clave. */
        internal fun mezclar(antes: Map<String, String>, texto: String?): Map<String, String> {
            if (texto.isNullOrBlank()) return antes
            val resultado = antes.toMutableMap()
            texto.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { par ->
                val i = par.indexOf('=').takeIf { it > 0 } ?: par.indexOf(':').takeIf { it > 0 } ?: return@forEach
                val clave = par.substring(0, i).trim().lowercase().take(40)
                val valor = par.substring(i + 1).trim().take(200)
                if (clave.isEmpty()) return@forEach
                if (valor.isEmpty()) resultado.remove(clave) else resultado[clave] = valor
            }
            return resultado.toMap()
        }

        /** Sin lineas configuradas vale cualquier categoria; si no, debe ser una de ellas. */
        internal fun enLineas(categoria: String, lineas: List<String>): Boolean =
            lineas.isEmpty() || lineas.any { normalizar(it) == normalizar(categoria) }

        private fun normalizar(t: String) = Normalizer.normalize(t.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

        private fun String?.limpio(): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
    }
}
