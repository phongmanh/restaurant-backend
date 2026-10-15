plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
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

// detekt 1.23.8 (the latest stable; 2.0 is alpha) crashes when it runs on JDK 25: its embedded
// Kotlin compiler can't parse the version string. So it runs as its own process on a JDK 21
// toolchain, whatever JDK runs Gradle. It only parses the source, so the JDK doesn't change its
// findings. Back to the Gradle plugin when detekt 2 is stable.
val detektCli by configurations.creating

dependencies {
    detektCli(libs.detekt.cli)
}

val detekt by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs detekt on the main and test sources."
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    classpath = detektCli
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    val sources = files("src/main/kotlin", "src/test/kotlin")
    val config = file("config/detekt/detekt.yml")
    val report = layout.buildDirectory.file("reports/detekt/detekt.html")
    inputs.files(sources, config)
    outputs.file(report)
    args(
        "--input",
        sources.joinToString(",") { it.path },
        "--config",
        config.path,
        "--build-upon-default-config",
        "--report",
        "html:${report.get().asFile.path}",
    )
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
    dependsOn(detekt, tasks.named("koverVerify"))
}
