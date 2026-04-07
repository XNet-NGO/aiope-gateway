package com.aiope.gateway

const val LOGIN_HTML = """<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>AIOPE Gateway</title>
<style>body{font-family:-apple-system,sans-serif;background:#1a1a1a;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}.login{background:#2d2d2d;padding:2rem;border-radius:12px;width:100%;max-width:360px}h1{margin:0 0 1.5rem;font-size:1.5rem;color:#fff;text-align:center}input{width:100%;padding:12px;margin-bottom:1rem;background:#3d3d3d;border:1px solid #555;border-radius:6px;color:#fff;font-size:1rem;box-sizing:border-box}input:focus{outline:none;border-color:#7c4dff}button{width:100%;padding:12px;background:#7c4dff;border:none;border-radius:6px;color:#fff;font-size:1rem;cursor:pointer}.hint{font-size:.85rem;color:#888;text-align:center;margin-top:1rem}</style>
</head><body><form class="login" method="POST"><h1>AIOPE Gateway</h1><!--ERROR--><input type="password" name="password" placeholder="Enter API Key" required autofocus><button type="submit">Login</button><p class="hint">Use your gateway API key as password</p></form></body></html>"""

object PortalHtml {
    val html: String by lazy {
        PortalHtml::class.java.getResourceAsStream("/portal.html")?.bufferedReader()?.readText() ?: "Portal resource not found"
    }
}
