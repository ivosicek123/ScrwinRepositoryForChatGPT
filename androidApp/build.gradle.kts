plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

val rustCore = (findProperty("elfall.rustCore") as String?)?.toBoolean() ?: false
val coreDir = rootProject.layout.projectDirectory.dir("core")

// cargo-ndk: libelfall_core.so pro arm64-v8a, armeabi-v7a, x86_64 → src/main/jniLibs (jen s příznakem)
val cargoNdk by tasks.registering(Exec::class) {
    onlyIf { rustCore }
    workingDir = coreDir.asFile
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "--platform", "26",
        "-o", layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath, "build", "--release", "--locked", "--features", "ffi")
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(cargoNdk) }

android {
    namespace = "cz.faltyn.elfall.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.faltyn.elfall"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.5.0"
    }

    buildFeatures { compose = true; buildConfig = true }
    // Rust jádro (core/) – výchozí VYPNUTO. Zapnout: ./gradlew -Pelfall.rustCore=true … (vyžaduje NDK + cargo-ndk)
    defaultConfig { buildConfigField("boolean", "RUST_CORE", rustCore.toString()) }

    flavorDimensions += listOf("app", "network")
    productFlavors {
        // Eleanor = standalone wallet: direct public testnet APIs, no RunPod / ELFALL network, no FLC / cash-out / swap.
        create("eleanor") {
            dimension = "app"
            isDefault = true
            applicationId = "cz.faltyn.eleanor"
            resValue("string", "app_name", "Eleanor")
            buildConfigField("boolean", "STANDALONE", "true")
        }
        create("elfall") {
            dimension = "app"
            resValue("string", "app_name", "ELFALL")
            buildConfigField("boolean", "STANDALONE", "false")
        }
        create("bitcoinTestnet") {
            dimension = "network"
            isDefault = true
            applicationIdSuffix = ".testnet"
            buildConfigField("String", "BTC_NETWORK", "\"TESTNET\"")
        }
        create("bitcoinMainnet") {
            dimension = "network"
            buildConfigField("String", "BTC_NETWORK", "\"MAINNET\"")
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate release builds (debug/CI builds unaffected)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("fr.acinq.bitcoin:bitcoin-kmp:0.32.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // QR scan (camera intent, handles CameraX/permissions internally) + QR render
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // libsodium (XChaCha20-Poly1305, Argon2id) přes ověřené bindingy lazysodium
    implementation("com.goterl:lazysodium-android:5.1.0@aar") { isTransitive = false }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.13.0") // UniFFI testy proti hostitelské libelfall_core
    testImplementation(kotlin("test-junit"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// JVM testy Rust jádra: hostitelská knihovna z core/target/release (cargo build --release --features ffi)
tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", coreDir.dir("target/release").asFile.absolutePath)
}
