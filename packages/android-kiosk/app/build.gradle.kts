plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.swonal.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swonal.kiosk"
        minSdk = 23
        targetSdk = 34
        val propVersionCode = (project.findProperty("swonalVersionCode") as String?)?.toIntOrNull()
        val propVersionName = (project.findProperty("swonalVersionName") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        versionCode = propVersionCode ?: 10101
        versionName = propVersionName ?: "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        val ksPath = System.getenv("SWONAL_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SWONAL_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SWONAL_KEY_ALIAS") ?: "swonal"
                keyPassword = System.getenv("SWONAL_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Embedded modern WebView engine (Mozilla GeckoView 140 ESR for arm64-v8a, minSdk 21+)
    // Replaces Android 6.0's obsolete System WebView v44 so /history, /settings, /admin render 100% in-app.
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:140.0.20250707120347")

    // Networking & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media & Images (API 23 compatible)
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Native QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Bundled Modern SQLite (SQLite 3.46+ for API 23 compatibility & UPSERT/WAL/VACUUM INTO)
    implementation("androidx.sqlite:sqlite:2.5.1")
    implementation("androidx.sqlite:sqlite-bundled:2.5.1")

    // Embedded lightweight HTTP & WebSocket server for serverless LAN Companion & local web views
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Unit Tests (JUnit 5 + MockWebServer + Host JVM Bundled SQLite)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.5.1")
}

configurations.matching { it.name.contains("UnitTest") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
