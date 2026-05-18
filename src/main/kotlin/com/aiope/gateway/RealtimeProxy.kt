package com.aiope.gateway

import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.servlet.WebSocketServlet
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory
import org.eclipse.jetty.websocket.servlet.WebSocketCreator
import okhttp3.*
import okio.ByteString
import com.google.gson.*
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * WebSocket proxy for realtime voice.
 * Client connects to wss://gateway/ws/voice?model=<model>
 * Gateway bridges to upstream provider (Google Live API, OpenAI Realtime, etc.)
 */
class RealtimeServlet : WebSocketServlet() {
    override fun configure(factory: WebSocketServletFactory) {
        factory.policy.maxTextMessageSize = 1024 * 1024 // 1MB for audio chunks
        factory.policy.maxBinaryMessageSize = 1024 * 1024
        factory.policy.idleTimeout = 600_000 // 10 min idle
        factory.creator = WebSocketCreator { req, _ ->
            val ctx = req.httpServletRequest.servletContext.getAttribute("gateway") as GatewayServer
            val auth = req.httpServletRequest.getHeader("Authorization")
            val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else
                req.httpServletRequest.getParameter("key")
            // Auth check
            if (bearer == null || (bearer != ctx.getGateway().getConfig().apiKey && ctx.findUser(bearer) == null)) {
                return@WebSocketCreator null // reject
            }
            val model = req.httpServletRequest.getParameter("model") ?: "gemini-3.1-flash-live-preview"
            RealtimeSocket(ctx, model, bearer)
        }
    }
}

/**
 * Per-client WebSocket session. Bridges client ↔ upstream provider.
 */
