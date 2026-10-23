plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val a1KeystoreFile = System.getenv("A1_KEYSTORE_FILE")
val a1KeystorePassword = System.getenv("A1_KEYSTORE_PASSWORD")
val a1KeyPassword = System.getenv("A1_KEY_PASSWORD")
val a1KeyAlias = System.getenv("A1_KEY_ALIAS") ?: "a1family"
val hasA1SigningSecrets =
    !a1KeystoreFile.isNullOrBlank() &&
    !a1KeystorePassword.isNullOrBlank() &&
    !a1KeyPassword.isNullOrBlank()

android {
    namespace = "com.kivora.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.a1family.media"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (hasA1SigningSecrets) {
            create("release") {
                storeFile = file(requireNotNull(a1KeystoreFile))
                storePassword = requireNotNull(a1KeystorePassword)
                keyAlias = a1KeyAlias
                keyPassword = requireNotNull(a1KeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasA1SigningSecrets) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    // Pin the verified Start.io release; never resolve a changing 5.+ version.
    implementation("com.startapp:inapp-sdk:5.4.0")
}
