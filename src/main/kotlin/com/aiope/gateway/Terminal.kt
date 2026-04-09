package com.aiope.gateway

import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.servlet.WebSocketServlet
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory
import org.eclipse.jetty.websocket.servlet.WebSocketCreator
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class TerminalServlet : WebSocketServlet() {
    override fun configure(factory: WebSocketServletFactory) {
        factory.policy.maxTextMessageSize = 65536
        factory.policy.maxBinaryMessageSize = 65536
        factory.policy.idleTimeout = 600_000
        factory.creator = WebSocketCreator { req, _ ->
            val httpReq = req.httpServletRequest
            val ctx = httpReq.servletContext.getAttribute("gateway") as GatewayServer
            if (!ctx.isAuthorized(httpReq)) null else TerminalSocket()
        }
    }
}

class TerminalSocket : WebSocketAdapter() {
    private var process: Process? = null
    private var readerThread: Thread? = null

    override fun onWebSocketConnect(sess: Session) {
        super.onWebSocketConnect(sess)

        // Use script(1) to allocate a real PTY for bash
        // -q = quiet, -c = command, /dev/null = typescript output
        val pb = ProcessBuilder(
            "/usr/bin/script", "-q", "-c",
            "TERM=xterm-256color COLORTERM=truecolor exec bash --login",
            "/dev/null"
        )
        pb.environment()["TERM"] = "xterm-256color"
        pb.environment()["COLORTERM"] = "truecolor"
        pb.environment()["LANG"] = "C.UTF-8"
        pb.redirectErrorStream(true)
        process = pb.start()

        // Reader thread: read from process stdout and send to websocket
        readerThread = Thread({
            try {
                val buf = ByteArray(4096)
                val input = process!!.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (isConnected) {
                        remote.sendString(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {}
            try { if (isConnected) session.close(1000, "Shell exited") } catch (_: Exception) {}
        }, "terminal-reader")
        readerThread!!.isDaemon = true
        readerThread!!.start()
    }

    override fun onWebSocketText(message: String) {
        try {
            // Ignore resize messages (can't resize without ioctl on the PTY fd)
            if (message.startsWith("{") && message.contains("cols")) return
            process?.outputStream?.let {
                it.write(message.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {}
    }

    override fun onWebSocketClose(statusCode: Int, reason: String?) {
        super.onWebSocketClose(statusCode, reason)
        cleanup()
    }

    override fun onWebSocketError(cause: Throwable?) {
        cleanup()
    }

    private fun cleanup() {
        try { process?.outputStream?.close() } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
