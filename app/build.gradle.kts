plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nebi.obdbridge"
    compileSdk = 34

    defaultConfig {
        // Forum sürümü kimliği (2026-10-11). Kod paketi (namespace) com.nebi.obdbridge kalır.
        applicationId = "com.obddashboard.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Yayın anahtarı depo dışında: ~/.config/obd-dashboard/{release.jks, keystore.pass} (yedeği kullanıcıda)
    val ks = file(System.getProperty("user.home") + "/.config/obd-dashboard/release.jks")
    val ksPass = file(System.getProperty("user.home") + "/.config/obd-dashboard/keystore.pass")
    signingConfigs {
        if (ks.exists() && ksPass.exists()) create("release") {
            storeFile = ks
            storePassword = ksPass.readText().trim()
            keyAlias = "obddashboard"
            keyPassword = ksPass.readText().trim()
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Geliştirme sürümü de aynı anahtarla: telefondaki yayın kurulumunun üstüne veri kaybetmeden kurulabilsin
        getByName("debug") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "SetTextI18n" }  // arayüz metinleri bilerek kodda
    buildFeatures { buildConfig = true }
}

dependencies {
    // Dönüşe yaklaşırken yuvarlak harita (TurnMapView). 12.x: Kotlin 2.0 stdlib, projenin 1.9 derleyicisiyle uyumlu.
    // Harita verisi OpenFreeMap (anahtarsız, ücretsiz; © OpenMapTiles, © OpenStreetMap katkıcıları)
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")  // android.jar'daki org.json JVM testinde boş
}
