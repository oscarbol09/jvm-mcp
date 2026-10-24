# Multi-stage Dockerfile for JVM-MCP Server
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache dependency layer
COPY pom.xml .
COPY core/pom.xml core/
COPY mcp-server/pom.xml mcp-server/
RUN mvn dependency:go-offline -B || true

# Compile and package shaded application JAR
COPY core core
COPY mcp-server mcp-server
RUN mvn clean package -B -DskipTests

# Runtime Stage: Minimal hardened JRE
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="JVM-MCP Server" \
      org.opencontainers.image.description="Model Context Protocol (MCP) server for live Spring Boot and JVM inspection" \
      org.opencontainers.image.url="https://github.com/oscarbol09/jvm-mcp" \
      org.opencontainers.image.source="https://github.com/oscarbol09/jvm-mcp" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Least-privilege non-root execution
RUN addgroup -g 10001 -S jvmgroup && \
    adduser -u 10001 -S jvmuser -G jvmgroup

COPY --from=builder --chown=jvmuser:jvmgroup /build/mcp-server/target/jvm-mcp-server-*.jar /app/jvm-mcp-server.jar

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/jvm-mcp-server.jar"]
CMD ["serve"]
