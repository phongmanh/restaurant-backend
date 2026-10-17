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

// test-plan §2, §9: tools run against staging (seed, history) and, from Phase 5 step 5, the system
// tests. They call a deployed service, so they never run in `./gradlew build`; `check` compiles them
// so they don't rot. Associated with `main`, so they reuse the service's own code.
val systemTest: SourceSet by sourceSets.creating
configurations[systemTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[systemTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
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

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.snakeyaml.engine)
    testImplementation(libs.json.schema.validator)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
    // IT-CORE-10 reads the contract from the repository root.
    systemProperty("contract.path", rootProject.file("docs/api/openapi.yaml").absolutePath)
    inputs.file("docs/api/openapi.yaml")
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
