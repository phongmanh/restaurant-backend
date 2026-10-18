plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.cyclonedx)
}

group = "io.github.phongmanh.restaurant"
version = "1.0.1"

application {
    mainClass = "io.github.phongmanh.restaurant.ApplicationKt"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

// test-plan §2, §9: tools run against staging (seed, history) and, from Phase 5 step 5, the system
// tests. They call a deployed service, so they never run in `./gradlew build`; `check` compiles them
// so they don't rot. Associated with `main`, so they reuse the service's own code.
val systemTest: SourceSet by sourceSets.creating
configurations[systemTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[systemTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
// The system tests check every staging response against the contract with the tests' own
// ContractCheck, so they see the test classes and their libraries (JUnit, the schema validator).
configurations[systemTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
dependencies { systemTest.implementationConfigurationName(sourceSets.test.get().output) }
kotlin.target.compilations
    .getByName("systemTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

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
    // Jackson comes only through libraries (java-jwt, Flyway, the logstash encoder); the BOMs lift
    // it to fixed versions (gradle/libs.versions.toml).
    implementation(platform(libs.jackson2.bom))
    implementation(platform(libs.jackson3.bom))
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.uuid.generator)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.ktor)
    implementation(libs.bouncycastle)
    implementation(libs.java.jwt)
    implementation(libs.ably.java)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.otlp)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.snakeyaml.engine)
    testImplementation(libs.json.schema.validator)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.opentelemetry.proto)
}

tasks.test {
    useJUnitPlatform()
    // IT-CORE-10 reads the contract from the repository root.
    systemProperty("contract.path", rootProject.file("docs/api/openapi.yaml").absolutePath)
    inputs.file("docs/api/openapi.yaml")
    // AlertRulesTest and MetricNamesTest read the alert rules and dashboards (Phase 5 step 4c).
    systemProperty("observability.path", rootProject.file("deploy/observability").absolutePath)
    inputs.dir("deploy/observability")
    // RouteLog writes one file per test JVM; a run starts empty so the matrix sees only this run,
    // and a cached or up-to-date run keeps the files of the run it stands for.
    val routeLog = layout.buildDirectory.dir("route-log")
    outputs.dir(routeLog)
    doFirst { routeLog.get().asFile.deleteRecursively() }
}

// G4 hardening H4: the route-test matrix is a rule. It fails on a 401, 400, 404 or success the
// contract documents and no test produces, unless scripts/route-matrix-allowed.txt lists it.
val routeMatrix by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails on a route-test matrix gap not in scripts/route-matrix-allowed.txt."
    dependsOn(tasks.test)
    inputs.files("scripts/route-matrix.py", "scripts/route-matrix-allowed.txt", "docs/api/openapi.yaml")
    inputs.dir(layout.buildDirectory.dir("route-log"))
    commandLine("python3", "scripts/route-matrix.py", "--check")
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
    description = "Runs detekt on the main, test and systemTest sources."
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    classpath = detektCli
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    val sources = files("src/main/kotlin", "src/test/kotlin", "src/systemTest/kotlin")
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
    currentProject {
        instrumentation {
            // Coverage is the CI tests' (test-plan §7); the system tests call a remote service.
            disabledForTestTasks.add("systemTest")
        }
    }
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
    dependsOn(detekt, tasks.named("koverVerify"), routeMatrix, tasks.named("compileSystemTestKotlin"))
}

// deploy/README.md, test-plan §6: staging's data. Both read the service's environment (exported from
// deploy/staging/*.env, as for `migrate`) and take the deployment's URL.
// The libraries that ship, as a CycloneDX SBOM (ASVS V15.1.2, F-3, step 6a): the runtime classpath
// only. CI's `build` job writes it and Grype checks it; the image job covers the JRE and the OS
// packages, which Gradle doesn't know (deploy/README.md "Vulnerable dependencies"). A fat jar keeps
// no `pom.properties` for Gradle-published libraries (Ktor, Exposed, Kotlin), so an image scan alone
// misses them.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    xmlOutput.convention(null as RegularFile?)
}
tasks.cyclonedxBom {
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    xmlOutput.convention(null as RegularFile?)
}

