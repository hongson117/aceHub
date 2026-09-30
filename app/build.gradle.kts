plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.lienson.acesport.g2probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.lienson.acesport.g2probe"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = "aceHub.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Network & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines & Lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
