plugins {
    id("com.android.application")
}

android {
    namespace = "com.primemakers.primemusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.primemakers.primemusic"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-local"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}