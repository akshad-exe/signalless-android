import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version comes from the Kotlin plugin already on the build classpath.
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Kable's desktop runtime requires Java 17; protocol-core only needs 11.
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
    implementation(libs.kable.core)
    implementation(libs.kotlinx.coroutines.core)
}
