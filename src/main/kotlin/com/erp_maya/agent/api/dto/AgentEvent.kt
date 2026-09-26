package com.erp_maya.agent.api.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.micronaut.serde.annotation.Serdeable

/**
 * Lo que el agente devuelve, en terminos neutrales.
 *
 * Nunca lleva markup de un canal: ni botones de WhatsApp, ni HTML, ni
 * markdown. Cada adaptador traduce estos eventos a lo suyo. Es lo que permite
 * que un mismo turno sirva a WhatsApp y al widget del ERP sin ramas en el
 * agente.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AgentEvent.Text::class, name = "text"),
    JsonSubTypes.Type(value = AgentEvent.Card::class, name = "card"),
    JsonSubTypes.Type(value = AgentEvent.Choices::class, name = "choices"),
    JsonSubTypes.Type(value = AgentEvent.Document::class, name = "document"),
)
@Serdeable
sealed interface AgentEvent {

    @Serdeable
    data class Text(val text: String) : AgentEvent

    /** `card` nombra la plantilla; `data` son sus campos ya resueltos. */
    @Serdeable
    data class Card(val card: String, val data: Map<String, String>) : AgentEvent

    /**
     * Opciones para que el usuario escoja. Cuantas caben lo decide el canal
     * (`capabilities.buttons`); el agente propone y el adaptador recorta.
     */
    @Serdeable
    data class Choices(val items: List<Choice>) : AgentEvent

    @Serdeable
    data class Document(val name: String, val mediaType: String, val url: String) : AgentEvent
}

@Serdeable
data class Choice(val id: String, val label: String)
