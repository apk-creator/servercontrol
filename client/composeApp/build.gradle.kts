import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

val appVersion = "0.1.0"
val ktor = "3.1.3"

kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("io.ktor:ktor-client-core:$ktor")
            implementation("io.ktor:ktor-client-okhttp:$ktor")
            implementation("io.ktor:ktor-client-content-negotiation:$ktor")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.lifecycle:lifecycle-process:2.8.7")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

android {
    namespace = "app.servercontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.servercontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = appVersion
    }
    signingConfigs {
        create("ci") {
            // Stable signing key so updates install over the previous version.
            // Prefer GitHub secrets; fall back to the keystore in the (private) repo.
            val ks = System.getenv("SIGNING_KEYSTORE_FILE")
            storeFile = if (ks != null) file(ks) else file("signing/servercontrol.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "servercontrol"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "servercontrol"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "servercontrol"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ci")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

compose.desktop {
    application {
        mainClass = "app.servercontrol.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "ServerControl"
            packageVersion = appVersion
            description = "ServerControl – Debian-Server-Verwaltung"
            vendor = "apk-creator"
            modules("java.naming", "jdk.crypto.ec")
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "5f0b7c1e-6a43-4c6a-9b3e-2d8e7b1a9c40"
            }
            linux { shortcut = true }
        }
    }
}
