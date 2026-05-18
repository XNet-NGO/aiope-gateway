FROM amazoncorretto:17-alpine
WORKDIR /opt/gateway
COPY build/libs/gateway-server-all.jar gateway.jar
ENTRYPOINT ["java", "-Xmx256m", "-jar", "gateway.jar"]
CMD ["8082", "/opt/gateway/data"]
