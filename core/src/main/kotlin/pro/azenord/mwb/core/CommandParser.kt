package pro.azenord.mwb.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import pro.azenord.mwb.core.models.InboundMessage
import pro.azenord.mwb.core.models.OutboundEvent

class CommandParser {
    // Настраиваем парсер JSON (игнорируем неизвестные поля, чтобы не крашить систему)
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Десериализация: превращаем сырой текст из WS в строгий объект
    fun parseInbound(rawText: String): InboundMessage? {
        return try {
            jsonConfig.decodeFromString<InboundMessage>(rawText)
        } catch (e: Exception) {
            null
        }
    }

    // Сериализация: превращаем событие игры в красивую JSON-строку для отправки
    fun createEventJson(eventName: String, data: Map<String, String>): String {
        val event = OutboundEvent(event = eventName, payload = data)
        return jsonConfig.encodeToString(event)
    }
}
