import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version comes from the Kotlin plugin already on the build classpath.
    id("org.jetbrains.kotlin.jvm")
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

    testImplementation(libs.junit)
}
