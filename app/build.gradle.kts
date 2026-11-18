plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.pascal.appelstation"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.pascal.appelstation"
        minSdk = 26
        targetSdk = 35
        // Numéro = dernière version publiée + 1 (les tests ne comptent pas)
        val props = java.util.Properties().apply { file("version.properties").inputStream().use { load(it) } }
        val numero = props.getProperty("PUBLIE").trim().toInt() + 1
        versionCode = numero
        versionName = "1.$numero" + (System.getenv("SUFFIXE_VERSION") ?: "")
    }

    signingConfigs {
        create("fixe") {
            // Clé protégée par un mot de passe stocké dans les secrets GitHub
            // (APPEL_STATION_KEY_PASSWORD), jamais dans le dépôt.
            val mdp = System.getenv("APPEL_STATION_KEY_PASSWORD") ?: ""
            storeFile = file("appelstation-v2.p12")
            storeType = "PKCS12"
            storePassword = mdp
            keyAlias = "appelstation"
            keyPassword = mdp
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Clé fixe : chaque nouvelle version s'installe par-dessus l'ancienne
            signingConfig = signingConfigs.getByName("fixe")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
