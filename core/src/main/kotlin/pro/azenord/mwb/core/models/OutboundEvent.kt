package pro.azenord.mwb.core.models

import kotlinx.serialization.Serializable
import kotlin.collections.Map
import kotlin.collections.emptyMap

// Базовый формат исходящих событий наружу в WebSocket
@Serializable
data class OutboundEvent(
val event: String,
val payload: Map<String, String> = emptyMap()
)
