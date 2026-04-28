import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    java
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.wallet"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.jar { enabled = false }

repositories {
    mavenCentral()
}

/** Dedicated classpath for Mockito as a JVM agent (inline mock maker); avoids self-attach warnings on Java 21+. */
val mockitoAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.4.2")

    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
    useJUnitPlatform()
    environment("DOCKER_API_VERSION", "1.44")
    systemProperty("logging.level.root", "INFO")
    systemProperty("logging.level.com.wallet", "DEBUG")
    jvmArgs(
        "-Ddocker.api.version=1.44",
        // https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#0.3
        "-javaagent:${mockitoAgent.asPath}"
    )
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }

    /** Total + failed-test list at end of `:test` (easy to spot in Docker logs). */
    addTestListener(
        object : TestListener {
            val failures = mutableListOf<String>()

            override fun beforeSuite(descriptor: TestDescriptor) {}

            override fun afterSuite(descriptor: TestDescriptor, result: TestResult) {
                if (descriptor.parent != null) return
                logger.lifecycle(
                    "\n=== Test summary: {} tests — {} passed, {} failed, {} skipped ===",
                    result.testCount,
                    result.successfulTestCount,
                    result.failedTestCount,
                    result.skippedTestCount,
                )
                if (failures.isNotEmpty()) {
                    logger.lifecycle("\nFailed tests:")
                    failures.forEach { logger.lifecycle("  FAILED  {}", it) }
                }
            }

            override fun beforeTest(descriptor: TestDescriptor) {}

            override fun afterTest(descriptor: TestDescriptor, result: TestResult) {
                if (result.resultType == TestResult.ResultType.FAILURE) {
                    failures += "${descriptor.className} > ${descriptor.displayName}"
                }
            }
        },
    )
}
