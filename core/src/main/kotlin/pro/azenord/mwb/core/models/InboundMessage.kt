package pro.azenord.mwb.core.models

import kotlinx.serialization.Serializable
import kotlin.collections.Map
import kotlin.collections.emptyMap

// Базовый формат входящих команд от внешнего ИИ-агента
@Serializable
data class InboundMessage(
val action: String,
val payload: Map<String, String> = emptyMap()
)