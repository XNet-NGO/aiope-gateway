package com.aiope.gateway

import kotlinx.coroutines.*
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.eclipse.jetty.servlet.*
import org.eclipse.jetty.util.ssl.SslContextFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.servlet.http.*
import com.google.gson.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val G = GsonBuilder().disableHtmlEscaping().create()
private fun jsonResp(vararg pairs: Pair<String, Any?>): String {
    val obj = JsonObject()
    pairs.forEach { (k, v) -> when(v) { is Boolean -> obj.addProperty(k, v); is Number -> obj.addProperty(k, v); is String -> obj.addProperty(k, v); null -> obj.add(k, JsonNull.INSTANCE); is JsonElement -> obj.add(k, v); else -> obj.addProperty(k, v.toString()) } }
    return G.toJson(obj)
}
private fun errResp(msg: String, code: Int = 500): String {
    val obj = JsonObject(); val err = JsonObject(); err.addProperty("message", msg); err.addProperty("code", code); obj.add("error", err); return G.toJson(obj)
}

// ── Server log visible to portal ──────────────────────────────────────────────
object ServerLog {
    private val entries = CopyOnWriteArrayList<String>()
    private var nextId = 0L
    fun add(msg: String) { synchronized(this) { entries.add("""{"id":${nextId++},"t":${System.currentTimeMillis()},"m":${JsonPrimitive(msg)}}"""); if (entries.size > 200) entries.removeAt(0) } }
    fun since(afterId: Long): String { val list = entries.mapNotNull { val id = it.substringAfter("\"id\":").substringBefore(",").toLongOrNull(); if (id != null && id > afterId) it else null }; return "[${list.joinToString(",")}]" }
}

class GatewayServer(private val port: Int, private val dataDir: File) {
    private val gateway = XNetLLM(File(dataDir, "config.json")) { msg ->
        println("[Gateway] $msg"); ServerLog.add(msg)
    }
    val sessions = ConcurrentHashMap<String, SessionInfo>()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val gson = GsonBuilder().disableHtmlEscaping().create()
    @Volatile var blocked = false

    data class SessionInfo(val token: String, val user: String = "admin", val createdAt: Long = System.currentTimeMillis())

    // Request history
    data class RequestRecord(val id: Long, val ts: Long, val model: String, val endpoint: String, val status: Int, val latencyMs: Long, val user: String)
    private val requestHistory = CopyOnWriteArrayList<RequestRecord>()
    private var nextReqId = 0L
    fun addRequest(r: RequestRecord) { synchronized(requestHistory) { requestHistory.add(r); if (requestHistory.size > 500) requestHistory.removeAt(0) } }
    fun getRequests(limit: Int = 50): List<RequestRecord> = requestHistory.takeLast(limit).reversed()

    // Audit log
    data class AuditEntry(val id: Long, val ts: Long, val user: String, val action: String, val detail: String)
    private val auditLog = CopyOnWriteArrayList<AuditEntry>()
    private var nextAuditId = 0L
    fun audit(user: String, action: String, detail: String = "") { synchronized(auditLog) { auditLog.add(AuditEntry(nextAuditId++, System.currentTimeMillis(), user, action, detail)); if (auditLog.size > 1000) auditLog.removeAt(0) } }
    fun getAudit(limit: Int = 100): List<AuditEntry> = auditLog.takeLast(limit).reversed()

    // Rate limiting (liberal — just stop DoS)
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()
    fun checkRate(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val window = requestCounts.getOrPut(ip) { mutableListOf() }
        synchronized(window) { window.removeAll { now - it > 60_000 }; window.add(now); return window.size <= 120 } // 120 req/min per IP
    }

    // Brute force protection (liberal)
    private val failedLogins = ConcurrentHashMap<String, MutableList<Long>>()
    fun recordFailedLogin(ip: String) { val now = System.currentTimeMillis(); failedLogins.getOrPut(ip) { mutableListOf() }.let { synchronized(it) { it.add(now); it.removeAll { t -> now - t > 900_000 } } } }
    fun isBlocked(ip: String): Boolean { val now = System.currentTimeMillis(); return (failedLogins[ip]?.count { now - it < 900_000 } ?: 0) >= 10 } // 10 fails in 15min

    // Multi-user: stored in data/users.json
    data class UserAccount(val name: String, val apiKey: String, val role: String = "user", val providers: List<String> = emptyList()) // empty = all
    private var users = mutableListOf<UserAccount>()
    private val usersFile = File(dataDir, "users.json")
    fun loadUsers() { try { if (usersFile.exists()) { val arr = com.google.gson.JsonParser.parseString(usersFile.readText()).asJsonArray; users = arr.map { val o = it.asJsonObject; UserAccount(o.get("name").asString, o.get("apiKey").asString, o.get("role")?.asString ?: "user", o.get("providers")?.asJsonArray?.map { p -> p.asString } ?: emptyList()) }.toMutableList() } } catch (_: Exception) {} }
    fun saveUsers() { usersFile.writeText(gson.toJson(users)) }
    fun getUsers() = users.toList()
    fun findUser(key: String): UserAccount? = users.find { it.apiKey == key }
    fun addUser(u: UserAccount) { users.add(u); saveUsers() }
    fun removeUser(name: String) { users.removeAll { it.name == name }; saveUsers() }

