package com.erp_maya.agent.vision.service

import com.fasterxml.jackson.annotation.JsonProperty
import io.micronaut.core.annotation.Nullable
import io.micronaut.serde.annotation.Serdeable
import java.math.BigDecimal

/** El JSON que devuelve el modelo al leer las fotos. Todo opcional: el modelo puede omitir campos. */
@Serdeable
data class LecturaModelo(@Nullable val fotos: List<FotoLeida>? = null)

@Serdeable
data class FotoLeida(
    @JsonProperty("documento_personal") @Nullable val documentoPersonal: Boolean? = null,
    @Nullable val relevante: Boolean? = null,
    @Nullable val producto: String? = null,
    @Nullable val descripcion: String? = null,
    @Nullable val material: String? = null,
    @Nullable val acabado: String? = null,
    @JsonProperty("medidas_estimadas") @Nullable val medidasEstimadas: MedidasLeidas? = null,
    @JsonProperty("referencia_escala") @Nullable val referenciaEscala: String? = null,
    @Nullable val confianza: String? = null,
    @Nullable val notas: String? = null,
)

@Serdeable
data class MedidasLeidas(
    @Nullable val alto: BigDecimal? = null,
    @Nullable val ancho: BigDecimal? = null,
    @Nullable val profundidad: BigDecimal? = null,
    @Nullable val unidad: String? = null,
)
