plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vaidyamd.telecaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vaidyamd.telecaller"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH") ?: project.findProperty("ANDROID_KEYSTORE_PATH") as String?
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: project.findProperty("ANDROID_KEYSTORE_PASSWORD") as String?
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: project.findProperty("ANDROID_KEY_ALIAS") as String?
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: project.findProperty("ANDROID_KEY_PASSWORD") as String?
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "VaidyaMD Telecaller Dev")
            buildConfigField("String", "ENVIRONMENT", "\"dev\"")
            // Default dev backend: Google Cloud Run dev service (can be overridden via QR pairing or in-app settings)
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://hms-backend-1062509658389.asia-south1.run.app\"")
            buildConfigField("String", "GCS_BUCKET_URL", "\"https://storage.googleapis.com/vaidya-hms-dev-uploads/apps/vaidyamd-telecaller-dev.apk\"")
        }
        create("prod") {
            dimension = "environment"
            resValue("string", "app_name", "VaidyaMD Telecaller")
            buildConfigField("String", "ENVIRONMENT", "\"prod\"")
            // Default prod backend: Production API domain
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://api.vaidyamd.com\"")
            buildConfigField("String", "GCS_BUCKET_URL", "\"https://storage.googleapis.com/vaidya-hms-prod-uploads/apps/vaidyamd-telecaller.apk\"")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            } else {
                // Fallback to debug keystore signing so APK can be installed without "package appears to be invalid" error
                signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
