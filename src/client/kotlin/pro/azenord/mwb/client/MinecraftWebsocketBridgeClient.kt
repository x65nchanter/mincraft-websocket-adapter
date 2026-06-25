package pro.azenord.mwb.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.GlobalEventExecutor
import com.google.gson.Gson
import com.google.gson.JsonObject
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import org.slf4j.LoggerFactory

class MinecraftWebsocketBridgeClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("minecraft-websocket-bridge")
    private val gson = Gson()
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null

    companion object {
        // Потокобезопасный список всех активных WebSocket клиентов, подключенных к боту
        val allClients = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

        // Вспомогательный метод для быстрой отправки JSON-событий всем клиентам наружу
        fun broadcastEvent(action: String, payload: JsonObject) {
            val eventJson = JsonObject()
            eventJson.addProperty("event", action)
            eventJson.add("payload", payload)

            val frame = TextWebSocketFrame(Gson().toJson(eventJson))
            allClients.writeAndFlush(frame)
        }
    }

    override fun onInitializeClient() {
        logger.info("[HeadlessBot] Инициализация Netty WebSocket сервера на клиенте...")
        Thread { startNettyServer(8642) }.start()

        // ==============================================================================
        // РЕГИСТРАЦИЯ ИГРОВЫХ СОБЫТИЙ ДЛЯ ОТПРАВКИ НАРУЖУ (OUTBOUND)
        // ==============================================================================

        // СОБЫТИЕ 1: Успешное подключение к игровому серверу Майнкрафта
        ClientPlayConnectionEvents.JOIN.register { handler, sender, client ->
            client.execute {
                val payload = JsonObject()
                payload.addProperty("server_ip", client.currentServerEntry?.address ?: "local")
                payload.addProperty("player_name", client.player?.gameProfile?.name ?: "unknown")
                broadcastEvent("game_connected", payload)
            }
        }

        // СОБЫТИЕ 2: Отключение бота от игрового сервера
        ClientPlayConnectionEvents.DISCONNECT.register { handler, client ->
            client.execute {
                val payload = JsonObject()
                payload.addProperty("status", "disconnected")
                broadcastEvent("game_disconnected", payload)
            }
        }

        // СОБЫТИЕ 3: Перехват ЛЮБЫХ сообщений в игровом чате Майнкрафта
        // Сюда летят: сообщения от игроков, системные сообщения, команды, логи смертей
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            val payload = JsonObject()
            payload.addProperty("text", message.string) // Вытаскиваем чистый текст без цветовых кодов параграфов
            broadcastEvent("chat_message_received", payload)
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
            logger.info("[HeadlessBot] Netty WebSocket сервер успешно поднят на порту \$port")
            ch.closeFuture().sync()
        } catch (e: Exception) {
            logger.error("[HeadlessBot] Ошибка работы сервера Netty: \${e.message}")
        } finally {
            bossGroup?.shutdownGracefully()
            workerGroup?.shutdownGracefully()
        }
    }

    inner class WebSocketFrameHandler : SimpleChannelInboundHandler<TextWebSocketFrame>() {
        override fun handlerAdded(ctx: ChannelHandlerContext) {
            // Как только клиент (например, wscat или ваш Node.js скрипт) подключился к WS — запоминаем его
            allClients.add(ctx.channel())
            logger.info("[HeadlessBot] Новый WS-клиент подключен к мониторингу.")
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext) {
            logger.info("[HeadlessBot] WS-клиент отключился от мониторинга.")
        }

        override fun channelRead0(ctx: ChannelHandlerContext, frame: TextWebSocketFrame) {
            val msg = frame.text()
            try {
                val json = gson.fromJson(msg, JsonObject::class.java)
                val action = json.get("action")?.asString
                val client = MinecraftClient.getInstance()

                if (action == "connect") {
                    val payload = json.getAsJsonObject("payload")
                    val serverIp = payload.get("address").asString

                    client.execute {
                        val serverInfo = ServerInfo("TargetServer", serverIp, ServerInfo.ServerType.OTHER)
                        ConnectScreen.connect(null, client, ServerAddress.parse(serverIp), serverInfo, false, null)
                    }
                    ctx.channel().writeAndFlush(TextWebSocketFrame("{\"status\": \"connecting\"}"))
                }
                else if (action == "chat") {
                    val payload = json.getAsJsonObject("payload")
                    val text = payload.get("message").asString

                    client.execute {
                        val networkHandler = client.networkHandler
                        if (networkHandler != null && client.player != null) {
                            if (text.startsWith("/")) {
                                networkHandler.sendChatCommand(text.substring(1))
                            } else {
                                networkHandler.sendChatMessage(text)
                            }
                            ctx.channel().writeAndFlush(TextWebSocketFrame("{\"status\": \"sent\", \"message\": \"\$text\"}"))
                        } else {
                            ctx.channel().writeAndFlush(TextWebSocketFrame("{\"error\": \"Player is not connected to any server\"}"))
                        }
                    }
                }
            } catch (e: Exception) {
                ctx.channel().writeAndFlush(TextWebSocketFrame("{\"error\": \"\${e.message}\"}"))
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }
    }
}
