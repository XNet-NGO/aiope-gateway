package com.aiope.gateway

import kotlinx.coroutines.*
import org.eclipse.jetty.server.*
import org.eclipse.jetty.servlet.*
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer
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
    data class RequestRecord(val id: Long, val ts: Long, val model: String, val endpoint: String, val status: Int, val latencyMs: Long, val user: String, val ip: String = "")
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


    // Portal authentication (separate from API keys)
    data class PortalCreds(val username: String, val passwordHash: String, val totpSecret: String = "")
    private var portalCreds: PortalCreds? = null
    private val portalCredsFile = File(dataDir, "portal_auth.json")
    fun loadPortalCreds() {
        try {
            if (portalCredsFile.exists()) {
                val o = com.google.gson.JsonParser.parseString(portalCredsFile.readText()).asJsonObject
                portalCreds = PortalCreds(
                    o.get("username")?.asString ?: "xnet-admin",
                    o.get("passwordHash")?.asString ?: "",
                    o.get("totpSecret")?.asString ?: ""
                )
            }
        } catch (_: Exception) {}
        // Default creds if none exist
        if (portalCreds == null || portalCreds!!.passwordHash.isBlank()) {
            portalCreds = PortalCreds("xnet-admin", org.mindrot.jbcrypt.BCrypt.hashpw("!1nfer!", org.mindrot.jbcrypt.BCrypt.gensalt()))
            savePortalCreds()
        }
    }
    fun savePortalCreds() { portalCredsFile.writeText(gson.toJson(portalCreds)) }
    fun getPortalCreds() = portalCreds!!
    fun setPortalTotp(secret: String) { portalCreds = portalCreds!!.copy(totpSecret = secret); savePortalCreds() }
    fun changePortalPassword(newPassword: String) {
        val hash = org.mindrot.jbcrypt.BCrypt.hashpw(newPassword, org.mindrot.jbcrypt.BCrypt.gensalt())
        portalCreds = portalCreds!!.copy(passwordHash = hash)
        savePortalCreds()
    }
    fun changePortalUsername(newUsername: String) {
        portalCreds = portalCreds!!.copy(username = newUsername)
        savePortalCreds()
    }
    fun verifyPortalLogin(username: String, password: String, totp: String): Boolean {
        val creds = portalCreds ?: return false
        if (username != creds.username) return false
        if (!org.mindrot.jbcrypt.BCrypt.checkpw(password, creds.passwordHash)) return false
        if (creds.totpSecret.isNotBlank()) {
            val verifier = dev.samstevens.totp.code.DefaultCodeVerifier(dev.samstevens.totp.code.DefaultCodeGenerator(), dev.samstevens.totp.time.SystemTimeProvider())
            if (!verifier.isValidCode(creds.totpSecret, totp)) return false
        }
        return true
    }

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

        handler.addServlet(HistoryServlet::class.java, "/api/history")
        handler.addServlet(AuditServlet::class.java, "/api/audit")
        handler.addServlet(UsersServlet::class.java, "/api/users/*")
        handler.addServlet(WebhookServlet::class.java, "/api/webhooks/*")

        handler.addServlet(KeysServlet::class.java, "/api/keys/*")
        handler.addServlet(RealtimeServlet::class.java, "/ws/voice/*")
        handler.setAttribute("gateway", this)

        // Main connector (default port)
        val httpConfig = HttpConfiguration().apply { sendServerVersion = false; securePort = 443 }
        val mainConnector = ServerConnector(server, HttpConnectionFactory(httpConfig))
        mainConnector.host = "0.0.0.0"; mainConnector.port = port
        server.addConnector(mainConnector)

        server.handler = handler
        println("[Gateway] Server started on http://0.0.0.0:$port")
        server.start(); server.join()
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
    server.loadPortalCreds()
    server.start()
}

