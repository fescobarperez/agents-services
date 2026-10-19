package com.erp_maya.agent.render

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El pie del render siempre lo marca como ilustrativo y dice cuantos quedan. */
class RenderPieTest {

    private fun job(n: Int, max: Int) = RenderJob(1, 1, 1, "x", "y", 1, "m", "p", n, max, 1)

    @Test
    fun `pie de un render intermedio y del ultimo`() {
        val primero = RenderJobConsumer.pie(job(1, 3))
        assertTrue(primero.startsWith("Imagen ilustrativa (1 de 3)"))
        assertTrue(primero.contains("qué le cambiamos"))
        assertTrue(RenderJobConsumer.pie(job(3, 3)).contains("última imagen"))
    }
}
