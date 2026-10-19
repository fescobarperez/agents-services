package com.erp_maya.agent.quote

import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.playbook.domain.Playbook
import com.erp_maya.agent.prompt.domain.FichaDiseno
import com.erp_maya.agent.prompt.domain.FotoCliente
import com.erp_maya.agent.prompt.domain.QuoteDraft
import com.erp_maya.agent.render.ColaRenders
import jakarta.inject.Singleton

/**
 * Pide un render: valida, arma las instrucciones desde la ficha de diseño y
 * lo deja en la cola. No genera nada dentro del turno.
 *
 * Reglas que aplica el codigo:
 *  - solo si el playbook lo permite y el agente tiene modelo de imagenes;
 *  - hace falta una foto del lugar y la ficha con tipo y medidas (la idea es
 *    juntar todo antes del primer render, no gastar renders preguntando);
 *  - como maximo `renders_max` por cotizacion.
 */
@Singleton
open class RenderService(private val trabajos: ColaRenders) {

    open fun solicitar(
        contexto: ExecutionContext,
        conversationId: Long,
        actual: QuoteDraft?,
        playbook: Playbook,
        fotos: List<FotoCliente>,
        telefono: String?,
        /** 1 = la foto mas vieja que se recuerda; null = la del render anterior o la mas nueva. */
        fotoNumero: Int?,
        ajuste: String?,
    ): DraftStep {
        val cfg = playbook.imagenes
        val modelo = contexto.imageModel
        if (!cfg.generarRenders || modelo == null) {
            throw IllegalArgumentException(
                "Esta empresa no genera imágenes de ejemplo. Ofrécele describirlo con detalle o que un asesor le " +
                    "comparta fotos de trabajos similares.",
            )
        }
        val destinatario = telefono ?: throw IllegalArgumentException("Las imágenes solo se envían por WhatsApp")
        if (fotos.isEmpty()) {
            throw IllegalArgumentException(
                "No hay una foto del lugar. Pídele al cliente una foto de donde lo quiere (si puede, que marque la zona).",
            )
        }
        val borrador = actual ?: QuoteDraft()
        val ficha = borrador.diseno ?: FichaDiseno()
        faltantes(ficha).takeIf { it.isNotEmpty() }?.let {
            throw IllegalArgumentException(
                "Antes del render completa la ficha con cotizacion.diseno: falta ${it.joinToString(", ")}. " +
                    "Pregúntalo todo en un solo mensaje.",
            )
        }
        val maximo = cfg.rendersMax.coerceAtLeast(1)
        if (ficha.renders >= maximo) {
            throw IllegalArgumentException(
                "Ya se generaron las $maximo imágenes que permite esta cotización. Sigue los ajustes por texto y " +
                    "anótalos en la ficha; el asesor los verá.",
            )
        }
        val foto = elegirFoto(fotos, fotoNumero, ficha.fotoRender)
        val numero = ficha.renders + 1
        val id = trabajos.encolar(
            conversationId = conversationId,
            tenantId = contexto.tenantId,
            accountRef = contexto.channel.accountRef.removePrefix("wa:"),
            recipient = destinatario,
            modelId = modelo.id,
            baseMediaId = foto.mediaId,
            prompt = instrucciones(ficha, foto.lectura, ajuste),
            renderNumber = numero,
            renderMax = maximo,
        )
        val nueva = ficha.copy(renders = numero, ultimoRender = id, fotoRender = foto.mediaId)
        return DraftStep(
            borrador = borrador.copy(diseno = nueva, actualizadaMs = System.currentTimeMillis()),
            mensaje = "Estoy preparando la imagen ($numero de $maximo); te llega en unos segundos.",
            datos = mapOf(
                "render" to numero,
                "de" to maximo,
                "quedan" to maximo - numero,
                "instruccion" to "Dile al cliente que en unos segundos le llega la imagen. No la describas: todavía " +
                    "no existe. Cuando la vea, pregúntale qué le cambiarías y anota los cambios en la ficha.",
            ),
        )
    }

    companion object {
        /** Lo minimo para un render que valga la pena. */
        internal fun faltantes(f: FichaDiseno): List<String> = listOfNotNull(
            "qué es (tipo)".takeIf { f.tipo.isNullOrBlank() },
            "las medidas".takeIf { f.medidas == null },
        )

        /**
         * La foto pedida; si no se pidio, la del render anterior (para que los
         * ajustes salgan sobre la misma), y si no, la mas nueva.
         */
        internal fun elegirFoto(fotos: List<FotoCliente>, numero: Int?, anterior: String?): FotoCliente {
            numero?.let { n ->
                return fotos.getOrNull(n - 1)
                    ?: throw IllegalArgumentException("No hay foto $n; el cliente mandó ${fotos.size}")
            }
            return fotos.firstOrNull { it.mediaId == anterior } ?: fotos.last()
        }

        /**
         * Instrucciones para el modelo de imagenes. Se arman SOLO desde la
         * ficha: lo que se ve en el render es lo que se cotiza. Siempre sobre
         * la foto original del cliente (no sobre el render anterior), con la
         * ficha ya ajustada.
         */
        internal fun instrucciones(f: FichaDiseno, lecturaFoto: String?, ajuste: String?): String = buildString {
            append("Edita esta foto real del cliente para mostrar cómo quedaría su proyecto. Resultado fotorrealista, ")
            append("con la misma luz, perspectiva y escala de la foto.\n")
            append("Qué agregar: ").append(f.tipo ?: "el proyecto")
            f.descripcion?.let { append(" — ").append(it) }
            append(".\n")
            f.material?.let { append("Material: ").append(it).append(".\n") }
            f.acabado?.let { append("Acabado/color: ").append(it).append(".\n") }
            f.medidas?.let { m ->
                val partes = listOfNotNull(
                    m.height?.let { "alto ${it.stripTrailingZeros().toPlainString()} m" },
                    m.width?.let { "ancho ${it.stripTrailingZeros().toPlainString()} m" },
                    m.depth?.let { "profundidad ${it.stripTrailingZeros().toPlainString()} m" },
                )
                append("Medidas aproximadas: ").append(partes.joinToString(", ")).append(".\n")
            }
            if (f.detalles.isNotEmpty()) {
                append("Detalles: ").append(f.detalles.entries.joinToString("; ") { "${it.key}: ${it.value}" }).append(".\n")
            }
            lecturaFoto?.removePrefix("[Foto del cliente]")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("Lo que muestra la foto: ").append(it).append('\n')
            }
            append("Si el cliente dibujó marcas o trazos en la foto, coloca el proyecto en esa zona y borra las marcas.\n")
            ajuste?.trim()?.takeIf { it.isNotEmpty() }?.let { append("Cambio que pidió el cliente: ").append(it).append('\n') }
            append("Reglas: agrega SOLO lo descrito. No agregues muebles, cojines, plantas, alfombras, lámparas, ")
            append("decoración ni personas. No cambies la casa, las ventanas, el jardín ni lo que ya existe. ")
            append("Sin textos, logotipos ni marcas de agua.")
        }
    }
}