    // Webhooks: stored in data/webhooks.json
    @Volatile var webhookUrl: String = ""
    private val webhookFile = File(dataDir, "webhooks.json")
    fun loadWebhooks() { try { if (webhookFile.exists()) { val o = com.google.gson.JsonParser.parseString(webhookFile.readText()).asJsonObject; webhookUrl = o.get("url")?.asString ?: "" } } catch (_: Exception) {} }
    fun saveWebhooks() { webhookFile.writeText("""{"url":"$webhookUrl"}""") }
    fun sendWebhook(event: String, message: String) { if (webhookUrl.isBlank()) return; scope.launch(Dispatchers.IO) { try { val body = gson.toJson(mapOf("event" to event, "message" to message, "ts" to System.currentTimeMillis())); okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(webhookUrl).post(body.toByteArray().toRequestBody("application/json".toMediaType())).build()).execute().close() } catch (_: Exception) {} } }


    // Third-party API keys: stored in data/keys.json
    private val apiKeys = ConcurrentHashMap<String, String>()
    private val keysFile = File(dataDir, "keys.json")
    fun loadKeys() {
        // Load from file, then overlay env vars (env takes precedence)
        try { if (keysFile.exists()) { val o = com.google.gson.JsonParser.parseString(keysFile.readText()).asJsonObject; o.entrySet().forEach { apiKeys[it.key] = it.value.asString } } } catch (_: Exception) {}
        System.getenv("NASA_API_KEY")?.let { if (it.isNotBlank()) apiKeys["nasa"] = it }
        System.getenv("GEOAPIFY_KEY")?.let { if (it.isNotBlank()) apiKeys["geoapify"] = it }
    }
    fun saveKeys() { keysFile.writeText(gson.toJson(apiKeys)) }
    fun getApiKeys(): Map<String, String> = apiKeys.toMap()
    fun getApiKey(name: String): String = apiKeys[name] ?: ""
    fun setApiKey(name: String, value: String) { if (value.isBlank()) apiKeys.remove(name) else apiKeys[name] = value; saveKeys() }
    fun removeApiKey(name: String) { apiKeys.remove(name); saveKeys() }
    val startTime = System.currentTimeMillis()
    @Volatile var requestCount = 0L
    @Volatile var errorCount = 0L

    fun start() {
        val tp = org.eclipse.jetty.util.thread.QueuedThreadPool(500, 16)
        val server = Server(tp)
        val handler = ServletContextHandler(ServletContextHandler.SESSIONS)
        handler.contextPath = "/"
        handler.addServlet(LoginServlet::class.java, "/login")
        handler.addServlet(LoginServlet::class.java, "/")
        handler.addServlet(WebPortalServlet::class.java, "/portal/*")
        handler.addServlet(ApiServlet::class.java, "/v1/*").registration.setMultipartConfig(
            javax.servlet.MultipartConfigElement("/tmp", 50*1024*1024, 50*1024*1024, 1024*1024)
        )
        handler.addServlet(DataServlet::class.java, "/v1/data")
        handler.addServlet(ConfigServlet::class.java, "/api/config/*")
        handler.addServlet(HealthServlet::class.java, "/health")
        handler.addServlet(AdminServlet::class.java, "/admin/*")
        handler.addServlet(LogServlet::class.java, "/api/logs")
        handler.addServlet(StatsServlet::class.java, "/api/stats")
        handler.addServlet(NetworkServlet::class.java, "/api/network/*")
        handler.addServlet(WifiServlet::class.java, "/api/wifi/*")
        handler.addServlet(CertServlet::class.java, "/api/certs/*")
        handler.addServlet(FileServlet::class.java, "/api/files/*").registration.setMultipartConfig(
            javax.servlet.MultipartConfigElement("/tmp", 50*1024*1024, 50*1024*1024, 1024*1024)
        )
        handler.addServlet(HistoryServlet::class.java, "/api/history")
        handler.addServlet(AuditServlet::class.java, "/api/audit")
        handler.addServlet(UsersServlet::class.java, "/api/users/*")
        handler.addServlet(WebhookServlet::class.java, "/api/webhooks/*")
        handler.addServlet(BackupServlet::class.java, "/api/backup/*")
        handler.addServlet(KeysServlet::class.java, "/api/keys/*")
        handler.setAttribute("gateway", this)

        // Main connector (default port)
        val httpConfig = HttpConfiguration().apply { sendServerVersion = false; securePort = 443 }
        val mainConnector = ServerConnector(server, HttpConnectionFactory(httpConfig))
        mainConnector.host = "0.0.0.0"; mainConnector.port = port
        server.addConnector(mainConnector)

        // Port 80 connector
        if (port != 80) {
            val http80 = ServerConnector(server, HttpConnectionFactory(HttpConfiguration().apply { sendServerVersion = false }))
            http80.host = "0.0.0.0"; http80.port = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 80
            try { server.addConnector(http80); println("[Gateway] HTTP on port 80") } catch (e: Exception) { println("[Gateway] Port 80 unavailable: ${e.message}") }
        }

        // TLS on 443 if certs exist
        val certDir = java.io.File("/etc/letsencrypt/live")
        val domain = certDir.listFiles()?.firstOrNull { it.isDirectory && it.name != "README" && java.io.File(it, "fullchain.pem").exists() }
        if (domain != null && port != 443) {
            try {
                val sslCtx = org.eclipse.jetty.util.ssl.SslContextFactory.Server().apply {
                    setPemConfig(domain)
                }
                val httpsConfig = HttpConfiguration().apply { sendServerVersion = false; addCustomizer(org.eclipse.jetty.server.SecureRequestCustomizer()) }
                val tls = ServerConnector(server, sslCtx, HttpConnectionFactory(httpsConfig))
                tls.host = "0.0.0.0"; tls.port = System.getenv("TLS_PORT")?.toIntOrNull() ?: 443
                server.addConnector(tls)
                println("[Gateway] HTTPS on port ${tls.port} (cert: ${domain.name})")
            } catch (e: Exception) { println("[Gateway] TLS setup failed: ${e.message}") }
        }

        // Use handler collection to add redirect from 80→443 when TLS is active
        if (domain != null) {
            val handlers = org.eclipse.jetty.server.handler.HandlerList()
            handlers.addHandler(object : org.eclipse.jetty.server.handler.AbstractHandler() {
                override fun handle(target: String, baseRequest: org.eclipse.jetty.server.Request, request: javax.servlet.http.HttpServletRequest, response: javax.servlet.http.HttpServletResponse) {
                    if (request.localPort == 80 && request.scheme == "http") {
                        response.sendRedirect("https://${request.serverName}${request.requestURI}${if (request.queryString != null) "?${request.queryString}" else ""}")
                        baseRequest.isHandled = true
                    }
                }
            })
            handlers.addHandler(handler)
            server.handler = handlers
        } else {
            server.handler = handler
        }

        println("[Gateway] Server started on http://0.0.0.0:$port")
        server.start(); server.join()
    }

    private fun org.eclipse.jetty.util.ssl.SslContextFactory.Server.setPemConfig(certDir: java.io.File) {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, null)

        // Load cert chain
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val chain = java.io.File(certDir, "fullchain.pem").inputStream().use { certFactory.generateCertificates(it) }.toTypedArray()

        // Load private key
        val keyPem = java.io.File(certDir, "privkey.pem").readText()
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "").replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = java.util.Base64.getDecoder().decode(keyPem)
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
        val key = try { java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec) }
                  catch (_: Exception) { java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec) }

        ks.setKeyEntry("cert", key, charArrayOf(), chain)
        this.keyStore = ks
        this.setKeyStorePassword("")
    }
    fun getGateway() = gateway
    fun isAuthorized(req: HttpServletRequest): Boolean {
        val token = req.cookies?.find { it.name == "gateway_session" }?.value
        val auth = req.getHeader("Authorization")
        val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else null
        return (token != null && sessions[token] != null) || (bearer != null && bearer == gateway.getConfig().apiKey)
    }
    fun isApiAuthorized(req: HttpServletRequest): Boolean {
        val token = req.cookies?.find { it.name == "gateway_session" }?.value
        val auth = req.getHeader("Authorization")
        val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else null
        return (token != null && sessions[token] != null) || (bearer != null && (bearer == gateway.getConfig().apiKey || findUser(bearer) != null))
    }
    fun resolveUser(req: HttpServletRequest): String {
        val token = req.cookies?.find { it.name == "gateway_session" }?.value
        if (token != null) return sessions[token]?.user ?: "admin"
        val auth = req.getHeader("Authorization")
        val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else null
        if (bearer == gateway.getConfig().apiKey) return "admin"
        return findUser(bearer ?: "")?.name ?: "unknown"
    }
    fun checkUserProviderAccess(req: HttpServletRequest, providerName: String) {
        val auth = req.getHeader("Authorization")
        val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else null
        if (bearer == gateway.getConfig().apiKey) return // admin has full access
        val user = findUser(bearer ?: "") ?: return // session-based users (portal) have full access
        if (user.providers.isNotEmpty() && user.providers.none { providerName == it || providerName.startsWith("$it/") }) {
            throw APIError("Access denied: ${user.name} cannot use provider $providerName", 403)
        }
    }
}

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8082
    val dataDir = args.getOrNull(1)?.let { File(it) } ?: File("data")
    dataDir.mkdirs()
    val server = GatewayServer(port, dataDir)
    server.loadUsers()
    server.loadWebhooks()
    server.loadKeys()
    server.start()
}

class LoginServlet : HttpServlet() {
    private val csrfTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (ctx.isBlocked(req.remoteAddr)) { resp.status = 429; resp.writer.write("Too many failed attempts. Try again later."); return }
        // Prune expired tokens (>10min)
        val now = System.currentTimeMillis()
        csrfTokens.entries.removeIf { now - it.value > 600_000 }
        val csrf = UUID.randomUUID().toString()
        csrfTokens[csrf] = now
        resp.contentType = "text/html; charset=utf-8"
        resp.writer.write(LOGIN_HTML.replace("<!--CSRF-->", "<input type=\"hidden\" name=\"csrf\" value=\"$csrf\">"))
    }
    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        val ip = req.remoteAddr
        if (ctx.isBlocked(ip)) { resp.status = 429; resp.writer.write("Too many failed attempts. Try again later."); return }
        val csrf = req.getParameter("csrf") ?: ""
        if (csrf.isEmpty() || csrfTokens.remove(csrf) == null) { resp.status = 403; resp.contentType = "text/html; charset=utf-8"; resp.writer.write(LOGIN_HTML.replace("<!--ERROR-->", "<p style='color:#f44336'>Session expired. Please refresh.</p>")); return }
        val pw = req.getParameter("password") ?: ""
        if (pw == ctx.getGateway().getConfig().apiKey) {
            val token = UUID.randomUUID().toString()
            ctx.sessions[token] = GatewayServer.SessionInfo(token, "admin")
            resp.addCookie(Cookie("gateway_session", token).apply { path = "/"; maxAge = 86400 * 7; isHttpOnly = true })
            ctx.audit("admin", "login", "from $ip")
            resp.sendRedirect("/portal/")
        } else {
            ctx.recordFailedLogin(ip)
            ctx.audit("unknown", "login_failed", "from $ip")
            resp.contentType = "text/html; charset=utf-8"; resp.status = 401; resp.writer.write(LOGIN_HTML.replace("<!--ERROR-->", "<p style='color:#f44336'>Invalid API key</p>"))
        }
    }
}

class WebPortalServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.sendRedirect("/login"); return }
        resp.contentType = "text/html; charset=utf-8"
        resp.writer.write(PortalHtml.html.replace("{{PORT}}", req.localPort.toString()).replace("{{HOST}}", req.serverName))
    }
}

class HealthServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        resp.contentType = "application/json"
        resp.writer.write(jsonResp("status" to if (ctx.blocked) "blocked" else "ok", "blocked" to ctx.blocked, "providers" to ctx.getGateway().getProviders().size, "port" to req.localPort))
    }
}

class LogServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        val after = req.getParameter("after")?.toLongOrNull() ?: -1
        resp.contentType = "application/json"; resp.writer.write(ServerLog.since(after))
    }
}

class AdminServlet : HttpServlet() {
    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        val body = req.reader.readText()
        val action = try { JsonParser.parseString(body).asJsonObject.get("action")?.asString } catch (_: Exception) { req.getParameter("action") }
        resp.contentType = "application/json"
        when (action) {
            "start" -> { ctx.blocked = false; ServerLog.add("Server unblocked"); resp.writer.write(jsonResp("status" to "ok", "message" to "Accepting connections")) }
            "stop" -> { ctx.blocked = true; ServerLog.add("Server blocked"); resp.writer.write(jsonResp("status" to "ok", "message" to "Blocking connections")) }
            "shutdown" -> { resp.writer.write(jsonResp("status" to "ok")); Thread { Thread.sleep(500); System.exit(0) }.start() }
            "cache_clear" -> { ctx.getGateway().clearCache(); ServerLog.add("Cache cleared"); resp.writer.write(jsonResp("status" to "ok", "message" to "Cache cleared")) }
            "cache_on" -> { ctx.getGateway().cacheEnabled = true; ServerLog.add("Cache enabled"); resp.writer.write(jsonResp("status" to "ok")) }
            "cache_off" -> { ctx.getGateway().cacheEnabled = false; ServerLog.add("Cache disabled"); resp.writer.write(jsonResp("status" to "ok")) }
            else -> { resp.status = 400; resp.writer.write(errResp("Unknown action", 400)) }
        }
    }
}

class ConfigServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; resp.contentType = "application/json"; resp.writer.write(errResp("Unauthorized", 401)); return }
        if (req.method == "OPTIONS") { cors(resp); resp.status = 200; return }

        val path = req.pathInfo?.removePrefix("/") ?: ""
        resp.contentType = "application/json"; cors(resp)
        try { when {
            path == "" && req.method == "GET" -> getConfig(ctx, resp)
            path == "" && req.method == "PUT" -> updateConfig(ctx, req, resp)
            path == "providers" && req.method == "GET" -> getProviders(ctx, resp)
            path.startsWith("providers/") && req.method == "PUT" -> updateProvider(ctx, req, resp, path.removePrefix("providers/"))
            path.startsWith("providers/") && req.method == "DELETE" -> deleteProvider(ctx, resp, path.removePrefix("providers/"))
            path.matches(Regex("providers/.+/models/add")) && req.method == "POST" -> addProviderModel(ctx, req, resp, path.removePrefix("providers/").removeSuffix("/models/add"))
            path.matches(Regex("providers/.+/models/remove")) && req.method == "POST" -> removeProviderModel(ctx, req, resp, path.removePrefix("providers/").removeSuffix("/models/remove"))
            path == "providers" && req.method == "POST" -> addProvider(ctx, req, resp)
            path == "loadmodels" && req.method == "POST" -> loadModels(ctx, req, resp)
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        } } catch (e: Exception) { resp.status = 500; resp.writer.write(errResp(e.message ?: "Internal error")) }
    }
    private fun getConfig(ctx: GatewayServer, resp: HttpServletResponse) {
        val c = ctx.getGateway().getConfig()
        val j = JsonObject().apply { addProperty("apiKey", c.apiKey); add("providers", JsonArray().apply { c.providers.forEach { p -> add(JsonObject().apply { addProperty("name", p.name); addProperty("model", p.model); addProperty("displayId", p.displayId); addProperty("apiBase", p.apiBase); addProperty("apiKey", if (p.apiKey.isNotEmpty()) "***set***" else ""); addProperty("enabled", p.enabled) }) } }) }
        resp.writer.write(ctx.gson.toJson(j))
    }
    private fun updateConfig(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject
        j.get("apiKey")?.asString?.let { k -> if (k.isNotEmpty() && k != "***set***") { ctx.getGateway().setGatewayApiKey(k); ServerLog.add("Gateway API key updated") } }
        resp.writer.write(jsonResp("status" to "ok"))
    }
    private fun getProviders(ctx: GatewayServer, resp: HttpServletResponse) {
        val a = JsonArray(); ctx.getGateway().getAllProviders().forEach { p -> a.add(JsonObject().apply { addProperty("name", p.name); addProperty("model", p.model); addProperty("displayId", p.displayId); addProperty("apiBase", p.apiBase); addProperty("path", p.path); addProperty("apiKey", if (p.apiKey.isNotEmpty()) "***set***" else ""); addProperty("enabled", p.enabled); add("cachedModels", JsonArray().apply { p.cachedModels.forEach { add(it) } }) }) }
        resp.writer.write(ctx.gson.toJson(a))
    }
    private fun updateProvider(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, name: String) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject; val gw = ctx.getGateway()
        j.get("apiKey")?.asString?.let { k -> if (k.isNotEmpty() && k != "***set***") gw.setProviderApiKey(name, k) }
        j.get("model")?.asString?.let { m -> if (m.isNotEmpty()) gw.setProviderModel(name, m) }
        j.get("apiBase")?.asString?.let { b -> if (b.isNotEmpty()) gw.setProviderApiBase(name, b) }
        j.get("displayId")?.asString?.let { d -> if (d.isNotEmpty()) gw.setProviderDisplayId(name, d) }
        j.get("path")?.asString?.let { p -> gw.setProviderPath(name, p) }
        if (j.has("enabled")) { if (j.get("enabled").asBoolean) gw.enableProvider(name) else gw.disableProvider(name) }
        resp.writer.write(jsonResp("status" to "ok", "message" to "Provider $name updated"))
    }
    private fun loadModels(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject
        val name = j.get("provider")?.asString ?: throw IllegalArgumentException("provider required")
        val p = ctx.getGateway().getAllProviders().find { it.name == name } ?: throw IllegalArgumentException("Not found: $name")
        val models = runBlocking { ctx.getGateway().loadProviderModels(p) }
        ctx.getGateway().setCachedModels(name, models)
        resp.writer.write(ctx.gson.toJson(mapOf("models" to models)))
    }
    private fun addProvider(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject
        val name = j.get("name")?.asString?.trim() ?: throw IllegalArgumentException("name required")
        if (ctx.getGateway().getAllProviders().any { it.name == name }) throw IllegalArgumentException("Provider '$name' already exists")
        val p = ProviderConfig(
            name = name,
            model = j.get("model")?.asString?.trim() ?: "",
            apiKey = j.get("apiKey")?.asString?.trim() ?: "",
            apiBase = j.get("apiBase")?.asString?.trim() ?: "",
            displayId = j.get("displayId")?.asString?.trim() ?: "$name/${j.get("model")?.asString?.trim()?.replace("/", "-") ?: "default"}",
            enabled = true
        )
        ctx.getGateway().addProvider(p)
        ServerLog.add("Added provider: $name")
        resp.writer.write(jsonResp("status" to "ok", "message" to "Provider $name added"))
    }
    private fun deleteProvider(ctx: GatewayServer, resp: HttpServletResponse, name: String) {
        ctx.getGateway().removeProvider(name)
        ServerLog.add("Removed provider: $name")
        resp.writer.write(jsonResp("status" to "ok", "message" to "Provider $name removed"))
    }
    private fun addProviderModel(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, name: String) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject
        val modelId = j.get("model")?.asString ?: throw IllegalArgumentException("model required")
        val gw = ctx.getGateway()
        val parent = gw.getAllProviders().find { it.name == name } ?: throw IllegalArgumentException("Not found: $name")
        // Check if already exists
        val existing = gw.getAllProviders().any { (it.name == name && it.model == modelId) || it.name == "$name/${modelId.replace("/", "-")}" }
        if (existing) { resp.writer.write(jsonResp("status" to "ok", "message" to "Already enabled")); return }
        val shortName = modelId.replace("/", "-")
        gw.addProvider(ProviderConfig(name = "$name/$shortName", model = modelId, apiKey = parent.apiKey, apiBase = parent.apiBase, path = parent.path, displayId = "$name/$shortName", enabled = true))
        ServerLog.add("Added model $modelId to $name")
        resp.writer.write(jsonResp("status" to "ok", "message" to "Enabled $modelId"))
    }
    private fun removeProviderModel(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, name: String) {
        val j = JsonParser.parseString(req.reader.readText()).asJsonObject
        val modelId = j.get("model")?.asString ?: throw IllegalArgumentException("model required")
        val gw = ctx.getGateway()
        val shortName = modelId.replace("/", "-")
        val subName = "$name/$shortName"
        // If it's the parent's model, just clear it; if sub-provider, remove it
        val parent = gw.getAllProviders().find { it.name == name }
        if (parent != null && parent.model == modelId) {
            gw.setProviderModel(name, "")
        } else {
            gw.removeProvider(subName)
        }
        ServerLog.add("Removed model $modelId from $name")
        resp.writer.write(jsonResp("status" to "ok", "message" to "Removed $modelId"))
    }
    private fun cors(resp: HttpServletResponse) { resp.setHeader("Access-Control-Allow-Origin", "*"); resp.setHeader("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,OPTIONS"); resp.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization") }
}

class ApiServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (req.method == "OPTIONS") { resp.setHeader("Access-Control-Allow-Origin", "*"); resp.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS"); resp.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization"); resp.status = 200; return }
        if (!ctx.checkRate(req.remoteAddr)) { resp.status = 429; resp.contentType = "application/json"; resp.writer.write(errResp("Rate limit exceeded", 429)); return }
        if (!ctx.isApiAuthorized(req)) { resp.status = 401; resp.contentType = "application/json"; resp.writer.write(errResp("Unauthorized", 401)); return }
        if (ctx.blocked) { resp.status = 503; resp.contentType = "application/json"; resp.writer.write(errResp("Server is blocked", 503)); return }
        val path = req.pathInfo ?: "/"
        val user = ctx.resolveUser(req)
        val start = System.currentTimeMillis()
        ctx.requestCount++
        var status = 200
        try { when {
            path == "/models" && req.method == "GET" -> handleModels(ctx, req, resp)
            path == "/chat/completions" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/completions" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/embeddings" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/audio/speech" && req.method == "POST" -> handleProxyBinary(ctx, req, resp, path)
            path == "/audio/transcriptions" && req.method == "POST" -> handleProxyMultipart(ctx, req, resp, path)
            path == "/audio/translations" && req.method == "POST" -> handleProxyMultipart(ctx, req, resp, path)
            path == "/images/generations" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/images/edits" && req.method == "POST" -> handleProxyMultipart(ctx, req, resp, path)
            path == "/images/variations" && req.method == "POST" -> handleProxyMultipart(ctx, req, resp, path)
            path == "/moderations" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/rerank" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path == "/responses" && req.method == "POST" -> handleProxy(ctx, req, resp, path)
            path.startsWith("/responses/") && req.method == "GET" -> handlePassthrough(ctx, req, resp, path)
            else -> { resp.status = 404; resp.contentType = "application/json"; resp.writer.write(errResp("Not found", 404)) }
        } } catch (e: Exception) { ctx.errorCount++; status = 500; ServerLog.add("Error: ${e.message}"); resp.status = 500; resp.contentType = "application/json"; val errJson = JsonObject().apply { add("error", JsonObject().apply { addProperty("message", e.message ?: "Internal error") }) }; resp.writer.write(errJson.toString()) }
        finally { ctx.addRequest(GatewayServer.RequestRecord(ctx.requestCount, System.currentTimeMillis(), "", path, status, System.currentTimeMillis() - start, user)) }
    }
    private fun handleModels(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse) {
        val ts = System.currentTimeMillis() / 1000; val a = JsonArray()
        val all = ctx.getGateway().getAllProviders()
        val parentNames = all.filter { it.name.contains("/") }.map { it.name.substringBefore("/") }.toSet()
        val seen = mutableSetOf<String>()
        // Resolve user's allowed providers
        val auth = req.getHeader("Authorization")
        val bearer = if (auth?.startsWith("Bearer ") == true) auth.removePrefix("Bearer ").trim() else null
        val userProviders = if (bearer != null && bearer != ctx.getGateway().getConfig().apiKey) ctx.findUser(bearer)?.providers ?: emptyList() else emptyList()
        all.filter { it.enabled && it.model.isNotBlank() }.forEach { p ->
            if (!p.name.contains("/") && p.name in parentNames) return@forEach
            if (userProviders.isNotEmpty() && userProviders.none { p.name == it || p.name.startsWith("$it/") }) return@forEach
            val id = p.displayId.ifEmpty { "${p.name.substringBefore("/")}/${p.model.replace("/", "-")}" }
            if (!seen.add(id.lowercase())) return@forEach
            val meta = ctx.getGateway().getModelsDevMeta(p.model)
            a.add(JsonObject().apply {
                addProperty("id", id); addProperty("object", "model"); addProperty("created", ts); addProperty("owned_by", p.name.substringBefore("/"))
                if (meta != null) {
                    meta.get("reasoning")?.let { addProperty("reasoning", it.asBoolean) }
                    meta.get("tool_call")?.let { addProperty("tool_call", it.asBoolean) }
                    meta.get("attachment")?.let { addProperty("attachment", it.asBoolean) }
                    meta.get("temperature")?.let { addProperty("temperature", it.asBoolean) }
                    meta.getAsJsonObject("limit")?.let { l -> l.get("context")?.let { addProperty("context_window", it.asInt) }; l.get("output")?.let { addProperty("max_output", it.asInt) } }
                    meta.getAsJsonObject("modalities")?.let { add("modalities", it) }
                    meta.get("name")?.let { addProperty("display_name", it.asString) }
                    meta.get("family")?.let { addProperty("family", it.asString) }
                }
            })
        }
        resp.contentType = "application/json"; resp.setHeader("Access-Control-Allow-Origin", "*")
        resp.writer.write(JsonObject().apply { addProperty("object", "list"); add("data", a) }.toString())
    }
    private fun handleProxy(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, endpoint: String) {
        val body = req.reader.readText(); val rj = JsonParser.parseString(body).asJsonObject
        val model = rj.get("model")?.asString ?: ""
        val stream = rj.get("stream")?.asBoolean ?: false
        ServerLog.add("${endpoint}: model=$model stream=$stream")
        val provider = ctx.getGateway().resolveProvider(model)
        ctx.checkUserProviderAccess(req, provider.name)
        // Use provider's custom path if set, otherwise match the requested endpoint
        val p = if (provider.path != "/chat/completions") provider else provider.copy(path = endpoint)
        // Cloudflare AI /run/ endpoints only accept {prompt}, strip other fields
        if (p.path.startsWith("/run/")) { val prompt = rj.get("prompt")?.asString ?: ""; rj.entrySet().map { it.key }.filter { it != "prompt" }.forEach { rj.remove(it) }; rj.addProperty("prompt", prompt) }
        if (stream) {
            resp.contentType = "text/event-stream; charset=utf-8"; resp.setHeader("Cache-Control", "no-cache"); resp.setHeader("Access-Control-Allow-Origin", "*")
            runBlocking { ctx.getGateway().callProviderStreaming(rj, p, onLog = { ServerLog.add(it) }, onChunk = { resp.writer.write("data: $it\n\n"); resp.writer.flush() }, onDone = {}) }
            resp.writer.write("data: [DONE]\n\n"); resp.writer.flush()
        } else {
            val rb = runBlocking { ctx.getGateway().callProviderRaw(rj, p) }
            resp.contentType = "application/json"; resp.setHeader("Access-Control-Allow-Origin", "*"); resp.writer.write(rb)
        }
    }
    private fun handleProxyBinary(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, endpoint: String) {
        val body = req.reader.readText(); val rj = JsonParser.parseString(body).asJsonObject
        val model = rj.get("model")?.asString ?: ""
        ServerLog.add("${endpoint}: model=$model")
        val provider = ctx.getGateway().resolveProvider(model)
        ctx.checkUserProviderAccess(req, provider.name)
        val p = if (provider.path != "/chat/completions") provider else provider.copy(path = endpoint)
        val result = runBlocking { ctx.getGateway().callProviderBinary(rj, p) }
        resp.contentType = result.first
        resp.setHeader("Access-Control-Allow-Origin", "*")
        result.second.writeTo(resp.outputStream)
    }
    private fun handlePassthrough(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, endpoint: String) {
        // For GET requests like /responses/{id}, we need the provider from a header or param
        val providerName = req.getParameter("provider") ?: req.getHeader("X-Provider") ?: ""
        val provider = (if (providerName.isNotEmpty()) ctx.getGateway().getAllProviders().firstOrNull { it.name == providerName || it.displayId == providerName }
            else ctx.getGateway().getAllProviders().firstOrNull { it.enabled && it.apiBase.contains("bedrock-runtime") })
            ?: throw APIError("No provider for passthrough. Set ?provider= or X-Provider header", 400)
        val resolved = ctx.getGateway().resolveProviderDirect(provider)
        val apiBase = resolved.apiBase.ifEmpty { "https://api.openai.com/v1" }
        val url = "$apiBase$endpoint"
        ServerLog.add("Passthrough GET: $url")
        val request = okhttp3.Request.Builder().url(url)
            .apply { if (resolved.apiKey.isNotEmpty()) header("Authorization", "Bearer ${resolved.apiKey}") }
            .get().build()
        val result = ctx.getGateway().httpClient().newCall(request).execute()
        resp.status = result.code
        resp.contentType = result.header("Content-Type") ?: "application/json"
        resp.setHeader("Access-Control-Allow-Origin", "*")
        result.body?.byteStream()?.use { it.copyTo(resp.outputStream) }
    }
    private fun handleProxyMultipart(ctx: GatewayServer, req: HttpServletRequest, resp: HttpServletResponse, endpoint: String) {
        // Read model from form field, forward entire multipart body
        val model = req.getParameter("model") ?: ""
        ServerLog.add("${endpoint}: model=$model (multipart)")
        val provider = ctx.getGateway().resolveProvider(model)
        ctx.checkUserProviderAccess(req, provider.name)
        val p = if (provider.path != "/chat/completions") provider else provider.copy(path = endpoint)
        val bodyBytes = req.inputStream.readBytes()
        val result = runBlocking { ctx.getGateway().callProviderRawBytes(bodyBytes, req.contentType, p) }
        resp.contentType = "application/json"; resp.setHeader("Access-Control-Allow-Origin", "*"); resp.writer.write(result)
    }
}

class StatsServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        val rt = Runtime.getRuntime()
        val uptimeMs = System.currentTimeMillis() - ctx.startTime
        val uptimeStr = "${uptimeMs / 86400000}d ${(uptimeMs / 3600000) % 24}h ${(uptimeMs / 60000) % 60}m"
        // System stats via /proc (Linux)
        val loadAvg = try { java.io.File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString(" ") } catch (_: Exception) { "n/a" }
        val memInfo = try {
            val lines = java.io.File("/proc/meminfo").readLines()
            val total = lines.find { it.startsWith("MemTotal") }?.split("\\s+".toRegex())?.get(1)?.toLongOrNull()?.div(1024) ?: 0
            val avail = lines.find { it.startsWith("MemAvailable") }?.split("\\s+".toRegex())?.get(1)?.toLongOrNull()?.div(1024) ?: 0
            mapOf("totalMB" to total, "availMB" to avail, "usedMB" to (total - avail))
        } catch (_: Exception) { mapOf("totalMB" to 0L, "availMB" to 0L, "usedMB" to 0L) }
        val cpuTemp = try { java.io.File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toLong() / 1000 } catch (_: Exception) { -1L }
        val diskFree = try {
            val f = java.io.File("/")
            mapOf("totalGB" to f.totalSpace / 1073741824, "freeGB" to f.freeSpace / 1073741824)
        } catch (_: Exception) { mapOf("totalGB" to 0L, "freeGB" to 0L) }

        val cs = ctx.getGateway().getCacheStats()
        resp.contentType = "application/json"
        resp.writer.write(jsonResp(
            "uptime" to uptimeStr, "requests" to ctx.requestCount, "errors" to ctx.errorCount,
            "jvmHeapMB" to (rt.totalMemory() - rt.freeMemory()) / 1048576,
            "jvmMaxMB" to rt.maxMemory() / 1048576,
            "loadAvg" to loadAvg, "cpuTempC" to cpuTemp,
            "memTotalMB" to (memInfo["totalMB"] ?: 0), "memUsedMB" to (memInfo["usedMB"] ?: 0),
            "diskTotalGB" to (diskFree["totalGB"] ?: 0), "diskFreeGB" to (diskFree["freeGB"] ?: 0),
            "cacheSize" to (cs["size"] ?: 0), "cacheHits" to (cs["hits"] ?: 0), "cacheMisses" to (cs["misses"] ?: 0), "cacheEnabled" to (cs["enabled"] ?: true)
        ))
    }
}

class NetworkServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.contentType = "application/json"
        val path = req.pathInfo?.removePrefix("/") ?: ""
        when {
            path == "" && req.method == "GET" -> {
                val hostname = try { ProcessBuilder("hostname").start().inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "unknown" }
                val interfaces = try {
                    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.map { iface ->
                        val addrs = iface.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { it.hostAddress }
                        mapOf("name" to iface.name, "addresses" to addrs, "mac" to (iface.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: ""))
                    }
                } catch (_: Exception) { emptyList() }
                val dns = try { java.io.File("/etc/resolv.conf").readLines().filter { it.startsWith("nameserver") }.map { it.split("\\s+".toRegex()).getOrElse(1) { "" } } } catch (_: Exception) { emptyList() }
                val gw = try { ProcessBuilder("ip", "route", "show", "default").start().inputStream.bufferedReader().readText().trim().split("\\s+".toRegex()).getOrElse(2) { "" } } catch (_: Exception) { "" }
                resp.writer.write(G.toJson(mapOf("hostname" to hostname, "interfaces" to interfaces, "dns" to dns, "gateway" to gw)))
            }
            path == "hostname" && req.method == "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val name = j.get("hostname")?.asString ?: throw IllegalArgumentException("hostname required")
                val r = ProcessBuilder("sudo", "hostnamectl", "set-hostname", name).start().waitFor()
                ServerLog.add("Hostname set to $name (exit=$r)")
                resp.writer.write(jsonResp("status" to if (r == 0) "ok" else "error", "message" to "Set hostname: $name"))
            }
            path == "dns" && req.method == "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val servers = j.getAsJsonArray("servers")?.map { it.asString } ?: throw IllegalArgumentException("servers required")
                val content = servers.joinToString("\n") { "nameserver $it" } + "\n"
                java.io.File("/etc/resolv.conf").writeText(content)
                ServerLog.add("DNS set to ${servers.joinToString(", ")}")
                resp.writer.write(jsonResp("status" to "ok"))
            }
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        }
    }
}