class LoginServlet : HttpServlet() {
    private val csrfTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (ctx.isBlocked(req.remoteAddr)) { resp.status = 429; resp.writer.write("Too many failed attempts. Try again later."); return }
        val now = System.currentTimeMillis()
        csrfTokens.entries.removeIf { now - it.value > 600_000 }
        val csrf = UUID.randomUUID().toString()
        csrfTokens[csrf] = now
        val needsTotp = ctx.getPortalCreds().totpSecret.isNotBlank()
        resp.contentType = "text/html; charset=utf-8"
        resp.writer.write(LOGIN_HTML
            .replace("<!--CSRF-->", "<input type=\"hidden\" name=\"csrf\" value=\"$csrf\">")
            .replace("<!--TOTP-->", if (needsTotp) "<input type=\"text\" name=\"totp\" placeholder=\"TOTP Code\" autocomplete=\"one-time-code\" inputmode=\"numeric\" maxlength=\"6\">" else ""))
    }
    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        val ip = req.remoteAddr
        if (ctx.isBlocked(ip)) { resp.status = 429; resp.writer.write("Too many failed attempts. Try again later."); return }
        val csrf = req.getParameter("csrf") ?: ""
        if (csrf.isEmpty() || csrfTokens.remove(csrf) == null) { resp.status = 403; resp.contentType = "text/html; charset=utf-8"; resp.writer.write(LOGIN_HTML.replace("<!--ERROR-->", "<p style='color:#f44336'>Session expired. Please refresh.</p>")); return }
        val username = req.getParameter("username") ?: ""
        val password = req.getParameter("password") ?: ""
        val totp = req.getParameter("totp") ?: ""
        if (ctx.verifyPortalLogin(username, password, totp)) {
            val token = UUID.randomUUID().toString()
            ctx.sessions[token] = GatewayServer.SessionInfo(token, username)
            resp.addCookie(Cookie("gateway_session", token).apply { path = "/"; maxAge = 86400 * 7; isHttpOnly = true; secure = true })
            ctx.audit(username, "login", "from $ip")
            resp.sendRedirect("/portal/")
        } else {
            ctx.recordFailedLogin(ip)
            ctx.audit(username.ifBlank { "unknown" }, "login_failed", "from $ip")
            resp.contentType = "text/html; charset=utf-8"; resp.status = 401; resp.writer.write(LOGIN_HTML.replace("<!--ERROR-->", "<p style='color:#f44336'>Invalid credentials</p>"))
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
            path == "portal-auth" && req.method == "GET" -> { resp.writer.write(jsonResp("totpEnabled" to ctx.getPortalCreds().totpSecret.isNotBlank(), "username" to ctx.getPortalCreds().username)) }
            path == "portal-auth" && req.method == "PUT" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                j.get("username")?.asString?.let { u ->
                    if (u.isNotBlank()) ctx.changePortalUsername(u)
                    ctx.audit(ctx.resolveUser(req), "username_changed", u)
                }
                j.get("password")?.asString?.let { pw ->
                    if (pw.length < 4) throw IllegalArgumentException("Password too short")
                    ctx.changePortalPassword(pw)
                    ctx.audit(ctx.resolveUser(req), "password_changed", "")
                }
                resp.writer.write(jsonResp("status" to "ok"))
            }
            path == "portal-auth/totp-setup" && req.method == "POST" -> {
                val secret = dev.samstevens.totp.secret.DefaultSecretGenerator().generate()
                req.session.setAttribute("pendingTotpSecret", secret)
                resp.writer.write(jsonResp("secret" to secret))
            }
            path == "portal-auth/totp-verify" && req.method == "POST" -> {
                val j = JsonParser.parseString(req.reader.readText()).asJsonObject
                val code = j.get("code")?.asString ?: ""
                val secret = req.session.getAttribute("pendingTotpSecret") as? String ?: throw IllegalArgumentException("No pending TOTP setup")
                val verifier = dev.samstevens.totp.code.DefaultCodeVerifier(dev.samstevens.totp.code.DefaultCodeGenerator(), dev.samstevens.totp.time.SystemTimeProvider())
                if (verifier.isValidCode(secret, code)) {
                    ctx.setPortalTotp(secret)
                    req.session.removeAttribute("pendingTotpSecret")
                    ctx.audit(ctx.resolveUser(req), "totp_enabled", "")
                    resp.writer.write(jsonResp("status" to "ok"))
                } else { resp.status = 400; resp.writer.write(errResp("Invalid code", 400)) }
            }
            path == "portal-auth/totp-disable" && req.method == "POST" -> {
                ctx.setPortalTotp("")
                ctx.audit(ctx.resolveUser(req), "totp_disabled", "")
                resp.writer.write(jsonResp("status" to "ok"))
            }
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
    private fun cors(resp: HttpServletResponse) { resp.setHeader("Access-Control-Allow-Origin", "https://inf.xnet.ngo"); resp.setHeader("Access-Control-Allow-Credentials", "true"); resp.setHeader("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,OPTIONS"); resp.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization") }
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
        var requestModel = ""
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
        finally { requestModel = req.getAttribute("requestModel") as? String ?: ""; val ip = req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim() ?: req.remoteAddr; ctx.addRequest(GatewayServer.RequestRecord(ctx.requestCount, System.currentTimeMillis(), requestModel, path, status, System.currentTimeMillis() - start, user, ip)) }
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
        if (model.isBlank()) { resp.status = 400; resp.writer.write("""{"error":{"message":"model field is required"}}"""); return }
        val stream = rj.get("stream")?.asBoolean ?: false
        req.setAttribute("requestModel", model)
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
        if (model.isBlank()) { resp.status = 400; resp.writer.write("""{"error":{"message":"model field is required"}}"""); return }
        req.setAttribute("requestModel", model)
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
        if (ctx.resolveUser(req) != "admin" && ctx.resolveUser(req) != ctx.getPortalCreds().username) { resp.status = 403; resp.contentType = "application/json"; resp.writer.write(errResp("Admin only", 403)); return }
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

class KeysServlet : HttpServlet() {
    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        val ctx = req.servletContext.getAttribute("gateway") as GatewayServer
        if (!ctx.isAuthorized(req)) { resp.status = 401; resp.contentType = "application/json"; resp.writer.write(errResp("Unauthorized", 401)); return }
        if (ctx.resolveUser(req) != "admin" && ctx.resolveUser(req) != ctx.getPortalCreds().username) { resp.status = 403; resp.contentType = "application/json"; resp.writer.write(errResp("Admin only", 403)); return }
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
