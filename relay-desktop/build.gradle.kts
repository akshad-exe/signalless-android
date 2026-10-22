import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version comes from the Kotlin plugin already on the build classpath.
    id("org.jetbrains.kotlin.jvm")
    // Compose compiler is versioned with Kotlin; the runtime ships with Compose Multiplatform.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The relay toolchain is independent of the Android Java 11 bytecode.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    applicationName = "relay-desktop"
    mainClass.set("com.signalless.relay.MainKt")
}

dependencies {
    implementation(project(":protocol-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)

    // Desktop-only Compose runtime. A plain JVM target is enough; no KMP plugin is used.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    testImplementation(libs.junit)
}
