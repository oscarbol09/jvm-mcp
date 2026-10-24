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

# Custom JRE via jlink: the stock JRE image lacks jdk.attach (Attach API)
# Modules from .github/workflows/release.yml, plus jdk.crypto.ec for EC/X25519 TLS (HTTPS Actuator)
# and jdk.jfr, without which the DiagnosticCommand MBean has no usable operations (self-inspection histogram)
FROM eclipse-temurin:21-jdk-alpine AS jre-builder

RUN jlink --add-modules java.base,java.management,java.net.http,java.sql,java.naming,jdk.attach,jdk.management,jdk.management.agent,java.instrument,jdk.internal.jvmstat,jdk.crypto.ec,jdk.jfr \
          --output /javaruntime \
          --strip-debug \
          --no-man-pages \
          --no-header-files

# Runtime Stage: Minimal hardened JRE
FROM alpine:3.24

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-builder /javaruntime $JAVA_HOME

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
