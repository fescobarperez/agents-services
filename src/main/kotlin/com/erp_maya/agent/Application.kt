package com.erp_maya.agent

import io.micronaut.runtime.Micronaut.run
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    cargarDotEnv()
    run(*args)
}

/**
 * Carga un `.env` del directorio de trabajo a propiedades del sistema, igual
 * que erp_maya_services: los secretos (DB_PASSWORD, la llave del modelo,
 * AGENT_KEY_ERP) no viven en el repositorio y configurarlos a mano en cada
 * forma de arrancar es lo que se olvida.
 *
 * El entorno real y lo que venga por -D mandan sobre el archivo. En el
 * servidor no hay `.env` y esto no hace nada.
 */
private fun cargarDotEnv() {
    val archivo = Path.of(".env")
    if (!Files.isReadable(archivo)) return
    try {
        Files.readAllLines(archivo).asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.indexOf('=') > 0 }
            .forEach { linea ->
                val igual = linea.indexOf('=')
                val clave = linea.substring(0, igual).trim()
                val valor = linea.substring(igual + 1).trim()
                if (System.getenv(clave) == null && System.getProperty(clave) == null) {
                    System.setProperty(clave, valor)
                }
            }
    } catch (e: IOException) {
        System.err.println("Aviso: no se pudo leer .env — ${e.message}")
    }
}