tasks.register<JavaExec>("seedStaging") {
    group = "staging"
    description = "<deployment URL>: restaurants A and B, their staff accounts, menus and tables"
    classpath = systemTest.runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.staging.SeedStagingKt"
    workingDir = rootDir
}

tasks.register<JavaExec>("generateHistory") {
    group = "staging"
    description = "<deployment URL>: restaurant L and 49 smaller ones, with a scaled year of sales"
    classpath = systemTest.runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.staging.GenerateHistoryKt"
    workingDir = rootDir
}

// test-plan §5, §9 step 5: the acceptance scenarios against a staging deployment, through the API.
// Never part of `./gradlew build`: it spends the free plans' allowances (test-plan §9's budget guard).
tasks.register<Test>("systemTest") {
    group = "staging"
    description = "-PstagingUrl=<deployment URL>: the AT scenarios on staging (test-plan §5)"
    testClassesDirs = systemTest.output.classesDirs
    classpath = systemTest.runtimeClasspath
    useJUnitPlatform()
    workingDir = rootDir
    systemProperty("staging.url", providers.gradleProperty("stagingUrl").getOrElse(""))
    systemProperty("contract.path", rootProject.file("docs/api/openapi.yaml").absolutePath)
    // ST-08's restore drill (deploy/README.md): -PrestoreDrill=mark|check|clean, -PrestorePoint=<RFC 3339>.
    systemProperty("restore.drill", providers.gradleProperty("restoreDrill").getOrElse(""))
    systemProperty("restore.point", providers.gradleProperty("restorePoint").getOrElse(""))
    // A run is against a live deployment whose state the inputs don't describe: never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// test-plan §4 ST-11, deploy/README.md: ZAP's API scan of a staging deployment, in Docker, driven by
// the contract. Never part of `./gradlew build`: it spends the free plans' allowances (test-plan §9).
tasks.register<JavaExec>("zapScan") {
    group = "staging"
    description = "<deployment URL> [--profiles guest,staff,admin,anonymous] [--plan-only]: ST-11's ZAP scan"
    classpath = systemTest.runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.staging.ZapScanKt"
    workingDir = rootDir
}

// deploy/README.md, ST-07: pushes request counts with an injected 5xx share to Grafana Cloud, so
// staging's burn-rate alerts can be seen to fire. Reads OTEL_EXPORTER_OTLP_* from the environment.
tasks.register<JavaExec>("injectErrors") {
    group = "staging"
    description = "[--minutes N] [--error-ratio X] [--rps N]: synthetic 5xx for ST-07's alerts"
    classpath = systemTest.runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.staging.InjectErrorsKt"
    workingDir = rootDir
}

// test-plan §9 step 6b, deploy/README.md: the scaled load runs (ST-01, ST-02, ST-04, ST-06, ST-09,
// ST-19), k6 in Docker for the mix. Never part of `./gradlew build`: it spends the free plans' allowances.
tasks.register<JavaExec>("loadTest") {
    group = "staging"
    description = "<URL> --profile smoke|cold|deploy|report|noisy [--minutes N] [--rate N] [--force] [--plan-only]: step 6b's load runs"
    classpath = systemTest.runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.staging.LoadRunKt"
    workingDir = rootDir
}

// ADR-0004: migrations run from CI (or locally) over a direct connection as the migrator, never at
// service startup. Reads DATABASE_URL_MIGRATOR_DIRECT, DATABASE_URL and DATABASE_URL_PLATFORM.
tasks.register<JavaExec>("migrate") {
    group = "database"
    description = "Creates the database roles and applies the Flyway migrations."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.core.persistence.MigrationsKt"
}

// deploy/README.md: a new environment's secrets, written to a git-ignored env file; prints names only.
tasks.register<JavaExec>("generateSecrets") {
    group = "operations"
    description = "<file> --db-host <pooled database host> [--db-name <database>]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.core.ops.GenerateSecretsKt"
    workingDir = rootDir
}

// restaurants §5, §8: the operator's commands for the first platform admin and the last admin's lost
// authenticator. Reads the service's environment variables; prints the link once and nowhere else.
tasks.register<JavaExec>("operator") {
    group = "operations"
    description = "invite-admin <email> <display name> | reset-admin-totp <email>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.phongmanh.restaurant.restaurants.ops.OperatorKt"
}