class WifiServlet : HttpServlet() {
    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.contentType = "application/json"
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try { when {
            // Current connection status
            path == "" && req.method == "GET" -> {
                val (_, nmOut) = run("nmcli", "-t", "-f", "DEVICE,TYPE,STATE,CONNECTION", "device")
                val devices = nmOut.lines().filter { it.contains("wifi") }.map { line ->
                    val parts = line.split(":"); mapOf("device" to parts.getOrElse(0){""}, "state" to parts.getOrElse(2){""}, "connection" to parts.getOrElse(3){""})
                }
                val (_, activeOut) = run("nmcli", "-t", "-f", "NAME,UUID,DEVICE,TYPE", "connection", "show", "--active")
                val active = activeOut.lines().filter { it.contains("wireless") || it.contains("wifi") }.map { line ->
                    val parts = line.split(":"); mapOf("name" to parts.getOrElse(0){""}, "uuid" to parts.getOrElse(1){""}, "device" to parts.getOrElse(2){""})
                }
                resp.writer.write(G.toJson(mapOf("devices" to devices, "active" to active)))
            }
            // Scan for networks
            path == "scan" && req.method == "GET" -> {
                run("nmcli", "device", "wifi", "rescan")
                Thread.sleep(2000)
                val (_, out) = run("nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY,BSSID,FREQ", "device", "wifi", "list")
                val networks = out.lines().filter { it.isNotBlank() }.map { line ->
                    val parts = line.split(":"); mapOf(
                        "ssid" to parts.getOrElse(0){""},
                        "signal" to (parts.getOrElse(1){"0"}.toIntOrNull() ?: 0),
                        "security" to parts.getOrElse(2){""},
                        "bssid" to parts.getOrElse(3){""},
                        "freq" to parts.getOrElse(4){""}
                    )
                }.filter { (it["ssid"] as? String)?.isNotBlank() == true }
                    .distinctBy { it["ssid"] }
                    .sortedByDescending { (it["signal"] as? Int) ?: 0 }
                resp.writer.write(G.toJson(mapOf("networks" to networks)))
            }
            // Connect to network
            path == "connect" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val ssid = j.get("ssid")?.asString ?: throw IllegalArgumentException("ssid required")
                val password = j.get("password")?.asString
                ServerLog.add("Connecting to WiFi: $ssid")
                val cmd = if (password.isNullOrEmpty()) {
                    arrayOf("sudo", "nmcli", "device", "wifi", "connect", ssid)
                } else {
                    arrayOf("sudo", "nmcli", "device", "wifi", "connect", ssid, "password", password)
                }
                val (exit, output) = run(*cmd)
                ServerLog.add("WiFi connect exit=$exit: ${output.take(200)}")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.trim().take(300)))
            }
            // Disconnect
            path == "disconnect" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val device = j.get("device")?.asString ?: "wlan0"
                val (exit, output) = run("sudo", "nmcli", "device", "disconnect", device)
                ServerLog.add("WiFi disconnect $device exit=$exit")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.trim().take(300)))
            }
            // Forget a saved network
            path == "forget" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val name = j.get("name")?.asString ?: throw IllegalArgumentException("connection name required")
                val (exit, output) = run("sudo", "nmcli", "connection", "delete", name)
                ServerLog.add("WiFi forget '$name' exit=$exit")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.trim().take(300)))
            }
            // List saved connections
            path == "saved" && req.method == "GET" -> {
                val (_, out) = run("nmcli", "-t", "-f", "NAME,UUID,TYPE", "connection", "show")
                val saved = out.lines().filter { it.contains("wireless") || it.contains("wifi") }.map { line ->
                    val parts = line.split(":"); mapOf("name" to parts.getOrElse(0){""}, "uuid" to parts.getOrElse(1){""})
                }
                resp.writer.write(G.toJson(mapOf("saved" to saved)))
            }
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        } } catch (e: Exception) { resp.status = 500; resp.writer.write(errResp(e.message ?: "Internal error")) }
    }
}

