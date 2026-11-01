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

    // The relay window ships only for Windows. Pin the Windows runtime instead
    // of `currentOs` so Linux CI resolves the same locked artifacts as the
    // Windows package job instead of silently switching to the Linux native.
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)

    testImplementation(libs.junit)
}
