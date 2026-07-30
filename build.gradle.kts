plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "io.schmikolai"
version = "0.1.0-SNAPSHOT"

// Spring Boot 3.3.4's BOM manages testcontainers.version at 1.19.8. That version's
// DockerClientProviderStrategy probes the daemon with a hardcoded Docker API version
// 1.32, which fails outright against a daemon enforcing a minimum API version of 1.40
// (rather than negotiating first and only falling back to 1.32 on failure, as later
// releases do). Override the BOM property so the whole testcontainers module family
// (not just the artifacts pinned below) resolves past that fixed release.
extra["testcontainers.version"] = "1.21.4"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web / core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Security / OAuth2 Authorization Server
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-authorization-server:1.3.2")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Persistence
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Redis / rate limiting / resilience
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // IMAP client
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")
    implementation("org.apache.commons:commons-pool2:2.12.0")

    // Crypto / KMS. Tink doesn't publish a BOM; tink-awskms:1.11.0 is the
    // latest available and pins its own tink core dependency to 1.15.0 (see
    // its POM), so tink is pinned to match rather than left to float higher.
    implementation("com.google.crypto.tink:tink:1.15.0")
    implementation("com.google.crypto.tink:tink-awskms:1.11.0")
    implementation("software.amazon.awssdk:kms:2.28.10")

    // HTML sanitization
    implementation("org.jsoup:jsoup:1.18.1")

    // Observability / logging
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Dev / annotation processing
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.2")
    testImplementation("org.testcontainers:postgresql:1.20.2")
    testImplementation("com.icegreen:greenmail-junit5:2.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("imap-mcp")
            // Tink's AesGcmJce and the AWS SDK's TLS calls to KMS both go
            // through JCE/JSSE providers resolved by name at runtime
            // (SunJCE, SunEC, ...) rather than direct class references, so
            // native-image needs to be told explicitly to keep them instead
            // of tree-shaking them as "unreachable".
            buildArgs.add("--enable-all-security-services")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
    metadataRepository {
        // Pulls curated reflect/resource/proxy config for common libraries
        // (AWS SDK v2, nimbus-jose-jwt, etc.) from the shared GraalVM
        // Reachability Metadata Repository instead of hand-writing it here.
        enabled = true
    }
}
