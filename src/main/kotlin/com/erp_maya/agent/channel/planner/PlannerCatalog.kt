package com.erp_maya.agent.channel.planner

import com.erp_maya.agent.erp.client.ErpClient
import com.erp_maya.agent.erp.domain.CatalogCategory
import com.erp_maya.agent.erp.domain.CatalogProduct
import com.erp_maya.agent.erp.domain.ProductImageData
import com.erp_maya.agent.erp.domain.ProductSummary
import com.erp_maya.agent.erp.domain.ProductTemplate
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * El producto del ERP detras de cada tipo de proyecto, con su plantilla de
 * calculo si la tiene (sin costos: el ERP no los da por aqui).
 *
 * Se guarda unos minutos en memoria: cada visitante que abre el planificador
 * pide lo mismo, y una plantilla no cambia a cada rato. Un cambio en el ERP se
 * ve al vencer la entrada.
 */
@Singleton
open class PlannerCatalog(
    private val erp: ErpClient,
    @param:Value("\${planner.template-cache-ttl:5m}") private val vigencia: Duration,
) {

    /** El producto y su plantilla (null: se cotiza por precio de lista y medidas). */
    data class Producto(val product: ProductSummary, val template: ProductTemplate?)

    private class Entrada(val valor: Producto, val vence: Long)

    private val cache = ConcurrentHashMap<Pair<Long, Long>, Entrada>()

    /** [com.erp_maya.agent.erp.domain.ErpException] si el ERP no responde; [PlannerSpecException] si el producto ya no existe. */
    open fun producto(tenantId: Long, productId: Long): Producto {
        val clave = tenantId to productId
        val ahora = System.currentTimeMillis()
        cache[clave]?.takeIf { it.vence > ahora }?.let { return it.valor }
        val p = erp.getProduct(tenantId, SIN_CONVERSACION, productId)
            ?: throw PlannerSpecException("projectType", "Este tipo de proyecto no está disponible por ahora.")
        val t = erp.templateByProduct(tenantId, SIN_CONVERSACION, productId)?.takeIf { it.active }
        val valor = Producto(p, t)
        if (cache.size > MAX_ENTRADAS) cache.entries.removeIf { it.value.vence <= ahora }
        cache[clave] = Entrada(valor, ahora + vigencia.toMillis())
        return valor
    }

    // ── categorias, sus productos y sus fotos (lo que el visitante elige) ──

    private class Lista<T>(val valor: List<T>, val vence: Long)

    private val categorias = ConcurrentHashMap<Long, Lista<CatalogCategory>>()
    private val listas = ConcurrentHashMap<Pair<Long, Long>, Lista<CatalogProduct>>()
    /** Miniaturas por (empresa, producto, version): una foto nueva cambia la version. Las completas no se guardan. */
    private val miniaturas = ConcurrentHashMap<Triple<Long, Long, Long>, ProductImageData>()

    /** Las categorias de la empresa. [com.erp_maya.agent.erp.domain.ErpException] si el ERP no responde. */
    open fun categorias(tenantId: Long): List<CatalogCategory> {
        val ahora = System.currentTimeMillis()
        categorias[tenantId]?.takeIf { it.vence > ahora }?.let { return it.valor }
        val valor = erp.categories(tenantId, SIN_CONVERSACION)
        if (categorias.size > MAX_ENTRADAS) categorias.entries.removeIf { it.value.vence <= ahora }
        categorias[tenantId] = Lista(valor, ahora + vigencia.toMillis())
        return valor
    }

    /** Los productos vendibles y activos de la categoria (y sus subcategorias), hasta [MAX_PRODUCTOS]. */
    open fun productosDe(tenantId: Long, categoryId: Long): List<CatalogProduct> {
        val clave = tenantId to categoryId
        val ahora = System.currentTimeMillis()
        listas[clave]?.takeIf { it.vence > ahora }?.let { return it.valor }
        val valor = erp.productsInCategory(tenantId, SIN_CONVERSACION, categoryId, MAX_PRODUCTOS)
            .filter { !it.status.equals("inactive", ignoreCase = true) }
        if (listas.size > MAX_ENTRADAS) listas.entries.removeIf { it.value.vence <= ahora }
        listas[clave] = Lista(valor, ahora + vigencia.toMillis())
        return valor
    }

    /** La foto del producto; las miniaturas se guardan en memoria por version. null si no tiene. */
    open fun foto(tenantId: Long, productId: Long, version: Long, miniatura: Boolean): ProductImageData? {
        if (!miniatura) return erp.productImage(tenantId, SIN_CONVERSACION, productId, false)
        val clave = Triple(tenantId, productId, version)
        miniaturas[clave]?.let { return it }
        val foto = erp.productImage(tenantId, SIN_CONVERSACION, productId, true) ?: return null
        if (miniaturas.size >= MAX_MINIATURAS) miniaturas.clear()
        miniaturas[clave] = foto
        return foto
    }

    companion object {
        /** El planificador no tiene conversacion (fase 1): el ERP lo registra como 0, igual que el formulario de contacto. */
        const val SIN_CONVERSACION = 0L
        private const val MAX_ENTRADAS = 2_000
        /** Productos por categoria que se muestran: una lista para elegir, no el inventario completo. */
        const val MAX_PRODUCTOS = 100
        /** ≈15 KB cada una: unos 15 MB como mucho. */
        private const val MAX_MINIATURAS = 1_000
    }
}
