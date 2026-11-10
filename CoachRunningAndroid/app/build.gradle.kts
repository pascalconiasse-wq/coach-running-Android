plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "fr.coach.running"
    compileSdk = 34
    defaultConfig {
        applicationId = "fr.coach.running"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Clé de signature fixe (fournie par GitHub Actions) : même signature à chaque version,
    // donc Android garde les autorisations lors des mises à jour.
    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        // Clé de secours fixe du dépôt : même signature à chaque version même sans secrets GitHub.
        getByName("debug") {
            storeFile = file("../../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (ksFile != null) create("fixe") {
            storeFile = file(ksFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "coach"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            if (ksFile != null) signingConfig = signingConfigs.getByName("fixe")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
