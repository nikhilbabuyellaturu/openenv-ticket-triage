# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Cache dependencies layer — copy pom first
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="OpenEnv: IT Support Ticket Triage" \
      org.opencontainers.image.description="Real-world RL environment for IT support ticket triage" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

# Non-root user for security
RUN addgroup -S openenv && adduser -S openenv -G openenv
USER openenv

WORKDIR /app

# Copy the fat JAR from build stage
COPY --from=builder /build/target/ticket-triage-env-1.0.0.jar app.jar

# Hugging Face Spaces requires port 7860
EXPOSE 7860

# JVM tuning for container environment
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# OPENAI_API_KEY is read from environment at runtime
# Set via HF Space secrets or docker run -e OPENAI_API_KEY=...
ENV OPENAI_API_KEY=""
ENV OPENAI_MODEL="gpt-4o-mini"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
