FROM alpine:3.21

RUN apk add --no-cache \
    openjdk21-jre-headless \
    certbot \
    py3-pip \
    openssl \
    curl \
    bash \
    sudo \
    ttyd

RUN pip3 install --break-system-packages \
    certbot-dns-cloudflare \
    certbot-dns-route53 \
    certbot-dns-google \
    certbot-dns-porkbun \
    2>/dev/null; true

# Gateway
COPY build/libs/gateway-server-all.jar /opt/gateway/gateway.jar
COPY deploy/entrypoint.sh /opt/gateway/entrypoint.sh
RUN chmod +x /opt/gateway/entrypoint.sh

# Default config directory
RUN mkdir -p /opt/gateway/data

EXPOSE 8082 7681

WORKDIR /opt/gateway
ENTRYPOINT ["/opt/gateway/entrypoint.sh"]
