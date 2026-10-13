import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "spike"
version = "0.1.0"

application {
    mainClass = "spike.ApplicationKt"
}

// No toolchain on purpose: the build uses the JDK that runs Gradle (21 locally, 25 in the image),
// and the bytecode targets 21 so the same jar runs on both.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.test {
    useJUnitPlatform()
}

// Q6: Flyway as `migrator` over the direct connection, then checks that `app` can't run DDL.
// CI runs it; locally, load .env.spike with Node (its URLs contain &): see the README, How to run.
tasks.register<JavaExec>("migrate") {
    group = "spike"
    description = "Creates the app role, runs Flyway as migrator and checks that app is denied DDL."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "spike.MigrateKt"
}

// Dry run of scripts/measure.mjs on localhost, against the tests' in-process PostgreSQL.
tasks.register<JavaExec>("runLocal") {
    group = "spike"
    description = "Serves the spike on localhost:8080 against an in-process PostgreSQL."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "spike.LocalServerKt"
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
}
