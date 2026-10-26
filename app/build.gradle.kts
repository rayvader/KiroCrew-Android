plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number drives the version so every build installs over the last one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val hasReleaseKey = !signingStoreFile.isNullOrBlank() && file(signingStoreFile).exists()

android {
    namespace = "io.github.rayvader.kirocrew"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.rayvader.kirocrew"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
        // Where the app looks for updates. A fork built on GitHub Actions checks its own releases.
        val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: "rayvader/KiroCrew-Android"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "kirocrew"
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the CI key the release APK is still signed (debug key) so it installs.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for plain-JVM unit tests (the Android one is only stubs there).
    testImplementation("org.json:json:20240303")
}
