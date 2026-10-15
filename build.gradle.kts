plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = "io.github.phongmanh.restaurant"
version = "0.1.0"

application {
    mainClass = "io.github.phongmanh.restaurant.ApplicationKt"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.uuid.generator)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.snakeyaml.engine)
}

tasks.test {
    useJUnitPlatform()
    // IT-CORE-10 reads the contract from the repository root.
    systemProperty("contract.path", rootProject.file("docs/api/openapi.yaml").absolutePath)
    inputs.file("docs/api/openapi.yaml")
}

ktlint {
    version = libs.versions.ktlint.get()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

// detekt 1.23.8, the latest stable, accepts JVM targets up to 22. Its plain task only parses the
// source, so the target doesn't change its findings. Remove this when detekt 2 is stable.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "22"
}

kover {
    reports {
        // test-plan §7: coverage ≥ 80% in services and domain.
        filters {
            includes {
                classes("*.domain.*", "*.application.*")
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.named("koverVerify"))
}
