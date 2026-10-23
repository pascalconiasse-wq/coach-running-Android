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
