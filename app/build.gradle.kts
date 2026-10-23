plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase (Google sign-in, cloud save, multiplayer) is enabled only when
// app/google-services.json is present. Without it the game builds and runs fully offline.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    // bundled race music is streamed straight from the APK (MediaPlayer needs it uncompressed)
    androidResources {
        noCompress += listOf("mp3", "ogg", "m4a", "aac", "wav", "flac")
    }

    namespace = "com.denoya.xenorush"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.denoya.xenorush"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        // Shared debug key so the SHA-1 registered in Firebase is the same on every machine.
        // For a public release, create a private release key and never commit it.
        getByName("debug") {
            storeFile = rootProject.file("keystore/xenorush-debug.jks")
            storePassword = "xenorush"
            keyAlias = "xenorushdebug"
            keyPassword = "xenorush"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.zxing:core:3.5.2")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
