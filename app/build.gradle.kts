plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kushrike.batterydial"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kushrike.batterydial"
        minSdk = 35
        targetSdk = 36
        // CI passes the run number, so every build from main installs as an update over the previous one.
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // Release key from CI secrets (.github/workflows/build.yml). Without one, the debug key keeps sideloading working.
        val keystore = providers.environmentVariable("BATTERYDIAL_KEYSTORE").orNull
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("BATTERYDIAL_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("BATTERYDIAL_KEY_ALIAS").orNull ?: "batterydial"
            keyPassword = providers.environmentVariable("BATTERYDIAL_KEY_PASSWORD").orNull ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    androidResources { localeFilters += "en" }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
