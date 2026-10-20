plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseKeystorePath = providers.environmentVariable("MOCKGPS_KEYSTORE_FILE").orNull

android {
    namespace = "com.sora.mockgps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bloss0m.bloomwalk"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val gatewayBaseUrl = providers.gradleProperty("BLOOMWALK_GATEWAY_BASE_URL")
            .orElse(providers.environmentVariable("BLOOMWALK_GATEWAY_BASE_URL"))
            .orElse("https://bloomwalk-gateway.example.invalid")
            .get()
        val admobAppId = providers.gradleProperty("BLOOMWALK_ADMOB_APP_ID")
            .orElse(providers.environmentVariable("BLOOMWALK_ADMOB_APP_ID"))
            .orElse("ca-app-pub-3940256099942544~3347511713")
            .get()
        val admobBannerUnitId = providers.gradleProperty("BLOOMWALK_ADMOB_BANNER_UNIT_ID")
            .orElse(providers.environmentVariable("BLOOMWALK_ADMOB_BANNER_UNIT_ID"))
            .orElse("ca-app-pub-3940256099942544/9214589741")
            .get()
        val privacyPolicyUrl = providers.gradleProperty("BLOOMWALK_PRIVACY_POLICY_URL")
            .orElse(providers.environmentVariable("BLOOMWALK_PRIVACY_POLICY_URL"))
            .orElse("https://bloomwalk-gps.pages.dev/privacy/zh-Hant/")
            .get()
        val supportUrl = providers.gradleProperty("BLOOMWALK_SUPPORT_URL")
            .orElse(providers.environmentVariable("BLOOMWALK_SUPPORT_URL"))
            .orElse("https://bloomwalk-gps.pages.dev/support/zh-Hant/")
            .get()
        val receiptPublicKeysJson = providers.gradleProperty("BLOOMWALK_RECEIPT_PUBLIC_KEYS_JSON")
            .orElse(providers.environmentVariable("BLOOMWALK_RECEIPT_PUBLIC_KEYS_JSON"))
            .orElse("[]")
            .get()
        buildConfigField("String", "GATEWAY_BASE_URL", "\"$gatewayBaseUrl\"")
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$admobBannerUnitId\"")
        buildConfigField("String", "BILLING_PRODUCT_ID", "\"remove_ads_lifetime\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        buildConfigField("String", "SUPPORT_URL", "\"$supportUrl\"")
        buildConfigField("String", "RECEIPT_PUBLIC_KEYS_JSON", "\"${receiptPublicKeysJson.replace("\"", "\\\"")}\"")
        buildConfigField("String", "API_CONTRACT_REVISION", "\"v1\"")
        manifestPlaceholders["admobAppId"] = admobAppId
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("MOCKGPS_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("MOCKGPS_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("MOCKGPS_KEY_PASSWORD").get()
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            ndk {
                // Play 16 KB applies to 64-bit native libs. Omit 32-bit MapLibre (4 KB ELF).
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.location)
    implementation(libs.maplibre.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("validateReleaseConfig") {
    group = "verification"
    description = "Rejects upload-unsafe release configuration; unsigned CI builds stay allowed."
    doLast {
        val upload = providers.environmentVariable("BLOOMWALK_RELEASE_UPLOAD").orNull == "true"
        val gateway = providers.environmentVariable("BLOOMWALK_GATEWAY_BASE_URL")
            .orElse("https://bloomwalk-gateway.example.invalid").get()
        val privacy = providers.environmentVariable("BLOOMWALK_PRIVACY_POLICY_URL")
            .orElse("https://bloomwalk-gps.pages.dev/privacy/zh-Hant/").get()
        val support = providers.environmentVariable("BLOOMWALK_SUPPORT_URL")
            .orElse("https://bloomwalk-gps.pages.dev/support/zh-Hant/").get()
        val admobApp = providers.environmentVariable("BLOOMWALK_ADMOB_APP_ID")
            .orElse("ca-app-pub-3940256099942544~3347511713").get()
        val banner = providers.environmentVariable("BLOOMWALK_ADMOB_BANNER_UNIT_ID")
            .orElse("ca-app-pub-3940256099942544/9214589741").get()
        val usesInvalid = listOf(gateway, privacy, support).any { it.contains(".invalid") || it.contains(".example") }
        val usesTestAds = admobApp.contains("3940256099942544") || banner.contains("3940256099942544")
        val empty = listOf(gateway, privacy, support, admobApp, banner).any { it.isBlank() }
        val receiptKeysJson = providers.environmentVariable("BLOOMWALK_RECEIPT_PUBLIC_KEYS_JSON")
            .orElse("[]").get()
        val receiptKeysMissing = receiptKeysJson.isBlank() || receiptKeysJson == "[]"
        val debugSigning = releaseKeystorePath == null
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val allowsCleartext = Regex("""android:usesCleartextTraffic\s*=\s*"true"""").containsMatchIn(manifest)
        if (!upload) {
            logger.lifecycle(
                "Release configuration check: UNSIGNED_NOT_UPLOADABLE " +
                    "(invalidUrls=$usesInvalid testAds=$usesTestAds empty=$empty " +
                    "receiptKeysMissing=$receiptKeysMissing debugSigning=$debugSigning cleartext=$allowsCleartext)",
            )
            return@doLast
        }
        require(!usesInvalid) { "Upload release rejects .invalid or example URLs." }
        require(!usesTestAds) { "Upload release rejects AdMob test IDs." }
        require(!empty) { "Upload release rejects empty public config." }
        require(!receiptKeysMissing) { "Upload release rejects missing receipt public keys." }
        require(!debugSigning) { "Upload release rejects debug signing." }
        require(!allowsCleartext) { "Upload release rejects debug cleartext traffic." }
    }
}
