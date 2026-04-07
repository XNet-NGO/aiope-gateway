# AIOPE Gateway Appliance

Zero-config LLM gateway for Raspberry Pi. Flash, boot, use.

## What You Get

- OpenAI-compatible API proxy on port 8082
- Web admin portal with provider management
- WiFi connection manager
- TLS certificate management (Let's Encrypt + wildcard via DNS)
- System stats (CPU temp, RAM, disk, load)
- Network configuration (hostname, DNS)
- Web shell access (ttyd on port 7681)
- 9 pre-configured free/freemium LLM providers

## Quick Start

### Option A: Flash SD Card (Raspberry Pi)

```bash
# Download and flash
git clone https://github.com/xnet-admin-1/gateway-server.git
cd gateway-server
sudo ./deploy/flash.sh /dev/sdX
```

Insert SD, power on Pi. Gateway auto-starts.

### Option B: Docker (Any Linux)

```bash
docker run -d --name gateway \
  --network host \
  -v gateway-data:/opt/gateway/data \
  -v /etc/letsencrypt:/etc/letsencrypt \
  ghcr.io/xnet-admin-1/aiope-gateway:latest
```

### Option C: Bare JAR (Any JVM)

```bash
java -jar gateway-server-all.jar 8082 ./data
```

## Access

| Service | URL |
|---------|-----|
| Portal | `http://<ip>:8082/portal/` |
| API | `http://<ip>:8082/v1/chat/completions` |
| Models | `http://<ip>:8082/v1/models` |
| Health | `http://<ip>:8082/health` |
| Shell | `http://<ip>:7681` |

Default API key: `aiope-gateway-key` (change in portal)

Find Pi on network: `ping aiope-gateway.local`

## First Boot Checklist

1. Open `http://<pi-ip>:8082` in browser
2. Login with API key (`aiope-gateway-key`)
3. Connect to WiFi (if not using ethernet)
4. Add API keys to providers you want to use
5. Change the gateway API key
6. (Optional) Issue TLS cert for your domain
7. (Optional) Set hostname

## Client Configuration

Point any OpenAI-compatible client at the gateway:

```
API Base: http://<ip>:8082/v1
API Key:  aiope-gateway-key
Model:    <provider>/<model>  (e.g. pollinations/openai-fast)
```

### AIOPE IDE

Settings > Providers > Custom provider:
- API Base: `http://<pi-ip>:8082/v1`
- API Key: your gateway key

### Kelivo / Other Apps

Use OpenAI-compatible provider with the gateway URL.

## Provider Format

Each provider has:
- **Name** — identifier (e.g. `github-models`)
- **API Base** — upstream URL (e.g. `https://models.github.ai/inference`)
- **Path** — endpoint path override (default: `/chat/completions`)
- **Model** — model ID sent to upstream
- **API Key** — upstream auth token

Clients request `<name>/<model-slug>` and the gateway routes to the right upstream.

## Wildcard TLS Certs

For `*.yourdomain.com`:
1. Go to TLS Certificates section
2. Enter `*.yourdomain.com`
3. Select your DNS provider (Cloudflare, Route53, Google, Porkbun)
4. Enter credentials file path
5. Click Issue

Credentials file format varies by provider. See certbot docs.

### Porkbun Example

```ini
# /etc/porkbun.ini
dns_porkbun_key = pk1_xxx
dns_porkbun_secret = sk1_xxx
```
```bash
chmod 600 /etc/porkbun.ini
```

## Architecture

```
Client (AIOPE/Kelivo/curl)
  |
  v
AIOPE Gateway (Jetty + OkHttp, port 8082)
  |-- /v1/chat/completions  -->  upstream provider
  |-- /v1/models            -->  list enabled providers
  |-- /portal/              -->  web admin UI
  |-- /api/wifi/*            -->  nmcli WiFi management
  |-- /api/network/*         -->  hostname, DNS config
  |-- /api/certs/*           -->  certbot management
  |-- /api/stats             -->  system metrics
  |-- /health                -->  status check
  v
ttyd (port 7681) --> web shell
```

## Hardware

Tested on:
- Raspberry Pi 3B+ (1GB RAM) — works fine
- Raspberry Pi 4 (2GB+) — recommended
- Any ARM64/x86_64 Linux with Docker

## Build From Source

```bash
./gradlew shadowJar
# Output: build/libs/gateway-server-all.jar

# Docker image:
docker build -t aiope-gateway .
```
