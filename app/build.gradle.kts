plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kero.remoteagent"
    compileSdk = 35

    flavorDimensions += "role"

    productFlavors {
        create("agent") {
            dimension = "role"
            applicationIdSuffix = ".agent"
            versionNameSuffix = "-agent"
        }
        create("controller") {
            dimension = "role"
            applicationIdSuffix = ".controller"
            versionNameSuffix = "-controller"
        }
    }

    defaultConfig {
        applicationId = "com.kero.remoteagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ✅ هذا السطر ضروري جداً لحل خطأ BuildConfig.FLAVOR
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // ✅ إضافة مكتبة الموقع لحل أخطاء gms
    implementation("com.google.android.gms:play-services-location:21.3.0")
}