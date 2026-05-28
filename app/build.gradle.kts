plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}


android {

    namespace = "ru.acestar.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.acestar.vpn"
        minSdk = 24
        // OpenVPN dependency crashes on Android 12+ when targetSdk >= 31 because
        // of missing PendingIntent mutability flags in library internals.
        // Keep targetSdk 30 until library dependency is updated/fixed.
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true

    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation("androidx.cardview:cardview:1.0.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Для логирования запросов (опционально)
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // Kotlin Coroutines (если еще не добавлены)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Kotlin Serialization (для JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("com.macasaet.fernet:fernet-java8:1.5.0")

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.remote.creation.core)
//    implementation(libs.cardview.v7)
    ksp(libs.androidx.room.compiler)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines)
    
    // OpenVPN (maintained upstream)
    implementation("com.github.appstacks0:openvpn:1.0.9")
//    implementation("com.github.murtmarsel:openvpn:b1f5b0a")

    // WireGuard
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // Для поддержки Java 8+ (требуется библиотекой)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // Required by OpenVPN service internals for state broadcasting
    implementation("org.greenrobot:eventbus:3.3.1")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}