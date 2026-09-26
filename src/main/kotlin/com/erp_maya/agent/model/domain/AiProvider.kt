package com.erp_maya.agent.model.domain

/**
 * Proveedor tal como esta configurado en la base.
 *
 * `credentialRef` es el NOMBRE de la variable de entorno, nunca el secreto.
 * Quien necesite el valor lo resuelve en ejecucion.
 */
data class AiProvider(
    val code: String,
    val baseUrl: String,
    val authType: String,
    val credentialRef: String,
)
