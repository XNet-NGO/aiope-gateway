# AIOPE Gateway

[![Built with Pollinations.ai](https://img.shields.io/badge/Built_with-Pollinations.ai-blue)](https://pollinations.ai)

OpenAI-compatible LLM proxy with a full web admin portal. Routes requests to multiple upstream providers through a single endpoint. Designed to run as a lightweight appliance on a Raspberry Pi, cloud instance, or any JVM host.

## Features

- **Multi-provider routing** — 9+ pre-configured providers, add unlimited custom ones
- **Full OpenAI API compatibility** — chat, completions, embeddings, rerank, audio (TTS/STT), images, moderations, responses API
- **Web admin portal** — manage everything from the browser, no CLI needed
- **Multi-model selection** — load model catalogs, enable/disable models per provider via scrollable dials
- **Response caching** — Caffeine in-memory cache (2000 entries, 30min TTL, SHA-256 keyed)
- **SSE streaming** — transparent streaming proxy, chunk-by-chunk passthrough
- **Multi-user auth** — admin + user accounts with separate API keys
- **TLS/HTTPS** — auto-detects Let's Encrypt certs, serves on port 443, HTTP-to-HTTPS redirect
- **Certificate management** — issue, renew, revoke, delete certs from the portal (standalone, webroot, DNS-01 via Cloudflare, Route53, Google, DigitalOcean, Porkbun)
- **WiFi management** — scan, connect, disconnect, forget networks (via NetworkManager)
- **File manager** — browse, edit, create, upload, download files from the portal
- **Web shell** — embedded terminal via ttyd
- **System stats** — uptime, CPU temp, load, RAM, disk, JVM heap, cache stats (auto-refreshing)
- **Network config** — hostname, DNS, interface info
- **Request history** — last 500 requests with latency, status, user
- **Audit log** — tracks logins, user management, config changes
- **Rate limiting** — 120 req/min per IP (liberal, prevents DoS)
- **Brute force protection** — 10 failed logins in 15min blocks the IP
- **Webhooks** — send notifications to Slack, Discord, or any HTTP endpoint
- **Backup/restore** — export/import config as tar.gz
- **API key rotation** — one-click key generation
- **PWA support** — add-to-homescreen on mobile
- **Dark mode** — always-on dark theme

## Quick Start

### Docker

```bash
docker run -d --name gateway --network host \
  -v gateway-data:/opt/gateway/data \
  -v /etc/letsencrypt:/etc/letsencrypt \
  aiope-gateway:latest
```

### Bare JAR

```bash
java -Xmx256m -jar gateway-server-all.jar 8082 ./data
```

### Raspberry Pi

```bash
sudo ./deploy/flash-pi3.sh /dev/sdX
```

## Access

| Service | URL |
|---------|-----|
| Portal | `http://<ip>:8082/portal/` |
| API | `http://<ip>:8082/v1/chat/completions` |
| Models | `http://<ip>:8082/v1/models` |
| Health | `http://<ip>:8082/health` |
| Web Shell | `http://<ip>:7681` |

Default API key: `aiope-gateway-key` — change immediately after first login.

## Pre-configured Providers

| Provider | API Base | Auth | Notes |
|----------|----------|------|-------|
| Pollinations | text.pollinations.ai | None | 3 free models (openai, openai-large, openai-fast) |
| Pollinations Gen | gen.pollinations.ai | API key | 65+ paid models (audio, image, video, code) |
| GitHub Models | models.github.ai | GitHub PAT | Free tier available |
| Google AI Studio | generativelanguage.googleapis.com | API key | Free tier (Gemma models) |
| Cloudflare Workers AI | api.cloudflare.com | API key | Free tier |
| OpenRouter | openrouter.ai | API key | Free models available |
| Cohere | api.cohere.ai | API key | Free tier |
| Cline | api.cline.bot | API key | 3 free models |
| Zen (OpenCode) | opencode.ai | None | Free models |
| Bedrock Mantle | bedrock-mantle.*.api.aws | Bedrock API key | OSS models, live model discovery |
| Bedrock Runtime | bedrock-runtime.*.amazonaws.com | Bedrock API key | Responses API support |

## API Endpoints

### LLM Proxy (all require auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/models` | List enabled models |
| POST | `/v1/chat/completions` | Chat completion (streaming + non-streaming) |
| POST | `/v1/completions` | Legacy completions |
| POST | `/v1/embeddings` | Text embeddings |
| POST | `/v1/rerank` | Reranking |
| POST | `/v1/audio/speech` | Text-to-speech (returns audio bytes) |
| POST | `/v1/audio/transcriptions` | Speech-to-text (multipart upload) |
| POST | `/v1/audio/translations` | Audio translation (multipart upload) |
| POST | `/v1/images/generations` | Image generation |
| POST | `/v1/images/edits` | Image editing (multipart upload) |
| POST | `/v1/images/variations` | Image variations (multipart upload) |
| POST | `/v1/moderations` | Content moderation |
| POST | `/v1/responses` | Responses API (stateful, async) |
| GET | `/v1/responses/{id}` | Retrieve response status |

### Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Server status (no auth required) |
| GET | `/api/stats` | System stats + cache stats |
| GET | `/api/config` | Gateway config |
| PUT | `/api/config` | Update gateway API key |
| GET | `/api/config/providers` | List all providers |
| POST | `/api/config/providers` | Add provider |
| PUT | `/api/config/providers/:name` | Update provider |
| DELETE | `/api/config/providers/:name` | Remove provider |
| POST | `/api/config/providers/:name/models/add` | Enable a model |
| POST | `/api/config/providers/:name/models/remove` | Disable a model |
| POST | `/api/config/loadmodels` | Fetch model catalog from provider |
| POST | `/admin` | Admin actions: start, stop, shutdown, cache_clear, cache_on, cache_off |

### Network & System

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/network` | Hostname, interfaces, DNS, gateway |
| PUT | `/api/network/hostname` | Set hostname |
| PUT | `/api/network/dns` | Set DNS servers |
| GET | `/api/wifi` | WiFi status |
| GET | `/api/wifi/scan` | Scan networks |
| POST | `/api/wifi/connect` | Connect to network |
| POST | `/api/wifi/disconnect` | Disconnect |
| POST | `/api/wifi/forget` | Forget saved network |
| GET | `/api/wifi/saved` | List saved networks |

### Certificates

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/certs` | List certs with expiry, issuer, status |
| GET | `/api/certs/status` | Certbot version, auto-renew status |
| POST | `/api/certs/issue` | Issue cert (standalone, webroot, DNS-01) |
| POST | `/api/certs/renew` | Renew all certs |
| POST | `/api/certs/revoke` | Revoke cert |
| POST | `/api/certs/delete` | Delete cert |
| POST | `/api/certs/autorenew` | Toggle auto-renew cron |

### Files

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/files/ls?path=` | List directory |
| GET | `/api/files/read?path=` | Read file (max 2MB) |
| POST | `/api/files/write` | Create/edit file (supports chmod) |
| POST | `/api/files/delete` | Delete file or directory |
| POST | `/api/files/mkdir` | Create directory |
| GET | `/api/files/download?path=` | Download file |
| POST | `/api/files/upload?path=` | Upload files (multipart, max 50MB) |

### Users & Auth

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/users` | List users (admin only) |
| POST | `/api/users` | Create user (returns API key) |
| DELETE | `/api/users/:name` | Delete user |
| GET | `/api/history` | Request history |
| GET | `/api/audit` | Audit log |
| GET/PUT/POST | `/api/webhooks` | Get/set/test webhook URL |
| GET | `/api/backup/export` | Download config backup |
| POST | `/api/backup/import` | Restore config backup |

## Authentication

All endpoints except `/health` require authentication:

- **Bearer token**: `Authorization: Bearer YOUR_API_KEY`
- **Session cookie**: set after portal login

Admin uses the gateway API key. Additional users get their own keys via the Users section.

## Provider Configuration

Each provider has:

- **Name** — unique identifier
- **API Base** — upstream URL
- **Path** — endpoint override (default `/chat/completions`, change to `/embeddings`, `/audio/speech`, etc.)
- **API Key** — upstream auth token
- **Enabled** — on/off toggle

Sub-providers (individual models) inherit API key, base URL, and path from their parent provider automatically.

## Model Selection

1. Add API key to a provider
2. Click **Load** to fetch the model catalog
3. Click models in the **Available** dial to enable them
4. Enabled models appear in the **Enabled** dial (click **x** to remove)
5. Models persist in `config.json` across restarts

Clients request models as `provider-name/model-slug` (e.g. `pollinations/openai-fast`).

## Caching

- **Engine**: Caffeine (in-process, JVM)
- **Capacity**: 2000 entries, LRU eviction
- **TTL**: 30 minutes
- **Key**: SHA-256 of model + messages + temperature
- **Scope**: non-streaming requests only
- **Controls**: Clear Cache button in portal, admin API (`cache_clear`, `cache_on`, `cache_off`)

## TLS / Wildcard Certificates

Issue Let's Encrypt certificates from the portal. Supported challenge methods:

| Method | Use Case | Credentials |
|--------|----------|-------------|
| Standalone | Port 80 available | None |
| Webroot | Existing web server | Webroot path |
| DNS - Cloudflare | Wildcard certs | `dns_cloudflare_api_token = TOKEN` |
| DNS - Route53 | Wildcard certs | AWS env vars or instance role |
| DNS - Google Cloud | Wildcard certs | Service account JSON |
| DNS - DigitalOcean | Wildcard certs | `dns_digitalocean_token = TOKEN` |
| DNS - Porkbun | Wildcard certs | `dns_porkbun_key` + `dns_porkbun_secret` |
| DNS - Manual | Any | Manual TXT record |

For wildcards (`*.example.com`), use any DNS method. Create credential files via the Files section.

## Security

- **Rate limiting**: 120 requests/min per IP on API endpoints
- **Brute force protection**: 10 failed logins in 15min blocks the IP for 15min
- **File sandbox**: file manager restricted to `/opt/gateway/data`, `/etc`, `/home`, `/tmp`, `/bin`, `/usr`, `/var`, `/opt`
- **Audit log**: tracks logins, user changes, config changes, backups
- **No default secrets**: API keys must be configured before models work

## Performance Tuning

| Setting | Value |
|---------|-------|
| Jetty thread pool | 500 max, 16 min |
| OkHttp connections (text) | 32, 5min keepalive |
| OkHttp connections (media) | 8 dedicated, 5min keepalive |
| Read timeout (text/chat) | 120 seconds |
| Read timeout (media) | 600 seconds (10 min) |
| Write timeout (media) | 120 seconds |
| Cache entries | 2000 |

## Deployment

### AWS (Graviton)

```bash
# On the instance
sudo apt update && sudo apt install -y openjdk-21-jre-headless certbot ttyd
mkdir -p /opt/gateway/data
# Copy JAR
scp build/libs/gateway-server-all.jar user@host:/opt/gateway/gateway.jar
# Copy systemd service
sudo cp deploy/aiope-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aiope-gateway
```

### Docker

```bash
docker build -t aiope-gateway .
docker run -d --name gateway --network host \
  -v /opt/gateway/data:/opt/gateway/data \
  -v /etc/letsencrypt:/etc/letsencrypt \
  aiope-gateway
```

### Raspberry Pi (bare metal)

```bash
sudo ./deploy/flash-pi3.sh /dev/sdX
# Insert SD, power on, wait 3-5 min
# ssh aiope@aiope-gw.local (password: aiope)
```

## Ports

| Port | Service |
|------|---------|
| 8082 | Gateway API + Web Portal |
| 443 | HTTPS (auto-enabled with certs) |
| 80 | HTTP (redirects to HTTPS when certs present) |
| 7681 | Web Shell (ttyd) |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GATEWAY_PORT` | 8082 | Gateway port |
| `TTYD_PORT` | 7681 | Web shell port |
| `DATA_DIR` | /opt/gateway/data | Data directory |

## Client Examples

### curl

```bash
curl http://GATEWAY:8082/v1/chat/completions \
  -H "Authorization: Bearer YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"pollinations/openai-fast","messages":[{"role":"user","content":"hello"}]}'
```

### Python (openai library)

```python
from openai import OpenAI
client = OpenAI(base_url="http://GATEWAY:8082/v1", api_key="YOUR_KEY")
r = client.chat.completions.create(
    model="pollinations/openai-fast",
    messages=[{"role": "user", "content": "hello"}]
)
print(r.choices[0].message.content)
```

### AIOPE IDE

Settings > Providers > Add custom provider:
- API Base: `http://GATEWAY:8082/v1`
- API Key: your gateway key

## Build from Source

```bash
./gradlew shadowJar
# Output: build/libs/gateway-server-all.jar (~8MB)
```

## Architecture

```
Client (AIOPE/curl/Python/any OpenAI-compatible app)
  │
  ▼
AIOPE Gateway (Jetty 9.4 + OkHttp 4.12 + Caffeine)
  ├── /v1/*              → LLM proxy (routes to upstream providers)
  ├── /portal/           → Web admin UI
  ├── /api/config/*      → Provider management
  ├── /api/wifi/*        → WiFi management (nmcli)
  ├── /api/network/*     → Network config
  ├── /api/certs/*       → Certificate management (certbot)
  ├── /api/files/*       → File manager
  ├── /api/stats         → System metrics
  ├── /api/users/*       → User management
  ├── /api/webhooks/*    → Webhook config
  ├── /api/backup/*      → Backup/restore
  ├── /api/history       → Request history
  ├── /api/audit         → Audit log
  ├── /api/logs          → Server log (polling)
  ├── /admin             → Start/stop/shutdown/cache
  └── /health            → Health check
  │
  ▼
ttyd (port 7681) → Web shell
```

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 1.9.22 |
| Jetty | 9.4.54 |
| OkHttp | 4.12.0 |
| Caffeine | 3.1.8 |
| Gson | 2.10.1 |
| Shadow (fat JAR) | 8.1.1 |
| JVM target | 17+ |

## Powered By

- [Pollinations.ai](https://pollinations.ai) — Open-source generative AI platform

## License

Proprietary — XNet NGO

---

*AIOPE Gateway by [XNet](https://xnet.ngo)*
