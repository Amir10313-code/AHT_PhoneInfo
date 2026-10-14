plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: when the CI provides a keystore (GitHub Secrets) the APK is signed with
// the owner's own stable key; otherwise it falls back to the debug key so builds never break.
val ksPath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore: Boolean = ksPath != null && file(ksPath).exists()

if (!hasKeystore) {
    logger.warn(
        "WARNING: no release keystore configured - the release APK is signed with the DEBUG key. " +
            "Such APKs change signature between build machines and look unknown to Play Protect. " +
            "Set the KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD secrets (see SECURITY_AUDIT_FA.md)."
    )
}

android {
    namespace = "com.mahirex.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mahirex.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "2.1"
        setProperty("archivesBaseName", "MAHIREX Pulse")
    }

    signingConfigs {
        if (hasKeystore) {
            create("mahirexRelease") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8: standard shrinking/optimisation (smaller APK, unused code removed). Not used to hide behaviour.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKeystore) signingConfigs.getByName("mahirexRelease")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
