# syntax=docker/dockerfile:1

# --- Build stage: package as a runnable jar ---
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Layer dependency resolution separately from source so an edit to src/
# doesn't bust the dependency-download cache.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon help >/dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# --- Runtime stage: just the JRE + jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --shell /usr/sbin/nologin appuser
COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