class CertServlet : HttpServlet() {
    private fun runCmd(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun certInfo(dir: java.io.File): Map<String, String> {
        val cert = java.io.File(dir, "cert.pem")
        if (!cert.exists()) return mapOf("domain" to dir.name, "status" to "missing")
        val (_, text) = runCmd("openssl", "x509", "-noout", "-subject", "-issuer", "-dates", "-serial", "-fingerprint", "-in", cert.absolutePath)
        val fields = mutableMapOf("domain" to dir.name, "path" to dir.absolutePath)
        text.lines().forEach { line ->
            when {
                line.startsWith("subject=") -> fields["subject"] = line.removePrefix("subject=").trim()
                line.startsWith("issuer=") -> fields["issuer"] = line.removePrefix("issuer=").trim()
                line.startsWith("notBefore=") -> fields["notBefore"] = line.removePrefix("notBefore=").trim()
                line.startsWith("notAfter=") -> fields["notAfter"] = line.removePrefix("notAfter=").trim()
                line.startsWith("serial=") -> fields["serial"] = line.removePrefix("serial=").trim()
                line.contains("Fingerprint=") -> fields["fingerprint"] = line.substringAfter("Fingerprint=").trim()
            }
        }
        // Check days remaining
        try {
            val (_, endRaw) = runCmd("openssl", "x509", "-enddate", "-noout", "-in", cert.absolutePath)
            val dateStr = endRaw.trim().removePrefix("notAfter=")
            val fmt = java.text.SimpleDateFormat("MMM dd HH:mm:ss yyyy z", java.util.Locale.US)
            val expiry = fmt.parse(dateStr)
            val days = ((expiry.time - System.currentTimeMillis()) / 86400000).toInt()
            fields["daysRemaining"] = days.toString()
            fields["status"] = when { days < 0 -> "expired"; days < 14 -> "expiring"; else -> "valid" }
        } catch (_: Exception) { fields["status"] = "unknown" }
        return fields
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.contentType = "application/json"
        val path = req.pathInfo?.removePrefix("/") ?: ""
        try { when {
            // List all certs with details
            path == "" && req.method == "GET" -> {
                val certs = try {
                    java.io.File("/etc/letsencrypt/live").listFiles()
                        ?.filter { it.isDirectory && it.name != "README" }
                        ?.map { certInfo(it) } ?: emptyList()
                } catch (_: Exception) { emptyList<Map<String, String>>() }
                resp.writer.write(G.toJson(mapOf("certs" to certs)))
            }
            // Issue new cert (standalone, webroot, or dns)
            path == "issue" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val domain = j.get("domain")?.asString ?: throw IllegalArgumentException("domain required")
                val email = j.get("email")?.asString ?: "admin@${domain.removePrefix("*.")}"
                val method = j.get("method")?.asString ?: "standalone"

                val cmd = mutableListOf("sudo", "certbot", "certonly", "--non-interactive", "--agree-tos", "-m", email)

                when (method) {
                    "dns-cloudflare" -> {
                        val creds = j.get("credentials")?.asString ?: throw IllegalArgumentException("credentials path required")
                        cmd.addAll(listOf("--dns-cloudflare", "--dns-cloudflare-credentials", creds, "--dns-cloudflare-propagation-seconds", "30"))
                        cmd.addAll(listOf("-d", domain))
                        // Also add base domain for wildcards
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "dns-route53" -> {
                        cmd.add("--dns-route53")
                        cmd.addAll(listOf("-d", domain))
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "dns-google" -> {
                        val creds = j.get("credentials")?.asString ?: throw IllegalArgumentException("credentials path required")
                        cmd.addAll(listOf("--dns-google", "--dns-google-credentials", creds, "--dns-google-propagation-seconds", "30"))
                        cmd.addAll(listOf("-d", domain))
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "dns-digitalocean" -> {
                        val creds = j.get("credentials")?.asString ?: throw IllegalArgumentException("credentials path required")
                        cmd.addAll(listOf("--dns-digitalocean", "--dns-digitalocean-credentials", creds, "--dns-digitalocean-propagation-seconds", "30"))
                        cmd.addAll(listOf("-d", domain))
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "dns-porkbun" -> {
                        val creds = j.get("credentials")?.asString ?: throw IllegalArgumentException("credentials path required")
                        cmd.addAll(listOf("--dns-porkbun", "--dns-porkbun-credentials", creds, "--dns-porkbun-propagation-seconds", "60"))
                        cmd.addAll(listOf("-d", domain))
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "dns-manual" -> {
                        cmd.addAll(listOf("--manual", "--preferred-challenges", "dns"))
                        cmd.addAll(listOf("-d", domain))
                        if (domain.startsWith("*.")) cmd.addAll(listOf("-d", domain.removePrefix("*.")))
                    }
                    "webroot" -> {
                        val webroot = j.get("webroot")?.asString ?: "/var/www/html"
                        cmd.addAll(listOf("--webroot", "-w", webroot, "-d", domain))
                    }
                    else -> { cmd.addAll(listOf("--standalone", "-d", domain)) }
                }

                ServerLog.add("Issuing cert for $domain (method=$method)...")
                val (exit, output) = runCmd(*cmd.toTypedArray())
                ServerLog.add("Certbot issue exit=$exit: ${output.take(200)}")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.take(500)))
            }
            // Renew all
            path == "renew" && req.method == "POST" -> {
                ServerLog.add("Renewing all certs...")
                val (exit, output) = runCmd("sudo", "certbot", "renew")
                ServerLog.add("Certbot renew exit=$exit")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.take(500)))
            }
            // Revoke a cert
            path == "revoke" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val domain = j.get("domain")?.asString ?: throw IllegalArgumentException("domain required")
                val certPath = "/etc/letsencrypt/live/$domain/cert.pem"
                ServerLog.add("Revoking cert for $domain...")
                val (exit, output) = runCmd("sudo", "certbot", "revoke", "--non-interactive", "--cert-path", certPath)
                ServerLog.add("Certbot revoke exit=$exit")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.take(500)))
            }
            // Delete a cert
            path == "delete" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val domain = j.get("domain")?.asString ?: throw IllegalArgumentException("domain required")
                ServerLog.add("Deleting cert for $domain...")
                val (exit, output) = runCmd("sudo", "certbot", "delete", "--non-interactive", "--cert-name", domain)
                ServerLog.add("Certbot delete exit=$exit")
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to output.take(500)))
            }
            // Setup auto-renew cron
            path == "autorenew" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val enable = j.get("enable")?.asBoolean ?: true
                if (enable) {
                    val cron = "0 3 * * * sudo certbot renew --quiet\n"
                    val (exit, _) = runCmd("bash", "-c", "(crontab -l 2>/dev/null | grep -v certbot; echo '$cron') | crontab -")
                    ServerLog.add("Auto-renew cron ${if (exit == 0) "enabled" else "failed"}")
                    resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to "Auto-renew cron enabled (daily 3am)"))
                } else {
                    val (exit, _) = runCmd("bash", "-c", "crontab -l 2>/dev/null | grep -v certbot | crontab -")
                    ServerLog.add("Auto-renew cron ${if (exit == 0) "disabled" else "failed"}")
                    resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error", "message" to "Auto-renew cron disabled"))
                }
            }
            // Check certbot status
            path == "status" && req.method == "GET" -> {
                val (certbotExit, certbotVer) = runCmd("certbot", "--version")
                val hasCron = try { runCmd("bash", "-c", "crontab -l 2>/dev/null | grep certbot").second.isNotBlank() } catch (_: Exception) { false }
                resp.writer.write(jsonResp(
                    "certbotInstalled" to (certbotExit == 0),
                    "certbotVersion" to certbotVer.trim(),
                    "autoRenewEnabled" to hasCron
                ))
            }
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        } } catch (e: Exception) { resp.status = 500; resp.writer.write(errResp(e.message ?: "Internal error")) }
    }
}

class FileServlet : HttpServlet() {
    private val ROOTS = listOf("/opt/gateway/data", "/etc/letsencrypt", "/etc", "/home", "/tmp")

    private fun safe(path: String): java.io.File {
        val f = java.io.File(path).canonicalFile
        if (ROOTS.none { f.path.startsWith(it) }) throw IllegalArgumentException("Access denied: ${f.path}")
        return f
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.setHeader("Access-Control-Allow-Origin", "*")
        val action = req.pathInfo?.removePrefix("/") ?: ""
        try { when {
            // List directory
            action == "ls" && req.method == "GET" -> {
                val dir = safe(req.getParameter("path") ?: "/opt/gateway/data")
                resp.contentType = "application/json"
                if (!dir.exists()) { resp.writer.write(G.toJson(mapOf("entries" to emptyList<Any>(), "path" to dir.path))); return }
                if (!dir.isDirectory) { resp.writer.write(G.toJson(mapOf("entries" to emptyList<Any>(), "path" to dir.parent, "error" to "Not a directory"))); return }
                val entries = (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name })).map {
                    mapOf("name" to it.name, "path" to it.path, "dir" to it.isDirectory, "size" to it.length(), "modified" to it.lastModified())
                }
                resp.writer.write(G.toJson(mapOf("entries" to entries, "path" to dir.path, "parent" to (dir.parentFile?.path ?: "/"))))
            }
            // Read file
            action == "read" && req.method == "GET" -> {
                val f = safe(req.getParameter("path") ?: throw IllegalArgumentException("path required"))
                if (!f.exists()) throw IllegalArgumentException("Not found: ${f.path}")
                if (f.length() > 2 * 1024 * 1024) throw IllegalArgumentException("File too large (>2MB)")
                resp.contentType = "application/json"
                resp.writer.write(G.toJson(mapOf("path" to f.path, "content" to f.readText(), "size" to f.length())))
            }
            // Write/create file
            action == "write" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val f = safe(j.get("path")?.asString ?: throw IllegalArgumentException("path required"))
                val content = j.get("content")?.asString ?: ""
                f.parentFile?.mkdirs()
                f.writeText(content)
                val perms = j.get("chmod")?.asString
                if (perms != null) ProcessBuilder("chmod", perms, f.path).start().waitFor()
                ServerLog.add("File written: ${f.path} (${content.length} bytes)")
                resp.contentType = "application/json"
                resp.writer.write(jsonResp("status" to "ok", "path" to f.path, "size" to f.length()))
            }
            // Delete file/dir
            action == "delete" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val f = safe(j.get("path")?.asString ?: throw IllegalArgumentException("path required"))
                if (!f.exists()) throw IllegalArgumentException("Not found")
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                ServerLog.add("Deleted: ${f.path}")
                resp.contentType = "application/json"
                resp.writer.write(jsonResp("status" to "ok"))
            }
            // Mkdir
            action == "mkdir" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val f = safe(j.get("path")?.asString ?: throw IllegalArgumentException("path required"))
                f.mkdirs()
                resp.contentType = "application/json"
                resp.writer.write(jsonResp("status" to "ok", "path" to f.path))
            }
            // Download (raw binary)
            action == "download" && req.method == "GET" -> {
                val f = safe(req.getParameter("path") ?: throw IllegalArgumentException("path required"))
                if (!f.exists() || !f.isFile) throw IllegalArgumentException("Not found")
                resp.contentType = "application/octet-stream"
                resp.setHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
                resp.setContentLengthLong(f.length())
                f.inputStream().use { it.copyTo(resp.outputStream) }
            }
            // Upload (multipart)
            action == "upload" && req.method == "POST" -> {
                val dir = safe(req.getParameter("path") ?: "/opt/gateway/data")
                dir.mkdirs()
                val ct = req.contentType ?: ""
                if (ct.contains("multipart")) {
                    req.parts.forEach { part ->
                        val name = part.submittedFileName ?: part.name
                        val dest = java.io.File(dir, name)
                        part.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                        ServerLog.add("Uploaded: ${dest.path} (${dest.length()} bytes)")
                    }
                } else {
                    val name = req.getParameter("name") ?: "upload"
                    val dest = java.io.File(dir, name)
                    req.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    ServerLog.add("Uploaded: ${dest.path} (${dest.length()} bytes)")
                }
                resp.contentType = "application/json"
                resp.writer.write(jsonResp("status" to "ok"))
            }
            else -> { resp.status = 404; resp.contentType = "application/json"; resp.writer.write(errResp("Not found", 404)) }
        } } catch (e: Exception) { resp.status = if (e is IllegalArgumentException) 400 else 500; resp.contentType = "application/json"; resp.writer.write(errResp(e.message ?: "Error")) }
    }
}

class HistoryServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        val limit = req.getParameter("limit")?.toIntOrNull() ?: 50
        resp.contentType = "application/json"
        resp.writer.write(ctx.gson.toJson(mapOf("requests" to ctx.getRequests(limit))))
    }
}

class AuditServlet : HttpServlet() {
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.contentType = "application/json"
        resp.writer.write(ctx.gson.toJson(mapOf("entries" to ctx.getAudit())))
    }
}

class UsersServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        if (ctx.resolveUser(req) != "admin") { resp.status = 403; resp.contentType = "application/json"; resp.writer.write(errResp("Admin only", 403)); return }
        resp.contentType = "application/json"
        val path = req.pathInfo?.removePrefix("/") ?: ""
        when {
            path == "" && req.method == "GET" -> {
                val users = ctx.getUsers().map { mapOf("name" to it.name, "role" to it.role, "providers" to it.providers, "apiKey" to it.apiKey) }
                resp.writer.write(ctx.gson.toJson(mapOf("users" to users)))
            }
            path == "" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val name = j.get("name")?.asString ?: throw IllegalArgumentException("name required")
                val key = j.get("apiKey")?.asString ?: UUID.randomUUID().toString()
                val role = j.get("role")?.asString ?: "user"
                val providers = j.get("providers")?.asJsonArray?.map { it.asString } ?: emptyList()
                ctx.addUser(GatewayServer.UserAccount(name, key, role, providers))
                ctx.audit(ctx.resolveUser(req), "user_add", name)
                resp.writer.write(jsonResp("status" to "ok", "apiKey" to key))
            }
            path.isNotEmpty() && req.method == "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val user = ctx.findUser(ctx.getUsers().find { it.name == path }?.apiKey ?: "") ?: throw IllegalArgumentException("User not found: $path")
                val providers = j.get("providers")?.asJsonArray?.map { it.asString } ?: user.providers
                val role = j.get("role")?.asString ?: user.role
                ctx.removeUser(path)
                ctx.addUser(GatewayServer.UserAccount(user.name, user.apiKey, role, providers))
                ctx.audit(ctx.resolveUser(req), "user_update", "$path providers=${providers.joinToString(",")}")
                resp.writer.write(jsonResp("status" to "ok"))
            }
            path.isNotEmpty() && req.method == "DELETE" -> {
                ctx.removeUser(path)
                ctx.audit(ctx.resolveUser(req), "user_delete", path)
                resp.writer.write(jsonResp("status" to "ok"))
            }
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        }
    }
}

class WebhookServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        resp.contentType = "application/json"
        when (req.method) {
            "GET" -> resp.writer.write(jsonResp("url" to ctx.webhookUrl))
            "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                ctx.webhookUrl = j.get("url")?.asString ?: ""
                ctx.saveWebhooks()
                ctx.audit(ctx.resolveUser(req), "webhook_update", ctx.webhookUrl)
                resp.writer.write(jsonResp("status" to "ok"))
            }
            "POST" -> { // Test webhook
                ctx.sendWebhook("test", "Test notification from AIOPE Gateway")
                resp.writer.write(jsonResp("status" to "ok", "message" to "Test sent"))
            }
            else -> { resp.status = 405 }
        }
    }
}

class BackupServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; return }
        val path = req.pathInfo?.removePrefix("/") ?: ""
        when {
            // Download backup as tar.gz
            path == "export" && req.method == "GET" -> {
                ctx.audit(ctx.resolveUser(req), "backup_export")
                resp.contentType = "application/gzip"
                resp.setHeader("Content-Disposition", "attachment; filename=\"gateway-backup-${System.currentTimeMillis()}.tar.gz\"")
                val p = ProcessBuilder("tar", "czf", "-", "-C", "/opt/gateway", "data").start()
                p.inputStream.use { it.copyTo(resp.outputStream) }
                p.waitFor()
            }
            // Upload and restore backup
            path == "import" && req.method == "POST" -> {
                val tmp = java.io.File.createTempFile("backup", ".tar.gz")
                req.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val p = ProcessBuilder("tar", "xzf", tmp.absolutePath, "-C", "/opt/gateway").start()
                val exit = p.waitFor()
                tmp.delete()
                ctx.getGateway().loadConfig()
                ctx.loadUsers()
                ctx.loadWebhooks()
                ctx.audit(ctx.resolveUser(req), "backup_import")
                ServerLog.add("Backup restored (exit=$exit)")
                resp.contentType = "application/json"
                resp.writer.write(jsonResp("status" to if (exit == 0) "ok" else "error"))
            }
            else -> { resp.status = 404; resp.contentType = "application/json"; resp.writer.write(errResp("Not found", 404)) }
        }
    }
}

class KeysServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; resp.contentType = "application/json"; resp.writer.write(errResp("Unauthorized", 401)); return }
        if (ctx.resolveUser(req) != "admin") { resp.status = 403; resp.contentType = "application/json"; resp.writer.write(errResp("Admin only", 403)); return }
        resp.contentType = "application/json"; resp.setHeader("Access-Control-Allow-Origin", "*")
        if (req.method == "OPTIONS") { resp.setHeader("Access-Control-Allow-Methods", "GET,PUT,DELETE,OPTIONS"); resp.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization"); resp.status = 200; return }
        val path = req.pathInfo?.removePrefix("/") ?: ""
        when {
            path == "" && req.method == "GET" -> {
                // Return keys with values masked
                val masked = ctx.getApiKeys().mapValues { (_, v) -> if (v.length > 8) "${v.take(4)}...${v.takeLast(4)}" else "***" }
                resp.writer.write(G.toJson(mapOf("keys" to masked)))
            }
            path == "" && req.method == "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                j.entrySet().forEach { (k, v) ->
                    val value = v.asString.trim()
                    if (value.isNotEmpty() && value != "***" && !value.contains("...")) {
                        ctx.setApiKey(k, value)
                        ServerLog.add("API key set: $k")
                        ctx.audit(ctx.resolveUser(req), "key_set", k)
                    }
                }
                resp.writer.write(jsonResp("status" to "ok"))
            }
            path.isNotEmpty() && req.method == "DELETE" -> {
                ctx.removeApiKey(path)
                ServerLog.add("API key removed: $path")
                ctx.audit(ctx.resolveUser(req), "key_delete", path)
                resp.writer.write(jsonResp("status" to "ok"))
            }
            else -> { resp.status = 404; resp.writer.write(errResp("Not found", 404)) }
        }
    }
}
