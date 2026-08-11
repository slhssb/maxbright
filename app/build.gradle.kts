plugins {
    id("com.android.application")
}

android {
    namespace = "com.operit.maxbright"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("maxbright.keystore")
            storePassword = "maxbright123"
            keyAlias = "maxbright"
            keyPassword = "maxbright123"
        }
    }

    defaultConfig {
        applicationId = "com.operit.maxbright"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Shizuku API（可选提权后端）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}