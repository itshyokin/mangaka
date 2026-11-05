plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mangaka.draw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangaka.draw"
        minSdk = 29
        targetSdk = 35
        // Each cloud build gets a higher version code, so it installs as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.0"
    }

    // The release key comes from the SIGNING_KEYSTORE repository secret (see the workflow).
    // Without it, release builds fall back to the debug key.
    val releaseKeystore = System.getenv("MANGAKA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("MANGAKA_KEYSTORE_PASSWORD") ?: "mangaka-release"
                keyAlias = "mangaka"
                keyPassword = System.getenv("MANGAKA_KEYSTORE_PASSWORD") ?: "mangaka-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // Milestone 1 uses only the Android platform and the Kotlin standard library.
}
