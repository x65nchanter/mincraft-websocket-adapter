package pro.azenord.mwb.client

import com.google.gson.Gson
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.GlobalEventExecutor
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import org.slf4j.LoggerFactory
import pro.azenord.mwb.core.CommandParser
import net.fabricmc.loader.api.FabricLoader
import baritone.api.BaritoneAPI
import baritone.api.event.events.BlockChangeEvent
import baritone.api.event.events.BlockInteractEvent
import baritone.api.event.events.ChatEvent
import baritone.api.event.events.ChunkEvent
import baritone.api.event.events.PacketEvent
import baritone.api.event.events.PathEvent
import baritone.api.event.events.PlayerUpdateEvent
import baritone.api.event.events.RenderEvent
import baritone.api.event.events.RotationMoveEvent
import baritone.api.event.events.SprintStateEvent
import baritone.api.event.events.TabCompleteEvent
import baritone.api.event.events.TickEvent
import baritone.api.event.events.WorldEvent
import baritone.api.event.listener.IGameEventListener

class MinecraftWebsocketBridgeClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("minecraft-websocket-bridge")
    private val parser = CommandParser() // Инициализируем наш строго типизированный парсер
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null

    companion object {
        private val allClients = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        private val parserForBroadcast = CommandParser()

        // Потокобезопасная отправка событий из игры наружу через наш core-logic
        fun broadcastEvent(eventName: String, data: Map<String, String>) {
            val jsonString = parserForBroadcast.createEventJson(eventName, data)
            allClients.writeAndFlush(TextWebSocketFrame(jsonString))
        }
    }

    override fun onInitializeClient() {
        logger.info("[MinecraftWebsocketBridge] Запуск сетевого моста на базе core-logic...")
        Thread { startNettyServer(8642) }.start()

        // ==============================================================================
        // ОТПРАВКА СОБЫТИЙ ИГРЫ НАРУЖУ (OUTBOUND)
        // ==============================================================================

        ClientPlayConnectionEvents.JOIN.register { handler, sender, client ->
            client.execute {
                broadcastEvent(
                    "game_connected", mapOf(
                        "server_ip" to (client.currentServerEntry?.address ?: "local"),
                        "player_name" to (client.player?.gameProfile?.name ?: "unknown")
                    )
                )

                // ------------------------------------------------------------------
                // РЕГИСТРАЦИЯ ИИ-СОБЫТИЙ BARITONE
                // ------------------------------------------------------------------
                try {
                    val primaryBaritone = BaritoneAPI.getProvider().getPrimaryBaritone()

                    primaryBaritone.gameEventHandler.registerEventListener(object : IGameEventListener {

                        // 🎯 НАШЕ ГЛАВНОЕ СОБЫТИЕ: Отслеживание движения ИИ
                        override fun onPathEvent(event: PathEvent) {
                            val currentGoal = primaryBaritone.customGoalProcess.goal
                            val goalInfo = if (currentGoal != null) {
                                mapOf("type" to currentGoal.javaClass.simpleName, "info" to currentGoal.toString())
                            } else {
                                mapOf("type" to "none")
                            }
                            mapOf("error" to currentGoal.javaClass.simpleName, "info" to "No path found")
                            when (event) {
                                PathEvent.AT_GOAL -> {
                                    broadcastEvent("baritone_status", mapOf("state" to "arrived") + goalInfo)
                                }
                                PathEvent.CALC_STARTED -> {
                                    broadcastEvent("baritone_status", mapOf("state" to "pathing_started") + goalInfo)
                                }
                                PathEvent.CALC_FAILED -> {
                                    broadcastEvent("baritone_status", mapOf("state" to "calculation_failed", "error" to "No path found"))
                                }
                                PathEvent.CANCELED -> {
                                    broadcastEvent("baritone_status", mapOf("state" to "canceled"))
                                }
                                else -> { /* Игнорируем промежуточные шаги типа CALC_STARTED */ }
                            }
                        }

                        // 📝 ОСТАЛЬНЫЕ МЕТОДЫ ИНТЕРФЕЙСА: Оставляем пустыми, чтобы не спамить код
                        override fun onTick(event: TickEvent?) {}
                        override fun onPostTick(event: TickEvent?) {}
                        override fun onPlayerUpdate(event: PlayerUpdateEvent?) {}
                        override fun onSendChatMessage(event: ChatEvent?) {}
                        override fun onPreTabComplete(event: TabCompleteEvent?) {}
                        override fun onChunkEvent(event: ChunkEvent?) {}
                        override fun onBlockChange(event: BlockChangeEvent?) {}
                        override fun onRenderPass(event: RenderEvent?) {}
                        override fun onWorldEvent(event: WorldEvent?) {}
                        override fun onSendPacket(event: PacketEvent?) {}
                        override fun onReceivePacket(event: PacketEvent?) {}
                        override fun onPlayerRotationMove(event: RotationMoveEvent?) {}
                        override fun onPlayerSprintState(event: SprintStateEvent?) {}
                        override fun onBlockInteract(event: BlockInteractEvent?) {}
                        override fun onPlayerDeath() {}
                    })
                    logger.info("[MinecraftWebsocketBridge] Обработчик событий Baritone успешно зарегистрирован.")
                } catch (e: Exception) {
                    logger.error("[MinecraftWebsocketBridge] Ошибка подписки на Baritone: ${e.message}")
                }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { handler, client ->
            client.execute {
                broadcastEvent("game_disconnected", mapOf("status" to "disconnected"))
            }
        }

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            broadcastEvent("chat_message_received", mapOf("text" to message.string))
        }
    }

    private fun startNettyServer(port: Int) {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()
        try {
            val b = ServerBootstrap()
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()
                        p.addLast(HttpServerCodec())
                        p.addLast(HttpObjectAggregator(65536))
                        p.addLast(WebSocketServerProtocolHandler("/"))
                        p.addLast(WebSocketFrameHandler())
                    }
                })

            val ch = b.bind(port).sync().channel()
            logger.info("[MinecraftWebsocketBridge] WebSocket поднят на порту $port")
            ch.closeFuture().sync()
        } catch (e: Exception) {
            logger.error("[MinecraftWebsocketBridge] Сбой Netty: ${e.message}")
        } finally {
            bossGroup?.shutdownGracefully()
            workerGroup?.shutdownGracefully()
        }
    }

    inner class WebSocketFrameHandler : SimpleChannelInboundHandler<TextWebSocketFrame>() {
        override fun handlerAdded(ctx: ChannelHandlerContext) {
            allClients.add(ctx.channel())
        }

        override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
            val rawText = frame.text()

            // КРИТИЧЕСКИЙ ШАГ: Отдаем сырую строку в core-logic и на выходе получаем строго типизированный объект
            val message = parser.parseInbound(rawText)
            if (message == null) {
                ctx.channel()
                    .writeAndFlush(TextWebSocketFrame("{\"error\": \"Malformed or invalid JSON protocol structure\"}"))
                return
            }

            val client = MinecraftClient.getInstance()

            // ИСПОЛНЕНИЕ КОМАНДЫ 1: Подключение к серверу
            if (message.action == "connect") {
                val serverIp = message.payload["address"] ?: return
                client.execute {
                    val serverInfo = ServerInfo("TargetServer", serverIp, ServerInfo.ServerType.OTHER)
                    ConnectScreen.connect(null, client, ServerAddress.parse(serverIp), serverInfo, false, null)
                }
                ctx.channel().writeAndFlush(TextWebSocketFrame("{\"status\": \"connecting\"}"))
            }

            // ИСПОЛНЕНИЕ КОМАНДЫ 2: Чат / Команды Baritone
            else if (message.action == "chat") {
                val text = message.payload["message"] ?: return
                client.execute {
                    val networkHandler = client.networkHandler
                    if (networkHandler != null && client.player != null) {
                        if (text.startsWith("/")) {
                            networkHandler.sendChatCommand(text.substring(1))
                        } else {
                            networkHandler.sendChatMessage(text)
                        }
                        ctx.channel()
                            .writeAndFlush(TextWebSocketFrame("{\"status\": \"sent\", \"message\": \"$text\"}"))
                    } else {
                        ctx.channel()
                            .writeAndFlush(TextWebSocketFrame("{\"error\": \"Player is not connected to any server\"}"))
                    }
                }
            }

            else if (message.action == "mods") {
                // Получаем все моды через официальный API Fabric Loader
                val modsList = FabricLoader.getInstance().allMods.map { mod ->
                    mapOf(
                        "id" to mod.metadata.id,
                        "version" to mod.metadata.version.friendlyString,
                        "name" to mod.metadata.name
                    )
                }
                val responseJson = Gson().toJson(mapOf("status" to "success", "mods" to modsList))
                ctx.channel().writeAndFlush(TextWebSocketFrame(responseJson))
            }

            else if (message.action == "baritone") {
                val commandText = message.payload["command"] ?: return
                client.execute {
                    if (client.player != null) {
                        try {
                            // Вызываем встроенную систему команд Baritone напрямую через его API
                            val primaryBaritone = BaritoneAPI.getProvider().primaryBaritone
                            primaryBaritone.commandManager.execute(commandText)

                            ctx.channel().writeAndFlush(TextWebSocketFrame("{\"status\": \"baritone_executing\", \"command\": \"$commandText\"}"))
                        } catch (e: Exception) {
                            ctx.channel().writeAndFlush(TextWebSocketFrame("{\"error\": \"Baritone failed to execute command: ${e.message}\"}"))
                        }
                    } else {
                        ctx.channel().writeAndFlush(TextWebSocketFrame("{\"error\": \"Player is not in game\"}"))
                    }
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }
    }
}
