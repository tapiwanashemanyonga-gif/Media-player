plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = System.getenv("A1_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("A1_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("A1_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("A1_RELEASE_KEY_PASSWORD")

android {
    namespace = "com.kivora.music"
    compileSdk = 36

    defaultConfig {
        // Production package ID used by the published A1 Family Media app.
        applicationId = "com.a1family.media"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.5"
    }

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
}
