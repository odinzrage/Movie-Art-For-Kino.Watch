import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

configure<ApplicationExtension> {
    namespace = "com.movieart.kinowatch"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.movieart.kinowatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.4.1-kinowatch-wide"

        vectorDrawables.useSupportLibrary = true
        // No TMDB API key needed in this fork - posters come directly from the source
        // app (Kinowatch) via Android TV's PreviewProgram/WatchNext data.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            // Signed with the standard debug key so the release APK is installable without a private keystore
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation(project(":api"))
    implementation("com.squareup.okhttp3:okhttp:5.3.2") // For HTTP requests
}
