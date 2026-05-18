FROM eclipse-temurin:17-jre-alpine
WORKDIR /opt/gateway
COPY build/libs/gateway-server-all.jar gateway.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Xmx256m", "-jar", "gateway.jar"]
CMD ["8082", "/opt/gateway/data"]
