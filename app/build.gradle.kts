import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- Permanent CI debug signing -------------------------------------------
// GitHub-hosted runners are ephemeral: without this, every build would get a
// fresh debug key, so new APKs could not install over old ones (and the app's
// data would be wiped by the forced uninstall each time).
// The repo carries our own debug keystore as base64 (debug-keystore.b64 at
// the project root — the standard public debug credentials, not a secret).
// Decode it once per build and sign the debug variant with it, so the
// signature is stable forever:
// SHA-1: 82:7E:10:86:72:E0:E0:7A:5B:80:12:49:D1:46:11:0B:BE:32:EA:3B
val ciDebugKeystore: File? = run {
    val b64 = rootProject.file("debug-keystore.b64")
    if (!b64.exists()) return@run null
    val out = layout.buildDirectory.file("ci-debug.keystore").get().asFile
    if (!out.exists()) {
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(b64.readText()))
    }
    out
}

android {
    namespace = "app.ypn.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ypn.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (ciDebugKeystore != null) {
            create("ciDebug") {
                storeFile = ciDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (ciDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Phase 2: embedded Open SSTP Client core (kittoku.osc) —
    // PreferenceManager, DocumentFile (log export), coroutines engine.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
