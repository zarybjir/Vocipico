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
        versionCode = 2
        versionName = "1.1"
    }

    // Podepisování release buildu: hodnoty z proměnných prostředí (GitHub
    // Actions) nebo z ~/.gradle/gradle.properties. Když chybí, vznikne debug APK.
    fun prop(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
    val ksPath = prop("PMT_KEYSTORE")
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = prop("PMT_KEYSTORE_PASSWORD")
                keyAlias = prop("PMT_KEY_ALIAS")
                keyPassword = prop("PMT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Zmenšení kódu = menší APK a rychlejší start na slabém telefonu.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
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
