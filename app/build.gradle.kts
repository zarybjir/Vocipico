plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.prectimito.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.prectimito.app"
        // Android 7.0 — ať to běží i na starším telefonu.
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    // Podepisování release buildu: cesta ke klíči (PMT_KEYSTORE) a heslo
    // (PMT_KEYSTORE_PASSWORD) z proměnných prostředí (GitHub Actions) nebo
    // z ~/.gradle/gradle.properties. Bez nich vznikne jen debug APK.
    fun prop(name: String): String? =
        (System.getenv(name) ?: (project.findProperty(name) as String?))?.takeIf { it.isNotBlank() }
    val ksPath = prop("PMT_KEYSTORE")
    val ksPassword = prop("PMT_KEYSTORE_PASSWORD")
    val canSign = ksPath != null && ksPassword != null
    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storeType = "PKCS12"
                storePassword = ksPassword
                keyAlias = "precti"
                keyPassword = ksPassword
            }
        }
    }

    buildTypes {
        release {
            // Zmenšení kódu = menší APK a rychlejší start na slabém telefonu.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (canSign) signingConfig = signingConfigs.getByName("release")
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

// Záměrně žádné další knihovny: jen čistý Android. Menší APK, méně paměti.
