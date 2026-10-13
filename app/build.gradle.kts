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
        // Harita kütüphaneleri yerel kod getiriyor: yalnız telefon işlemcileri (x86 öykünücü gerekmiyor)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Yandex MapKit anahtarı depo dışında (~/.config/obd-dashboard/yandex-mapkit.key); yoksa harita MapLibre'ye düşer
        val mk = file(System.getProperty("user.home") + "/.config/obd-dashboard/yandex-mapkit.key")
        buildConfigField("String", "YANDEX_MAPKIT_KEY", "\"" + (if (mk.exists()) mk.readText().trim() else "") + "\"")
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
    // SetTextI18n: arayüz metinleri bilerek kodda. LogNotTimber: MapLibre getiren Timber kuralı (Timber kullanmıyoruz).
    // ChromeOsAbiSupport: harita kütüphaneleri büyük, yalnız telefon işlemcileri
    lint { disable += listOf("SetTextI18n", "LogNotTimber", "ChromeOsAbiSupport") }
    buildFeatures { buildConfig = true }
}

dependencies {
    // Dönüşe yaklaşırken yuvarlak harita (TurnMapView). 12.x: Kotlin 2.0 stdlib, projenin 1.9 derleyicisiyle uyumlu.
    // Harita verisi OpenFreeMap (anahtarsız, ücretsiz; © OpenMapTiles, © OpenStreetMap katkıcıları)
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    // Yandex haritası ve yol tarifi (rota çizgisi Navigator'la aynı motordan). Ücretsiz sürüm şartları: SettingsActivity
    implementation("com.yandex.android:maps.mobile:4.45.0-full")
    // MapKit 2.11'i çekiyor (compileSdk 35 + AGP 8.6 ister). WorkManager yalnız çevrimdışı harita indirmede; 2.9.1 SDK 34'le derlenir
    implementation("androidx.work:work-runtime:2.9.1") { because("compileSdk 34") }
    constraints { implementation("androidx.work:work-runtime") { version { strictly("2.9.1") } } }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")  // android.jar'daki org.json JVM testinde boş
}
