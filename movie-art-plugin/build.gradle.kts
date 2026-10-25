
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from APP_VERSION set by GitHub Actions.
val appVersionName: String =
    System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

val appVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.-]+)?$""")
        .matchEntire(appVersionName)
        ?: throw GradleException(
            "APP_VERSION '$appVersionName' is not x.y.z or x.y.z-suffix"
        )

    val (major, minor, patch) =
        match.destructured.toList().take(3).map { it.toInt() }

    if (minor > 99 || patch > 99) {
        throw GradleException("minor and patch must be 0..99: $appVersionName")
    }

    maxOf(1, major * 10000 + minor * 100 + patch)
}

val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    ?.let { file(it) }
    ?.takeIf { it.exists() }

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
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables.useSupportLibrary = true
        // Posters come directly from Kinowatch via Android TV data.
    }

    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            signingConfig =
                signingConfigs.findByName("release")
                    ?: signingConfigs.getByName("debug")

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
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
}