class RealtimeSocket(
    private val ctx: GatewayServer,
    private val model: String,
    private val userKey: String
) : WebSocketAdapter() {

    private var upstream: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WS
        .build()

    override fun onWebSocketConnect(sess: Session) {
        super.onWebSocketConnect(sess)
        ServerLog.add("Realtime WS connected: model=$model")

        // Resolve upstream URL based on model/provider
        val upstreamUrl = resolveUpstreamUrl(model)
        if (upstreamUrl == null) {
            sess.remote.sendString("""{"error":"No realtime provider for model: $model"}""")
            sess.close(1008, "No provider")
            return
        }

        // Connect to upstream
        val request = Request.Builder().url(upstreamUrl).build()
        upstream = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send setup message for Google Live API
                if (upstreamUrl.contains("generativelanguage.googleapis.com")) {
                    val setup = JsonObject().apply {
                        add("config", JsonObject().apply {
                            addProperty("model", "models/$model")
                            add("responseModalities", JsonArray().apply { add("AUDIO") })
                        })
                    }
                    webSocket.send(setup.toString())
                }
                ServerLog.add("Realtime upstream connected: $model")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Forward upstream → client, translating format
                try {
                    val clientMsg = translateFromUpstream(text)
                    if (clientMsg != null && isConnected) {
                        remote.sendString(clientMsg)
                    }
                } catch (e: Exception) {
                    ServerLog.add("Realtime upstream parse error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary passthrough
                if (isConnected) {
                    remote.sendBytes(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ServerLog.add("Realtime upstream failed: ${t.message}")
                if (isConnected) {
                    remote.sendString("""{"error":"Upstream disconnected: ${t.message}"}""")
                    session?.close(1011, "Upstream failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ServerLog.add("Realtime upstream closed: $code $reason")
                if (isConnected) session?.close(code, reason)
            }
        })
    }

    override fun onWebSocketText(message: String) {
        // Client → upstream, translate format
        try {
            val upstreamMsg = translateToUpstream(message)
            if (upstreamMsg != null) {
                upstream?.send(upstreamMsg)
            }
        } catch (e: Exception) {
            ServerLog.add("Realtime client parse error: ${e.message}")
        }
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        // Binary audio passthrough
        upstream?.send(ByteString.of(*payload.sliceArray(offset until offset + len)))
    }

    override fun onWebSocketClose(statusCode: Int, reason: String?) {
        super.onWebSocketClose(statusCode, reason)
        upstream?.close(1000, "Client disconnected")
        upstream = null
        ServerLog.add("Realtime WS closed: $statusCode")
    }

    override fun onWebSocketError(cause: Throwable) {
        ServerLog.add("Realtime WS error: ${cause.message}")
        upstream?.close(1011, "Client error")
    }

    /**
     * Resolve the upstream WebSocket URL for a given model.
     */
    private fun resolveUpstreamUrl(model: String): String? {
        val googleKey = ctx.getGateway().getConfig().providers
            .firstOrNull { it.name == "google-ai-studio" }?.apiKey
            ?: ctx.getApiKey("google")

        // Google Gemini Live API models
        val googleLiveModels = listOf(
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-preview-native-audio-dialog",
            "gemini-2.5-flash",
            "gemini-2.5-flash-native-audio"
        )
        if (googleLiveModels.any { model.contains(it) || model == it }) {
            if (googleKey.isNullOrEmpty()) return null
            return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$googleKey"
        }

        // OpenAI Realtime API
        val openaiKey = ctx.getGateway().getConfig().providers
            .firstOrNull { it.name.contains("openai") && it.apiKey.startsWith("sk-") }?.apiKey
        if (model.contains("gpt") && model.contains("realtime")) {
            if (openaiKey.isNullOrEmpty()) return null
            return "wss://api.openai.com/v1/realtime?model=$model"
        }

        return null
    }

    /**
     * Translate client message format → upstream provider format.
     * Client sends: {"audio":{"pcm":"base64...","sampleRate":16000}}
     * Google expects: {"realtimeInput":{"audio":{"data":"base64...","mimeType":"audio/pcm;rate=16000"}}}
     */
    private fun translateToUpstream(clientMsg: String): String? {
        val json = JsonParser.parseString(clientMsg).asJsonObject

        // Audio from client
        json.getAsJsonObject("audio")?.let { audio ->
            val pcm = audio.get("pcm")?.asString ?: return null
            val rate = audio.get("sampleRate")?.asInt ?: 16000
            return JsonObject().apply {
                add("realtimeInput", JsonObject().apply {
                    add("audio", JsonObject().apply {
                        addProperty("data", pcm)
                        addProperty("mimeType", "audio/pcm;rate=$rate")
                    })
                })
            }.toString()
        }

        // Text from client
        json.getAsJsonObject("text")?.let { text ->
            val content = text.get("content")?.asString ?: return null
            return JsonObject().apply {
                add("realtimeInput", JsonObject().apply {
                    addProperty("text", content)
                })
            }.toString()
        }

        // Turn end
        if (json.has("turnEnd")) {
            return """{"clientContent":{"turnComplete":true}}"""
        }

        // Passthrough unknown
        return clientMsg
    }

    /**
     * Translate upstream provider format → client format.
     * Google sends: {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"base64","mimeType":"audio/pcm"}}]}}}
     * Client expects: {"audio":{"pcm":"base64..."}} or {"text":{"delta":"..."}}
     */
    private fun translateFromUpstream(upstreamMsg: String): String? {
        val json = JsonParser.parseString(upstreamMsg).asJsonObject

        // Server content (audio/text from model)
        json.getAsJsonObject("serverContent")?.let { sc ->
            // Audio output
            sc.getAsJsonObject("modelTurn")?.getAsJsonArray("parts")?.forEach { part ->
                val p = part.asJsonObject
                p.getAsJsonObject("inlineData")?.let { inline ->
                    val data = inline.get("data")?.asString ?: return@forEach
                    return JsonObject().apply {
                        add("audio", JsonObject().apply { addProperty("pcm", data) })
                    }.toString()
                }
                // Text part
                p.get("text")?.asString?.let { text ->
                    return JsonObject().apply {
                        add("text", JsonObject().apply { addProperty("delta", text) })
                    }.toString()
                }
            }

            // Transcriptions
            sc.getAsJsonObject("inputTranscription")?.get("text")?.asString?.let {
                return """{"inputTranscription":"$it"}"""
            }
            sc.getAsJsonObject("outputTranscription")?.get("text")?.asString?.let {
                return """{"outputTranscription":"$it"}"""
            }

            // Turn complete
            if (sc.has("turnComplete") && sc.get("turnComplete").asBoolean) {
                return """{"turnComplete":true}"""
            }
        }

        // Tool calls
        json.getAsJsonObject("toolCall")?.let {
            return JsonObject().apply { add("toolCall", it) }.toString()
        }

        // Setup complete (ack)
        if (json.has("setupComplete")) {
            return """{"connected":true}"""
        }

        return null
    }
}
