# syntax=docker/dockerfile:1

# --- Build stage: Spring AOT processing + GraalVM native-image compile ---
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /workspace

# The base Oracle Linux 9 image is minimal enough that even findutils (xargs)
# is missing, and the Gradle wrapper script shells out to it.
RUN microdnf install -y findutils && microdnf clean all

# Layer dependency resolution separately from source so an edit to src/
# doesn't bust the dependency-download cache.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon help >/dev/null

COPY src src
RUN ./gradlew --no-daemon nativeCompile -x test

# --- Runtime stage: just the native binary, no JVM, no shell ---
# cc-debian12 (not the plain "base" variant) is the image GraalVM's own docs
# call out for native-image binaries specifically: it carries libgcc/libstdc++
# for the GC's unwind tables, on top of base's glibc + ca-certificates (needed
# here for outbound TLS to arbitrary IMAP servers, Postgres, Redis and KMS).
FROM gcr.io/distroless/cc-debian12:nonroot
WORKDIR /app

# The native binary dynamically links libz (used by java.util.zip at
# runtime); cc-debian12 carries libgcc/libstdc++ but not zlib, and
# distroless has no package manager to install it with.
COPY --from=builder /usr/lib64/libz.so.1.2.11 /lib/x86_64-linux-gnu/libz.so.1

COPY --from=builder /workspace/build/native/nativeCompile/imap-mcp /app/imap-mcp

USER nonroot
EXPOSE 8080
ENTRYPOINT ["/app/imap-mcp"]
