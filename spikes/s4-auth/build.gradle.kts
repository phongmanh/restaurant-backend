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

// Dry run of scripts/measure.mjs on localhost, with keys generated in memory if SPIKE_KEYS is unset.
tasks.register<JavaExec>("runLocal") {
    group = "spike"
    description = "Serves the spike on localhost:8080."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "spike.ApplicationKt"
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.bouncycastle)
    implementation(libs.password4j)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
}

tasks.register<JavaExec>("generateSeeds") {
    group = "spike"
    description = "Prints the PHC strings for Seeds.kt."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "spike.GenerateSeedsKt"
}
