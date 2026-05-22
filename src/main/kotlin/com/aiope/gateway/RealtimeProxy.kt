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
            val ctx = req.httpServletRequest.servletContext.getAttribute("gateway") as? GatewayServer
            if (ctx == null) {
                System.err.println("[WS] No gateway context found")
                return@WebSocketCreator null
            }
            val auth = req.httpServletRequest.getHeader("Authorization")
            val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else
                req.httpServletRequest.getParameter("key")
            // Auth check
            if (bearer == null || (bearer != ctx.getGateway().getConfig().apiKey && ctx.findUser(bearer) == null)) {
                System.err.println("[WS] Auth failed for bearer: ${bearer?.take(10)}")
                return@WebSocketCreator null
            }
            val model = req.httpServletRequest.getParameter("model") ?: "gemini-3.1-flash-live-preview"
            val systemPrompt = req.httpServletRequest.getParameter("system") ?: ""
            System.err.println("[WS] Creating RealtimeSocket for model=$model")
            RealtimeSocket(ctx, model, bearer, systemPrompt)
        }
    }
}

/**
 * Per-client WebSocket session. Bridges client ↔ upstream provider.
 */
class RealtimeSocket(
    private val ctx: GatewayServer,
    private val model: String,
    private val userKey: String,
    private var systemPrompt: String = ""
) : WebSocketAdapter() {

    private var upstream: WebSocket? = null
    private var upstreamReady = false
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WS
        .build()

    override fun onWebSocketConnect(sess: Session) {
        super.onWebSocketConnect(sess)
        System.err.println("[WS] onWebSocketConnect model=$model systemPrompt=${systemPrompt.length} chars")

        // If system prompt was provided in URL, connect immediately
        if (systemPrompt.isNotBlank()) {
            connectUpstream()
        }
        // Otherwise wait for first message with {"setup":{"systemPrompt":"..."}}
    }

    private fun connectUpstream() {
        // Resolve upstream URL based on model/provider
        val upstreamUrl = resolveUpstreamUrl(model)
        if (upstreamUrl == null) {
            remote.sendString("""{"error":"No realtime provider for model: $model"}""")
            session?.close(1008, "No provider")
            return
        }
        System.err.println("[WS] Connecting upstream: ${upstreamUrl.take(100)}")

        // Connect to upstream
        val request = Request.Builder().url(upstreamUrl).build()
        upstream = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                System.err.println("[WS] Upstream onOpen, sending setup")
                upstreamReady = true
                // Send setup message for Google Live API
                if (upstreamUrl.contains("generativelanguage.googleapis.com")) {
                    val modelName = model.substringAfter("/").let {
                        if (it.startsWith("models-")) it.removePrefix("models-") else it
                    }
                    val setup = JsonObject().apply {
                        add("setup", JsonObject().apply {
                            addProperty("model", "models/$modelName")
                            add("generationConfig", JsonObject().apply {
                                add("responseModalities", JsonArray().apply { add("AUDIO") })
                                add("speechConfig", JsonObject().apply {
                                    add("voiceConfig", JsonObject().apply {
                                        add("prebuiltVoiceConfig", JsonObject().apply {
                                            addProperty("voiceName", "Aoede")
                                        })
                                    })
                                })
                            })
                            if (systemPrompt.isNotBlank()) {
                                add("systemInstruction", JsonObject().apply {
                                    add("parts", JsonArray().apply {
                                        add(JsonObject().apply { addProperty("text", systemPrompt) })
                                    })
                                })
                            }
                            add("tools", JsonArray().apply {
                                add(JsonObject().apply {
                                    add("functionDeclarations", buildToolDeclarations())
                                })
                            })
                        })
                    }
                    webSocket.send(setup.toString())
                    System.err.println("[WS] Setup sent: ${setup.toString().take(200)}")
                }
                ServerLog.add("Realtime upstream connected: $model")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Forward upstream → client, translating format
                System.err.println("[WS] Upstream raw: ${text.take(100)}")
                try {
                    val clientMsg = translateFromUpstream(text)
                    System.err.println("[WS] Translated: ${clientMsg?.take(100)}")
                    if (clientMsg != null && isConnected) {
                        remote.sendString(clientMsg)
                    }
                } catch (e: Exception) {
                    System.err.println("[WS] Translate error: ${e.message}")
                    ServerLog.add("Realtime upstream parse error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Google sends all messages as binary frames
                val text = bytes.utf8()
                try {
                    val clientMsg = translateFromUpstream(text)
                    if (clientMsg != null && isConnected) {
                        remote.sendString(clientMsg)
                    }
                } catch (e: Exception) {
                    if (isConnected) {
                        remote.sendBytes(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                System.err.println("[WS] Upstream FAILED: ${t.message} resp=${response?.code}")
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
        // Handle setup message (system prompt sent after connection)
        if (!upstreamReady) {
            try {
                val json = JsonParser.parseString(message).asJsonObject
                json.getAsJsonObject("setup")?.let { setup ->
                    setup.get("systemPrompt")?.asString?.let { systemPrompt = it }
                    connectUpstream()
                    return
                }
            } catch (_: Exception) {}
            // If no setup message and upstream not connected, connect with empty prompt
            if (upstream == null) connectUpstream()
        }
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
            "gemini-2.5-flash-native-audio-latest",
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
                add("clientContent", JsonObject().apply {
                    add("turns", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            add("parts", JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", content) })
                            })
                        })
                    })
                    addProperty("turnComplete", true)
                })
            }.toString()
        }

        // Turn end
        if (json.has("turnEnd")) {
            return """{"clientContent":{"turnComplete":true}}"""
        }

        // Tool response from client
        json.getAsJsonObject("toolResponse")?.let { tr ->
            return JsonObject().apply {
                add("toolResponse", JsonObject().apply {
                    add("functionResponses", tr.getAsJsonArray("functionResponses"))
                })
            }.toString()
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
            val results = mutableListOf<String>()

            // Audio output
            sc.getAsJsonObject("modelTurn")?.getAsJsonArray("parts")?.forEach { part ->
                val p = part.asJsonObject
                p.getAsJsonObject("inlineData")?.let { inline ->
                    val data = inline.get("data")?.asString ?: return@forEach
                    results.add(JsonObject().apply {
                        add("audio", JsonObject().apply { addProperty("pcm", data) })
                    }.toString())
                }
                // Text part
                p.get("text")?.asString?.let { text ->
                    results.add(JsonObject().apply {
                        add("text", JsonObject().apply { addProperty("delta", text) })
                    }.toString())
                }
            }

            // Transcriptions
            sc.getAsJsonObject("inputTranscription")?.get("text")?.asString?.let {
                results.add(JsonObject().apply { addProperty("inputTranscription", it) }.toString())
            }
            sc.getAsJsonObject("outputTranscription")?.get("text")?.asString?.let {
                results.add(JsonObject().apply { addProperty("outputTranscription", it) }.toString())
            }

            // Turn complete
            if (sc.has("turnComplete") && sc.get("turnComplete").asBoolean) {
                results.add("""{"turnComplete":true}""")
            }
            if (sc.has("generationComplete")) {
                // skip
            }

            // Send all results
            if (results.size > 1 && isConnected) {
                // Send all but last directly, return last
                for (i in 0 until results.size - 1) {
                    remote.sendString(results[i])
                }
                return results.last()
            }
            return results.firstOrNull()
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

    private fun handleToolCall(upstream: WebSocket, toolCall: JsonObject) {
        val fcs = toolCall.getAsJsonArray("functionCalls") ?: return
        val responses = JsonArray()
        for (i in 0 until fcs.size()) {
            val fc = fcs[i].asJsonObject
            val name = fc.get("name").asString
            val id = fc.get("id").asString
            val args = fc.getAsJsonObject("args") ?: JsonObject()
            System.err.println("[WS] Tool call: $name($args)")
            val result = executeTool(name, args)
            responses.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("id", id)
                add("response", JsonObject().apply { addProperty("result", result) })
            })
        }
        val response = JsonObject().apply {
            add("toolResponse", JsonObject().apply {
                add("functionResponses", responses)
            })
        }
        upstream.send(response.toString())
    }

    private fun executeTool(name: String, args: JsonObject): String {
        return try {
            when (name) {
                "fetch_url" -> {
                    val url = args.get("url")?.asString ?: return "Error: no url"
                    val req = Request.Builder().url(url).build()
                    val resp = http.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    // Strip HTML tags for cleaner output
                    body.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .take(4000)
                }
                "query_data" -> {
                    val category = args.get("category")?.asString ?: return "Error: no category"
                    val extra = args.get("extra")?.asString ?: ""
                    val apiKeys = ctx.getApiKeys()
                    val url = buildDataUrl(category, extra, apiKeys)
                    if (url.isBlank()) return "Unknown category: $category"
                    val req = Request.Builder().url(url).build()
                    val resp = http.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    body.take(4000)
                }
                "search_location" -> {
                    val query = args.get("query")?.asString ?: return "Error: no query"
                    val key = ctx.getApiKeys()["geoapify"] ?: ""
                    if (key.isBlank()) return "Error: no geoapify key"
                    val url = "https://api.geoapify.com/v1/geocode/search?text=${java.net.URLEncoder.encode(query, "UTF-8")}&apiKey=$key"
                    val req = Request.Builder().url(url).build()
                    val resp = http.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    body.take(4000)
                }
                else -> "Tool '$name' not available server-side"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun buildDataUrl(category: String, extra: String, keys: Map<String, String>): String {
        return when (category) {
            "weather" -> "https://api.open-meteo.com/v1/forecast?latitude=43.6&longitude=-116.2&current_weather=true"
            "news" -> "https://newsdata.io/api/1/news?apikey=${keys["newsdata"] ?: ""}&language=en&q=${java.net.URLEncoder.encode(extra.ifBlank { "top" }, "UTF-8")}"
            "stocks" -> "https://query1.finance.yahoo.com/v8/finance/chart/${extra.ifBlank { "SPY" }}?interval=1d&range=1d"
            "crypto" -> "https://api.coingecko.com/api/v3/simple/price?ids=${extra.ifBlank { "bitcoin" }}&vs_currencies=usd&include_24hr_change=true"
            else -> ""
        }
    }

    private fun buildToolDeclarations(): JsonArray {
        val decls = JsonArray()
        fun tool(name: String, desc: String, params: JsonObject) {
            decls.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("parameters", params)
            })
        }
        fun obj(vararg props: Pair<String, String>, required: List<String> = props.map { it.first }): JsonObject {
            return JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    props.forEach { (k, desc) -> add(k, JsonObject().apply { addProperty("type", "string"); addProperty("description", desc) }) }
                })
                add("required", JsonArray().apply { required.forEach { add(it) } })
            }
        }
        // Filesystem
        tool("run_sh", "Execute shell command on the Android device", obj("command" to "Shell command"))
        tool("run_proot", "Execute command in Alpine Linux proot environment", obj("command" to "Command"))
        tool("read_file", "Read file contents", obj("path" to "File path"))
        tool("write_file", "Write file", obj("path" to "File path", "content" to "File content"))
        tool("list_directory", "List directory contents", obj("path" to "Directory path"))
        // System
        tool("get_location", "Get device GPS location", obj(required = emptyList()))
        tool("open_intent", "Open URL, app, or intent on device", obj("uri" to "URI to open"))
        tool("device_info", "Get device battery, storage, RAM, network info", obj(required = emptyList()))
        // Data/Web
        tool("fetch_url", "Fetch a URL and return text content", obj("url" to "URL to fetch"))
        tool("query_data", "Query live data: weather, news, stocks, crypto, etc", obj("category" to "Data category", "extra" to "Optional param", required = listOf("category")))
        tool("search_location", "Search for places, addresses, businesses", obj("query" to "Search query"))
        tool("search_web", "Search the web for current information", obj("query" to "Search query"))
        // Calendar/Contacts
        tool("read_calendar", "Read upcoming calendar events", obj("days" to "Days ahead to look", required = emptyList()))
        tool("create_event", "Create a calendar event", obj("title" to "Event title", "start_time" to "Start time", "end_time" to "End time", "location" to "Location", required = listOf("title")))
        tool("read_contacts", "Search or list contacts", obj("query" to "Name to search", required = emptyList()))
        // SMS
        tool("read_sms", "Read recent SMS messages", obj("limit" to "Number of messages", required = emptyList()))
        tool("send_sms", "Send an SMS message", obj("to" to "Phone number", "body" to "Message text"))
        // Alarms/Notifications
        tool("set_alarm", "Set an alarm", obj("hour" to "Hour 0-23", "minutes" to "Minutes 0-59", "message" to "Label", required = listOf("hour", "minutes")))
        tool("send_notification", "Post a notification/reminder", obj("title" to "Title", "body" to "Text", required = listOf("body")))
        // Memory
        tool("memory_store", "Remember a fact for future conversations", obj("key" to "Short key", "content" to "Fact to remember"))
        tool("memory_recall", "Search stored memories", obj("query" to "Search term"))
        // Media
        tool("media_control", "Control media playback", obj("action" to "play_pause, next, previous, or stop"))
        // Clipboard
        tool("clipboard_copy", "Copy text to clipboard", obj("text" to "Text to copy"))
        tool("clipboard_read", "Read clipboard contents", obj(required = emptyList()))
        // SSH
        tool("ssh_start", "Open persistent SSH session to a remote server", obj("server" to "Server name or ID"))
        tool("ssh_exec", "Execute command on active remote SSH session", obj("server" to "Server name or ID", "command" to "Shell command"))
        tool("ssh_exit", "Close an active SSH session", obj("server" to "Server name or ID"))
        // Browser
        tool("browser_navigate", "Navigate in-app browser to a URL", obj("url" to "URL to navigate to"))
        tool("browser_content", "Get current page text, URL, and title", obj(required = emptyList()))
        tool("browser_elements", "List interactive elements with their CSS selectors", obj(required = emptyList()))
        tool("browser_click", "Click an element in the browser. Use the CSS selector from browser_elements.", obj("selector" to "CSS selector of element to click"))
        tool("browser_fill", "Type text into an input field in the browser. Use the CSS selector from browser_elements.", obj("selector" to "CSS selector of input element", "value" to "Text to type"))
        tool("browser_eval", "Execute JavaScript in the browser", obj("script" to "JavaScript code"))
        tool("browser_back", "Go back in browser history", obj(required = emptyList()))
        tool("browser_scroll", "Scroll the page up or down", obj("direction" to "up or down"))
        tool("browser_open", "Open the browser panel", obj(required = emptyList()))
        tool("browser_close", "Close the browser panel", obj(required = emptyList()))
        tool("browser_maximize", "Maximize or restore browser panel", obj("maximize" to "true to maximize, false to restore"))
        // Image
        tool("image_generate", "Generate an image from a text prompt", obj("prompt" to "Image generation prompt"))
        tool("analyze_image", "Analyze an image from URL or file path", obj("url" to "URL or file path", "question" to "What to look for"))
        tool("search_images", "Search for images on the web", obj("query" to "Image search query"))
        // Delete/dismiss
        tool("delete_event", "Delete a calendar event by ID", obj("event_id" to "Event ID from read_calendar"))
        tool("delete_sms", "Delete an SMS by ID", obj("sms_id" to "SMS ID from read_sms"))
        tool("dismiss_alarm", "Dismiss an alarm by label", obj("message" to "Alarm label"))
        tool("memory_forget", "Delete a memory by key", obj("key" to "Memory key to delete"))
        // Subagent
        tool("task", "Spawn async subagent for background research", obj("description" to "Short description", "prompt" to "Detailed instructions"))
        return decls
    }
}
