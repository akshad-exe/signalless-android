import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version comes from the Kotlin plugin already on the build classpath via
    // the Android plugin; adding a marker POM here would also require a new
    // dependency-verification entry.
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Android's D8 supports Java 11 bytecode; keep Java and Kotlin aligned so the
// app can consume this module.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Exposed by the public API (StateFlow in DebugSettingsManager consumers,
    // suspend functions in the mesh core), so consumers must see it.
    api(libs.kotlinx.coroutines.core)

    // Used only by SignalLessMessage's JSON helpers.
    implementation(libs.gson)
}
