package com.aiope.gateway

const val LOGIN_HTML = """<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>AIOPE Gateway</title>
<style>body{font-family:-apple-system,sans-serif;background:#1a1a1a;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}.login{background:#2d2d2d;padding:2rem;border-radius:12px;width:100%;max-width:360px}h1{margin:0 0 1.5rem;font-size:1.5rem;color:#fff;text-align:center}input{width:100%;padding:12px;margin-bottom:1rem;background:#3d3d3d;border:1px solid #555;border-radius:6px;color:#fff;font-size:1rem;box-sizing:border-box}input:focus{outline:none;border-color:#7c4dff}button{width:100%;padding:12px;background:#7c4dff;border:none;border-radius:6px;color:#fff;font-size:1rem;cursor:pointer}.hint{font-size:.85rem;color:#888;text-align:center;margin-top:1rem}</style>
</head><body><form class="login" method="POST"><h1>AIOPE Gateway</h1><!--ERROR--><!--CSRF--><input type="password" name="password" placeholder="Enter API Key" required autofocus><button type="submit">Login</button><p class="hint">Use your gateway API key as password</p></form></body></html>"""

const val SHELL_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Shell</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.min.css">
<style>html,body{margin:0;padding:0;height:100%;overflow:hidden;background:#121212}#terminal{height:100%}</style>
</head><body><div id="terminal"></div>
<script src="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@xterm/addon-fit@0.10.0/lib/addon-fit.min.js"></script>
<script>
var t=new Terminal({cursorBlink:true,fontSize:14,theme:{background:'#121212',foreground:'#e0e0e0'}}),
f=new FitAddon.FitAddon();t.loadAddon(f);t.open(document.getElementById('terminal'));f.fit();
var ws=new WebSocket('{{WS_URL}}');
ws.onopen=function(){t.focus();ws.send(JSON.stringify({cols:t.cols,rows:t.rows}))};
ws.onmessage=function(e){t.write(e.data)};
ws.onclose=function(){t.write('\r\n\x1b[31m[Connection closed]\x1b[0m\r\n')};
t.onData(function(d){ws.readyState===1&&ws.send(d)});
window.addEventListener('resize',function(){f.fit();ws.readyState===1&&ws.send(JSON.stringify({cols:t.cols,rows:t.rows}))});
</script></body></html>"""

object PortalHtml {
    val html: String by lazy {
        PortalHtml::class.java.getResourceAsStream("/portal.html")?.bufferedReader()?.readText() ?: "Portal resource not found"
    }
}
