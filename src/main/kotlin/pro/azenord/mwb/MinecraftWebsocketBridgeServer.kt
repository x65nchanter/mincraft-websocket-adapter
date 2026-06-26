
package pro.azenord.mwb

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

class MinecraftWebsocketBridgeServer : ModInitializer {
    private val logger = LoggerFactory.getLogger("minecraft-websocket-bridge")

    override fun onInitialize() {
        logger.info("[MinecraftWebsocketBridge] Серверная часть инициализирована.")
    }
}
