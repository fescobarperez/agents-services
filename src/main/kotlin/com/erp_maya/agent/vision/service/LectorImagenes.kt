package com.erp_maya.agent.vision.service

import com.erp_maya.agent.api.dto.InputImage
import com.erp_maya.agent.context.domain.ExecutionContext
import com.erp_maya.agent.context.domain.ModelRef
import com.erp_maya.agent.model.domain.ImagenParaModelo
import com.erp_maya.agent.model.domain.ModelOptions
import com.erp_maya.agent.model.service.ModelRouter
import com.erp_maya.agent.model.service.RoutedCompletion
import com.erp_maya.agent.playbook.domain.Playbook
import io.micronaut.serde.ObjectMapper
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Lee las fotos que manda el cliente y las convierte en texto.
 *
 * Es un paso aparte del turno, antes del bucle del agente: el modelo de la
 * conversacion recibe la lectura como texto y el historial guarda solo ese
 * texto. La foto se baja, se lee y se descarta; no se persiste en ningun lado.
 *
 * Nunca tumba el turno: si no se puede leer (playbook apagado, ningun modelo
 * ve imagenes, Meta no la entrega, el proveedor falla), devuelve una nota para
 * que el agente le pida al cliente que describa lo que necesita.
 */
@Singleton
open class LectorImagenes(
    private val router: ModelRouter,
    private val fuentes: List<ImageSource>,
    private val imagenes: ImagenService,
    private val json: ObjectMapper,
) {

    /** La lectura de una foto, para guardarla en el mensaje que la trajo. */
    data class Lectura(val messageId: String?, val texto: String)

    data class Resultado(
        /** Texto que se agrega al mensaje del cliente para el modelo de la conversacion. */
        val notaParaModelo: String,
        val lecturas: List<Lectura>,
        /** La llamada al modelo de vision, para auditarla; nula si no hubo. */
        val corrida: RoutedCompletion?,
    )

    open fun leer(
        contexto: ExecutionContext,
        playbook: Playbook,
        fotos: List<InputImage>,
        textoCliente: String?,
    ): Resultado {
        val cfg = playbook.imagenes
        if (!cfg.aceptar) {
            return sinLeer(
                fotos,
                "(no se leyo: la empresa no recibe fotos por este canal)",
                "[El cliente mando ${fotos.size} foto(s), pero esta empresa no recibe fotos por este canal. " +
                    "Pidele con amabilidad que describa con palabras lo que necesita: producto, medidas y material.]",
            )
        }

        val modelos = modelosConVision(contexto)
        if (modelos.isEmpty()) {
            log.warn("tenant {}: llegaron fotos y ningun modelo del agente ve imagenes", contexto.tenantId)
            return sinLeer(
                fotos,
                "(no se leyo: ningun modelo configurado ve imagenes)",
                "[El cliente mando ${fotos.size} foto(s) y no puedes verlas. Diselo y pidele que describa con " +
                    "palabras lo que necesita: producto, medidas y material.]",
            )
        }

        val maximo = cfg.maxPorTurno.coerceAtLeast(1)
        val usadas = fotos.take(maximo)
        val ignoradas = fotos.size - usadas.size

        // Se bajan y preparan una por una: una que falle no tumba a las demas.
        val preparadas = usadas.map { foto -> foto to preparar(foto) }
        val legibles = preparadas.mapNotNull { (foto, img) -> img?.let { foto to it } }
        val noAbiertas = preparadas.filter { it.second == null }.map { it.first }

        if (legibles.isEmpty()) {
            return Resultado(
                notaParaModelo = notaFinal(emptyList(), noAbiertas.size, ignoradas, maximo),
                lecturas = usadas.map { Lectura(it.messageId, "$MARCA_FOTO (no se pudo abrir)") } + ignoradasLecturas(fotos, maximo),
                corrida = null,
            )
        }

        val corrida = try {
            router.completeWithImages(
                principal = modelos[0],
                respaldo = modelos.getOrNull(1),
                opciones = ModelOptions(
                    temperature = TEMPERATURA,
                    timeoutMs = contexto.agent.responseTimeoutMs,
                    maxTokens = MAX_TOKENS,
                ),
                instrucciones = instrucciones(playbook, textoCliente, legibles.size),
                imagenes = legibles.map { it.second },
            )
        } catch (e: Exception) {
            log.warn("tenant {}: no se pudieron leer {} foto(s): {}", contexto.tenantId, legibles.size, e.message)
            return sinLeer(
                fotos,
                "(no se pudo leer)",
                "[El cliente mando ${fotos.size} foto(s) y en este momento no se pudieron leer. Pidele que describa " +
                    "con palabras lo que necesita: producto, medidas y material.]",
            )
        }

        val descritas = interpretar(corrida.completion.text, legibles.size)
        val lecturas = legibles.mapIndexed { i, (foto, _) -> Lectura(foto.messageId, descritas[i]) } +
            noAbiertas.map { Lectura(it.messageId, "$MARCA_FOTO (no se pudo abrir)") } +
            ignoradasLecturas(fotos, maximo)

        return Resultado(
            notaParaModelo = notaFinal(descritas, noAbiertas.size, ignoradas, maximo),
            lecturas = lecturas,
            corrida = corrida,
        )
    }

    /** El principal primero si ve imagenes; si no, el respaldo. */
    private fun modelosConVision(contexto: ExecutionContext): List<ModelRef> =
        listOfNotNull(contexto.model, contexto.fallbackModel).filter { it.supportsVision }.distinctBy { it.id }

    private fun preparar(foto: InputImage): ImagenParaModelo? {
        val fuente = fuentes.firstOrNull { it.source == foto.source }
        if (fuente == null) {
            log.warn("foto de origen '{}' sin fuente registrada", foto.source)
            return null
        }
        return try {
            imagenes.preparar(fuente.descargar(foto.ref, ImagenService.MAX_BYTES))
        } catch (e: Exception) {
            // Nunca se registra el contenido ni la URL de Meta: solo el motivo.
            log.warn("no se pudo preparar la foto {}: {} {}", foto.messageId, e.javaClass.simpleName, e.message)
            null
        }
    }

    private fun sinLeer(fotos: List<InputImage>, detalle: String, nota: String) =
        Resultado(nota, fotos.map { Lectura(it.messageId, "$MARCA_FOTO $detalle") }, null)

    private fun ignoradasLecturas(fotos: List<InputImage>, maximo: Int) =
        fotos.drop(maximo).map { Lectura(it.messageId, "$MARCA_FOTO (no se leyo: pasa del maximo de $maximo por mensaje)") }

    /** La nota que ve el modelo de la conversacion junto al mensaje del cliente. */
    private fun notaFinal(descritas: List<String>, noAbiertas: Int, ignoradas: Int, maximo: Int): String = buildString {
        if (descritas.isNotEmpty()) {
            append("[Lectura automatica de las fotos que acaba de mandar el cliente. Las medidas son estimadas: ")
            append("confirmalas con el cliente antes de usarlas en la cotizacion.]\n")
            descritas.forEachIndexed { i, d -> append(i + 1).append(") ").append(d.removePrefix("$MARCA_FOTO ")).append('\n') }
        }
        if (noAbiertas > 0) {
            append("[$noAbiertas foto(s) no se pudieron abrir: pidele que las mande de nuevo o que describa lo que muestran.]\n")
        }
        if (ignoradas > 0) {
            append("[Mando $ignoradas foto(s) mas que no se leyeron: el maximo es $maximo por mensaje. Diselo y pidele ")
            append("que mande solo las mas importantes.]\n")
        }
    }.trimEnd()

    private fun instrucciones(playbook: Playbook, textoCliente: String?, cantidad: Int): String = buildString {
        append("Eres parte del equipo de ventas de ")
        append(playbook.negocio?.descripcion?.takeIf { it.isNotBlank() } ?: "una empresa")
        append(". El cliente mando ").append(cantidad).append(" foto(s) mientras cotiza")
        textoCliente?.takeIf { it.isNotBlank() }?.let { append(" y escribio: «").append(it.take(500)).append("»") }
        append(".\nDescribe cada foto para poder cotizar. Responde SOLO con un JSON con esta forma, sin texto alrededor:\n")
        append(
            """{"fotos":[{"documento_personal":false,"relevante":true,"producto":"tipo de producto o estructura",""" +
                """"descripcion":"lo que se ve, en una o dos frases","material":"","acabado":"",""" +
                """"medidas_estimadas":{"alto":null,"ancho":null,"profundidad":null,"unidad":"m"},""" +
                """"referencia_escala":"en que te basaste para estimar (puerta, persona, ventana)",""" +
                """"confianza":"alta|media|baja","notas":"detalles que importen para cotizar"}]}""",
        )
        append("\nReglas:\n")
        append("- Una entrada por foto, en el mismo orden en que llegaron.\n")
        append("- Las medidas son estimaciones: si no hay nada que de escala, deja null.\n")
        append("- Si la foto marca o señala una zona, describe esa zona y su tamaño aproximado.\n")
        append("- Documento de identidad, tarjeta, estado de cuenta u otro documento personal: documento_personal=true ")
        append("y no transcribas ningun dato.\n")
        append("- relevante=false si no tiene que ver con lo que vende la empresa.\n")
        append("- No inventes precios.")
    }

    /**
     * Pasa el JSON del modelo a una linea de texto por foto. Si el modelo no
     * respeto el formato, se usa su texto recortado: peor una lectura en prosa
     * que ninguna.
     */
    internal fun interpretar(respuesta: String?, cantidad: Int): List<String> {
        val crudo = respuesta?.trim().orEmpty()
        val fotos = runCatching {
            val inicio = crudo.indexOf('{')
            val fin = crudo.lastIndexOf('}')
            require(inicio >= 0 && fin > inicio)
            json.readValue(crudo.substring(inicio, fin + 1), LecturaModelo::class.java)?.fotos.orEmpty()
        }.getOrElse { emptyList() }

        return (0 until cantidad).map { i ->
            fotos.getOrNull(i)?.let(::describir)
                ?: if (fotos.isEmpty() && crudo.isNotBlank()) "$MARCA_FOTO ${crudo.take(LARGO_PROSA)}"
                else "$MARCA_FOTO (sin descripcion)"
        }
    }

    private fun describir(f: FotoLeida): String = buildString {
        append(MARCA_FOTO).append(' ')
        when {
            f.documentoPersonal == true -> append("Documento personal: no se usa ni se transcribe.")
            f.relevante == false -> append("No relacionada con lo que se cotiza: ").append(f.descripcion.orEmpty().take(200))
            else -> {
                f.producto?.takeIf { it.isNotBlank() }?.let { append(it).append(": ") }
                append(f.descripcion.orEmpty().take(400))
                f.material?.takeIf { it.isNotBlank() }?.let { append(". Material: ").append(it) }
                f.acabado?.takeIf { it.isNotBlank() }?.let { append(". Acabado: ").append(it) }
                medidas(f.medidasEstimadas)?.let { m ->
                    append(". Medidas estimadas (sin confirmar): ").append(m)
                    f.referenciaEscala?.takeIf { it.isNotBlank() }?.let { append(", segun ").append(it) }
                    f.confianza?.takeIf { it.isNotBlank() }?.let { append(", confianza ").append(it) }
                }
                f.notas?.takeIf { it.isNotBlank() }?.let { append(". Notas: ").append(it.take(300)) }
                append('.')
            }
        }
    }

    private fun medidas(m: MedidasLeidas?): String? {
        if (m == null) return null
        val partes = listOf("alto" to m.alto, "ancho" to m.ancho, "profundidad" to m.profundidad)
            .filter { it.second != null }
        if (partes.isEmpty()) return null
        val unidad = m.unidad?.takeIf { it.isNotBlank() } ?: "m"
        return partes.joinToString(", ") { (nombre, v) -> "$nombre ${v!!.stripTrailingZeros().toPlainString()} $unidad" }
    }

    companion object {
        /** Como empieza en el historial el mensaje de una foto; la lectura lo reemplaza. */
        const val MARCA_FOTO = "[Foto del cliente]"

        private val TEMPERATURA = BigDecimal("0.1")
        private const val MAX_TOKENS = 1_500
        private const val LARGO_PROSA = 600

        private val log = LoggerFactory.getLogger(LectorImagenes::class.java)
    }
}
