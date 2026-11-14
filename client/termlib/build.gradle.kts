import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
}

// The JNI libraries come unchanged from the published AAR of the same version.
val termlibVersion = "0.3.14"
val nativeAar: Configuration by configurations.creating { isTransitive = false }
dependencies { nativeAar("org.connectbot:termlib:$termlibVersion@aar") }

val jniOut = layout.buildDirectory.dir("aar-jni")
val extractJni by tasks.registering(Sync::class) {
    from({ nativeAar.map { zipTree(it) } }) {
        include("jni/**")
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(jniOut)
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].jniLibs.srcDir(jniOut)
    lint { abortOnError = false }
}

tasks.named("preBuild") { dependsOn(extractJni) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(extractJni) }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
}
