plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trotabares.argentascomandas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trotabares.argentascomandas"
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "0.4.7"
        resValue("string", "app_name", "Argentas")
    }

    buildTypes {
        getByName("debug") {
            // Install the audit build alongside production without replacing its data.
            applicationIdSuffix = ".audit"
            versionNameSuffix = "-audit"
            resValue("string", "app_name", "Argentas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
