plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kommt aus GitHub Actions (Laufnummer), damit jede neue APK eine hoehere
// Version hat und sich ueber die alte installieren laesst.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "io.github.obitusde.aufnahme"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.obitusde.aufnahme"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../signing/release.jks")
            storePassword = "aufnahme-app"
            keyAlias = "aufnahme"
            keyPassword = "aufnahme-app"
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
