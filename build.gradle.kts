plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
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

    // Crypto — Tink's local AEAD primitive wraps/unwraps secrets; the DEK
    // itself is generated/wrapped by OpenBao's Transit engine over plain
    // HTTP (RestClient, from spring-boot-starter-web — no dedicated client
    // dependency needed).
    implementation("com.google.crypto.tink:tink:1.15.0")

    // HTML sanitization
    implementation("org.jsoup:jsoup:1.18.1")

    // Observability / logging
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
    // One JVM per test class: ImapMailServiceIntegrationTest relies on JVM-wide
    // javax.net.ssl.trustStore system properties for its throwaway GreenMail
    // cert (see its Javadoc), which only works if nothing else in the same JVM
    // has already triggered the JDK's default SSLContext to cache the stock
    // cacerts first — e.g. building a java.net.http.HttpClient-backed RestClient
    // (crypto/OpenBaoEnvelopeEncryptionService and its tests) elsewhere in the
    // suite. forkEvery isolates each class's JVM so this ordering accident
    // can't happen, rather than depending on which class Gradle happens to run
    // first.
    forkEvery = 1
}
