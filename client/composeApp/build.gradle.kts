import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

val appVersion = "1.5.0"
val ktor = "3.1.3"

// APP_VERSION constant for the in-app updater
val versionDir = layout.buildDirectory.dir("generated/version/kotlin").get().asFile
File(versionDir, "app/servercontrol/AppVersion.kt").apply {
    parentFile.mkdirs()
    val text = "package app.servercontrol\n\n/** generated from build.gradle.kts */\nconst val APP_VERSION = \"$appVersion\"\n"
    if (!exists() || readText() != text) writeText(text)
}

kotlin {
    androidTarget()
    jvm("desktop")
    jvmToolchain(17)

    sourceSets {
        commonMain {
            kotlin.srcDir(versionDir)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(compose.ui)
            implementation("io.ktor:ktor-client-core:$ktor")
            implementation("io.ktor:ktor-client-okhttp:$ktor")
            implementation("io.ktor:ktor-client-content-negotiation:$ktor")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        // SSH (pure Java) – used by the actual implementations in androidMain and desktopMain
        val jsch = "com.github.mwiede:jsch:0.2.26"
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            implementation(jsch)
            // ConnectBot's terminal (libvterm, selection with magnifier, IME handling)
            implementation("org.connectbot:termlib:0.3.14")
            // ed25519 / curve25519 for SSH on older Android versions
            implementation("org.bouncycastle:bcprov-jdk18on:1.80")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation(jsch)
            }
        }
    }
}

android {
    namespace = "app.servercontrol"
    compileSdk = 37
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
            fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
            storeFile = env("SIGNING_KEYSTORE_FILE")?.let { file(it) } ?: file("signing/servercontrol.keystore")
            storePassword = env("SIGNING_STORE_PASSWORD") ?: "servercontrol"
            keyAlias = env("SIGNING_KEY_ALIAS") ?: "servercontrol"
            keyPassword = env("SIGNING_KEY_PASSWORD") ?: "servercontrol"
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin", "META-INF/versions/*/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

compose.desktop {
    application {
        mainClass = "app.servercontrol.MainKt"
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "ServerControl"
            packageVersion = appVersion
            description = "ServerControl – Debian-Server-Verwaltung"
            vendor = "apk-creator"
            modules("java.naming", "jdk.crypto.ec", "java.logging", "java.security.jgss")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true
                shortcut = true
                upgradeUuid = "5f0b7c1e-6a43-4c6a-9b3e-2d8e7b1a9c40"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                shortcut = true
            }
        }
    }
}
