plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pranav.atminigame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pranav.atminigame"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    aaptOptions {
        noCompress("task")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
