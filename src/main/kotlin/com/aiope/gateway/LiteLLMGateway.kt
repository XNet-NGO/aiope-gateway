package com.aiope.gateway

import kotlinx.coroutines.*
import com.google.gson.*
import com.github.benmanes.caffeine.cache.Caffeine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ProviderConfig(
    val name: String,
    val model: String,
    val apiKey: String,
    val apiBase: String = "",
    val path: String = "/chat/completions",
    val weight: Int = 1,
    val enabled: Boolean = true,
    val displayId: String = "",
    val cachedModels: List<String> = emptyList()
)

data class GatewayConfig(
    val apiKey: String = "aiope-gateway-key",
    val providers: List<ProviderConfig> = emptyList(),
    val routingStrategy: String = "direct",
    val fallbackEnabled: Boolean = false,
    val blocked: Boolean = false
)

data class APIError(
    val errorMessage: String,
    val code: Int = 500,
    val type: String = "api_error"
) : Exception(errorMessage) {
    fun toJson(): JsonObject = JsonObject().apply {
        add("error", JsonObject().apply {
            addProperty("message", errorMessage)
            addProperty("type", type)
            addProperty("code", code)
        })
    }
}

class LiteLLMGateway(
    private val configFile: File,
    private val onLog: (String) -> Unit = {}
) {
    private var config = GatewayConfig()
    private var enabledProviders: List<ProviderConfig> = emptyList()
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val http = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val httpMedia = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON_MT = "application/json".toMediaType()
    private val cache = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(java.time.Duration.ofMinutes(30))
        .build<String, String>()
    @Volatile var cacheEnabled = true
    var cacheHits = 0L; var cacheMisses = 0L

    private fun cacheKey(model: String, messages: JsonElement?, extra: String = ""): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(model.toByteArray())
        messages?.let { md.update(it.toString().toByteArray()) }
        if (extra.isNotEmpty()) md.update(extra.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun getCacheStats(): Map<String, Any> = mapOf("size" to cache.estimatedSize(), "hits" to cacheHits, "misses" to cacheMisses, "enabled" to cacheEnabled)
    fun clearCache() { cache.invalidateAll(); cacheHits = 0; cacheMisses = 0 }

    init { loadConfig() }

    fun loadConfig() {
        if (!configFile.exists()) {
            config = defaultConfig()
            saveConfig()
        } else {
            try {
                config = parseConfigJson(configFile.readText())
                log("Loaded config: ${config.providers.size} providers")
            } catch (e: Exception) {
                log("Config parse error: ${e.message}")
                config = defaultConfig()
            }
        }
        enabledProviders = config.providers.filter { it.enabled }
    }

    fun saveConfig() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(configToJson(config)))
        } catch (e: Exception) {
            log("Save failed: ${e.message}")
        }
    }

    fun getConfig(): GatewayConfig = config
    fun getProviders(): List<ProviderConfig> = enabledProviders
    fun getAllProviders(): List<ProviderConfig> = config.providers
    fun isBlocked(): Boolean = config.blocked

    fun setBlocked(blocked: Boolean) {
        config = config.copy(blocked = blocked)
        saveConfig()
    }

    fun setConfig(newConfig: GatewayConfig) {
        config = newConfig
        enabledProviders = config.providers.filter { it.enabled }
        saveConfig()
    }

    fun enableProvider(name: String) = updateProvider(name) { it.copy(enabled = true) }
    fun disableProvider(name: String) = updateProvider(name) { it.copy(enabled = false) }
    fun setProviderWeight(name: String, weight: Int) = updateProvider(name) { it.copy(weight = weight) }
    fun setProviderApiKey(name: String, apiKey: String) = updateProvider(name) { it.copy(apiKey = apiKey) }
    fun setProviderApiBase(name: String, apiBase: String) = updateProvider(name) { it.copy(apiBase = apiBase) }
    fun setProviderDisplayId(name: String, displayId: String) = updateProvider(name) { it.copy(displayId = displayId) }
    fun setProviderPath(name: String, path: String) = updateProvider(name) { it.copy(path = path) }
    fun setCachedModels(name: String, models: List<String>) = updateProvider(name) { it.copy(cachedModels = models) }

    fun addProvider(provider: ProviderConfig) {
        config = config.copy(providers = config.providers + provider)
        enabledProviders = config.providers.filter { it.enabled }
        saveConfig()
    }

    fun removeProvider(name: String) {
        config = config.copy(providers = config.providers.filter { it.name != name })
        enabledProviders = config.providers.filter { it.enabled }
        saveConfig()
    }

    fun setProviderModel(name: String, model: String) = updateProvider(name) { p ->
        val prefix = if (p.displayId.contains("/")) p.displayId.substringBefore("/") else p.name
        // Strip provider prefix if someone passes the displayId instead of the model
        val cleanModel = if (model.startsWith("$prefix/")) model.removePrefix("$prefix/") else model
        // For Google models, keep the models/ prefix as the actual API model name
        val actualModel = if (p.apiBase.contains("generativelanguage.googleapis.com") && !cleanModel.startsWith("models/") && !cleanModel.contains("/")) "models/$cleanModel" else cleanModel
        val shortName = actualModel.removePrefix("models/").replace("/", "-")
        p.copy(model = actualModel, displayId = "$prefix/$shortName")
    }

    fun setGatewayApiKey(apiKey: String) {
        config = config.copy(apiKey = apiKey)
        saveConfig()
    }

    suspend fun loadProviderModels(provider: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val apiBase = provider.apiBase.ifEmpty { "https://api.openai.com/v1" }

        if (apiBase.contains("api.cline.bot")) {
            log("Loaded 3 models from ${provider.name} (static/free)")
            return@withContext listOf("kwaipilot/kat-coder-pro", "minimax/minimax-m2.5", "z-ai/glm-5")
        }

        if (apiBase.contains("bedrock-runtime") && apiBase.contains("amazonaws.com")) {
            log("Loaded bedrock-runtime models from ${provider.name} (static)")
            return@withContext listOf(
                "openai.gpt-oss-20b-1:0", "openai.gpt-oss-120b-1:0",
                "openai.gpt-oss-safeguard-20b", "openai.gpt-oss-safeguard-120b",
                "deepseek.v3.2", "deepseek.v3.1", "deepseek.r1-v1:0",
                "mistral.mistral-large-3-675b-instruct", "mistral.magistral-small-2509", "mistral.devstral-2-123b",
                "mistral.ministral-3-8b-instruct", "mistral.ministral-3-14b-instruct", "mistral.ministral-3-3b-instruct",
                "minimax.minimax-m2.5", "minimax.minimax-m2.1", "minimax.minimax-m2",
                "moonshotai.kimi-k2.5", "moonshotai.kimi-k2-thinking",
                "nvidia.nemotron-super-3-120b", "nvidia.nemotron-nano-3-30b",
                "nvidia.nemotron-nano-9b-v2", "nvidia.nemotron-nano-12b-v2",
                "qwen.qwen3-235b-a22b-2507", "qwen.qwen3-vl-235b-a22b-instruct",
                "qwen.qwen3-coder-480b-a35b-instruct", "qwen.qwen3-coder-30b-a3b-instruct", "qwen.qwen3-coder-next",
                "qwen.qwen3-32b", "qwen.qwen3-next-80b-a3b-instruct",
                "google.gemma-3-4b-it", "google.gemma-3-12b-it", "google.gemma-3-27b-it",
                "zai.glm-5", "zai.glm-4.7", "zai.glm-4.7-flash", "zai.glm-4.6",
                "writer.palmyra-vision-7b"
            )
        }

        if (apiBase.contains("text.pollinations.ai")) {
            log("Loaded 3 models from ${provider.name} (static/free)")
            return@withContext listOf("openai", "openai-large", "openai-fast")
        }

        if (apiBase.contains("gen.pollinations.ai")) {
            if (provider.apiKey.isEmpty()) throw APIError("API key required for gen.pollinations.ai", 400)
            // gen.pollinations.ai supports standard /models endpoint, fall through to fetch
        }

        if (provider.apiKey.isEmpty()) {
            throw APIError("API key required to load models", 400)
        }

        val modelsUrl = when {
            apiBase.contains("models.github.ai") || apiBase.contains("models.inference.ai.azure.com") ->
                "https://models.github.ai/catalog/models"
            apiBase.contains("cloudflare.com") -> {
                val acct = Regex("accounts/([^/]+)").find(apiBase)?.groupValues?.get(1) ?: ""
                "https://api.cloudflare.com/client/v4/accounts/$acct/ai/models/search"
            }
            else -> "$apiBase/models"
        }

        val req = Request.Builder().url(modelsUrl)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .get().build()

        val resp = http.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw APIError("Failed to load models: HTTP ${resp.code} - ${body.take(200)}", resp.code)

        val models = mutableListOf<String>()
        try {
            when {
                modelsUrl.contains("catalog/models") -> {
                    Regex(""""id"\s*:\s*"([^"]+)"""").findAll(body).forEach { m ->
                        val id = m.groupValues[1]; if (!id.startsWith("azureml://")) models.add(id)
                    }
                }
                modelsUrl.contains("cloudflare.com") -> {
                    Regex(""""name"\s*:\s*"(@[^"]+)"""").findAll(body).forEach { models.add(it.groupValues[1]) }
                }
                else -> {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonArray("data")?.forEach { elem ->
                        elem.asJsonObject.get("id")?.asString?.let { models.add(it) }
                    }
                }
            }
        } catch (e: Exception) { throw APIError("Failed to parse models response: ${e.message}", 500) }

        log("Loaded ${models.size} models from ${provider.name}")
        models
    }

    private fun updateProvider(name: String, transform: (ProviderConfig) -> ProviderConfig) {
        config = config.copy(providers = config.providers.map {
            if (it.name == name) transform(it) else it
        })
        enabledProviders = config.providers.filter { it.enabled }
        saveConfig()
    }

    fun resolveProviderDirect(provider: ProviderConfig): ProviderConfig {
        if (!provider.name.contains("/")) return provider
        val parentName = provider.name.substringBefore("/")
        val parent = config.providers.firstOrNull { it.name == parentName } ?: return provider
        return provider.copy(
            apiKey = if (provider.apiKey.isEmpty()) parent.apiKey else provider.apiKey,
            apiBase = if (provider.apiBase.isEmpty() || !provider.apiBase.startsWith("http")) parent.apiBase else provider.apiBase
        )
    }
    fun httpClient() = http

    fun resolveProvider(requestedModel: String): ProviderConfig {
        val provider = config.providers.firstOrNull {
            it.enabled && (it.displayId == requestedModel || it.model == requestedModel)
        } ?: throw APIError("No provider for model: $requestedModel", 404)
        // Sub-providers inherit parent's API key and base if their own is empty
        val resolved = if (provider.name.contains("/")) {
            val parentName = provider.name.substringBefore("/")
            val parent = config.providers.firstOrNull { it.name == parentName }
            if (parent != null) provider.copy(
                apiKey = if (provider.apiKey.isEmpty()) parent.apiKey else provider.apiKey,
                apiBase = if (provider.apiBase.isEmpty() || !provider.apiBase.startsWith("http")) parent.apiBase else provider.apiBase,
                path = if (provider.path == "/chat/completions") parent.path else provider.path
            ) else provider
        } else provider
        log("Resolved $requestedModel → ${resolved.name} (${resolved.model})")
        return resolved
    }

    fun toJson(obj: Any): String = gson.toJson(obj)

    fun parseJson(json: String): JsonElement = JsonParser.parseString(json)

    suspend fun callProviderRaw(requestJson: JsonObject, provider: ProviderConfig): String =
        withContext(Dispatchers.IO) {
            val apiBase = provider.apiBase.ifEmpty { "https://api.openai.com/v1" }
            requestJson.addProperty("model", provider.model)
            requestJson.remove("stream")
            if (requestJson.has("max_tokens")) {
                requestJson.addProperty("max_completion_tokens", requestJson.get("max_tokens").asInt)
                requestJson.remove("max_tokens")
            }

            // Cache lookup
            val key = if (cacheEnabled) cacheKey(provider.model, requestJson.get("messages"), requestJson.get("temperature")?.toString() ?: "") else null
            if (key != null) {
                cache.getIfPresent(key)?.let { synchronized(this@LiteLLMGateway) { cacheHits++ }; log("Cache hit"); return@withContext it }
                synchronized(this@LiteLLMGateway) { cacheMisses++ }
            }

            val payload = gson.toJson(requestJson)
            log("callProviderRaw payload: ${payload.take(500)}")

            val req = Request.Builder().url("$apiBase${provider.path}")
                .apply { if (provider.apiKey.isNotEmpty()) header("Authorization", "Bearer ${provider.apiKey}") }
                .post(payload.toRequestBody(JSON_MT)).build()

            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw APIError("Provider ${provider.name} HTTP ${resp.code}: ${body.take(500)}", resp.code)

            if (key != null) cache.put(key, body)
            body
        }

    suspend fun callProviderBinary(requestJson: JsonObject, provider: ProviderConfig): Pair<String, okio.Buffer> =
        withContext(Dispatchers.IO) {
            val apiBase = provider.apiBase.ifEmpty { "https://api.openai.com/v1" }
            requestJson.addProperty("model", provider.model)
            val payload = gson.toJson(requestJson)
            log("callProviderBinary: ${provider.name}${provider.path}")

            val req = Request.Builder().url("$apiBase${provider.path}")
                .apply { if (provider.apiKey.isNotEmpty()) header("Authorization", "Bearer ${provider.apiKey}") }
                .post(payload.toRequestBody(JSON_MT)).build()

            val resp = httpMedia.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: "Unknown error"
                throw APIError("Provider ${provider.name} HTTP ${resp.code}: ${err.take(500)}", resp.code)
            }
            val ct = resp.header("Content-Type") ?: "application/octet-stream"
            val buf = okio.Buffer()
            resp.body?.source()?.use { buf.writeAll(it) }
            ct to buf
        }

    suspend fun callProviderRawBytes(body: ByteArray, contentType: String, provider: ProviderConfig): String =
        withContext(Dispatchers.IO) {
            val apiBase = provider.apiBase.ifEmpty { "https://api.openai.com/v1" }
            log("callProviderRawBytes: ${provider.name}${provider.path} (${body.size} bytes)")

            val req = Request.Builder().url("$apiBase${provider.path}")
                .apply { if (provider.apiKey.isNotEmpty()) header("Authorization", "Bearer ${provider.apiKey}") }
                .post(body.toRequestBody(contentType.toMediaType())).build()

            val resp = httpMedia.newCall(req).execute()
            val result = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw APIError("Provider ${provider.name} HTTP ${resp.code}: ${result.take(500)}", resp.code)
            result
        }

    suspend fun callProviderStreaming(
        requestJson: JsonObject,
        provider: ProviderConfig,
        onLog: (String) -> Unit,
        onChunk: (String) -> Unit,
        onDone: () -> Unit
    ) = withContext(Dispatchers.IO) {
        val apiBase = provider.apiBase.ifEmpty { "https://api.openai.com/v1" }
        val payload = JsonParser.parseString(gson.toJson(requestJson)).asJsonObject
        payload.addProperty("model", provider.model)
        payload.addProperty("stream", true)
        if (payload.has("max_tokens")) {
            payload.addProperty("max_completion_tokens", payload.get("max_tokens").asInt)
            payload.remove("max_tokens")
        }

        val req = Request.Builder().url("$apiBase${provider.path}")
            .apply { if (provider.apiKey.isNotEmpty()) header("Authorization", "Bearer ${provider.apiKey}") }
            .header("Accept", "text/event-stream")
            .post(gson.toJson(payload).toRequestBody(JSON_MT)).build()

        val resp = http.newCall(req).execute()
        onLog("Provider responded: HTTP ${resp.code}, Content-Type: ${resp.header("Content-Type")}")

        if (!resp.isSuccessful) {
            val error = resp.body?.string() ?: "Unknown error"
            throw APIError("Provider ${provider.name} HTTP ${resp.code}: ${error.take(500)}", resp.code)
        }

        var chunkCount = 0
        var sentDone = false

        resp.body?.byteStream()?.let { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 256).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l.isEmpty()) continue
                    if (l.startsWith("data:")) {
                        val data = l.removePrefix("data:").trim()
                        if (data == "[DONE]") { if (!sentDone) { sentDone = true; onDone() }; break }
                        if (data.isNotEmpty()) { onChunk(data); chunkCount++ }
                    } else if (l.startsWith("{")) { onChunk(l); chunkCount++ }
                }
            }
        }

        onLog("Stream ended: $chunkCount chunks read")
        if (!sentDone) onDone()
    }

    private fun parseConfigJson(json: String): GatewayConfig {
        val obj = JsonParser.parseString(json).asJsonObject
        val providers = mutableListOf<ProviderConfig>()
        val arr = obj.getAsJsonArray("providers")
        if (arr != null) {
            for (elem in arr) {
                val p = elem.asJsonObject
                providers.add(ProviderConfig(
                    name = p.get("name").asString,
                    model = p.get("model")?.asString ?: "",
                    apiKey = p.get("apiKey")?.asString ?: "",
                    apiBase = p.get("apiBase")?.asString ?: "",
                    path = p.get("path")?.asString ?: "/chat/completions",
                    weight = p.get("weight")?.asInt ?: 100,
                    enabled = p.get("enabled")?.asBoolean ?: true,
                    displayId = p.get("displayId")?.asString ?: "",
                    cachedModels = p.get("cachedModels")?.asJsonArray?.map { it.asString } ?: emptyList()
                ))
            }
        }
        return GatewayConfig(
            apiKey = obj.get("apiKey")?.asString ?: "aiope-gateway-key",
            providers = if (providers.isEmpty()) defaultConfig().providers else providers,
            routingStrategy = obj.get("routingStrategy")?.asString ?: "direct",
            fallbackEnabled = obj.get("fallbackEnabled")?.asBoolean ?: false,
            blocked = obj.get("blocked")?.asBoolean ?: false
        )
    }

    private fun configToJson(config: GatewayConfig): JsonObject = JsonObject().apply {
        addProperty("apiKey", config.apiKey)
        addProperty("routingStrategy", config.routingStrategy)
        addProperty("fallbackEnabled", config.fallbackEnabled)
        addProperty("blocked", config.blocked)
        add("providers", JsonArray().apply {
            config.providers.forEach { p ->
                add(JsonObject().apply {
                    addProperty("name", p.name)
                    addProperty("model", p.model)
                    addProperty("displayId", p.displayId)
                    addProperty("apiKey", p.apiKey)
                    addProperty("apiBase", p.apiBase)
                    addProperty("path", p.path)
                    addProperty("weight", p.weight)
                    addProperty("enabled", p.enabled)
                    add("cachedModels", JsonArray().apply { p.cachedModels.forEach { add(it) } })
                })
            }
        })
    }

    private fun defaultConfig(): GatewayConfig {
        return GatewayConfig(
            apiKey = "aiope-gateway-key",
            providers = listOf(
                ProviderConfig(name = "github-models", model = "", apiKey = "", apiBase = "https://models.github.ai/inference", enabled = true),
                ProviderConfig(name = "google-ai-studio", model = "", apiKey = "", apiBase = "https://generativelanguage.googleapis.com/v1beta/openai", enabled = true),
                ProviderConfig(name = "bedrock-mantle", model = "", apiKey = "", apiBase = "https://bedrock-mantle.us-west-2.api.aws/v1", enabled = true),
                ProviderConfig(name = "bedrock-runtime", model = "", apiKey = "", apiBase = "https://bedrock-runtime.us-west-2.amazonaws.com/openai/v1", enabled = true),
                ProviderConfig(name = "openrouter", model = "", apiKey = "", apiBase = "https://openrouter.ai/api/v1", enabled = true),
                ProviderConfig(name = "cohere", model = "", apiKey = "", apiBase = "https://api.cohere.ai/compatibility/v1", enabled = true),
                ProviderConfig(name = "cloudflare", model = "", apiKey = "", apiBase = "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1", enabled = true),
                ProviderConfig(name = "cline", model = "", apiKey = "", apiBase = "https://api.cline.bot/api/v1", enabled = true),
                ProviderConfig(name = "zen", model = "", apiKey = "", apiBase = "https://opencode.ai/zen/v1", enabled = true),
                ProviderConfig(name = "pollinations", model = "", apiKey = "", apiBase = "https://text.pollinations.ai/openai", enabled = true)
            ),
            routingStrategy = "direct",
            fallbackEnabled = false
        )
    }

    private fun log(message: String) = onLog(message)
}
