import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.nutrition.tracker"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/nutrition-release.jks")
            storePassword = "ccn748463"
            keyAlias = "nutrition"
            keyPassword = "ccn748463"
        }
    }

    defaultConfig {
        applicationId = "uk.nutritiontracker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "1.0"

        // Backend proxy: AI/USDA keys moved to the server (see backend/ARCHITECTURE.md).
        // Default = production URL; override via backend.base.url in local.properties
        // (e.g. http://10.0.2.2:3000/ so the Android emulator sees the host's localhost).
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${localProperties.getProperty("backend.base.url", "https://api.nutritiontracker.uk/")}\""
        )
        buildConfigField(
            "String",
            "DEV_AUTH_SECRET",
            "\"${localProperties.getProperty("dev.auth.secret", "change-me-local-dev-secret")}\""
        )
        // Sign-in (web-OAuth через Custom Tabs). Плейсхолдеры до боевых кредов.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("google.web.client.id", "REPLACE_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_IOS_CLIENT_ID",
            "\"${localProperties.getProperty("google.ios.client.id", "634452098876-85n6iabtmhsjm12vqpkdmruk2g72qaq8.apps.googleusercontent.com")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_IOS_CLIENT_ID_SHORT",
            "\"${localProperties.getProperty("google.ios.client.id.short", "634452098876-85n6iabtmhsjm12vqpkdmruk2g72qaq8")}\""
        )
        buildConfigField(
            "String",
            "APPLE_SERVICES_ID",
            "\"${localProperties.getProperty("apple.services.id", "com.nutrition.tracker.signin")}\""
        )
        buildConfigField(
            "String",
            "OAUTH_REDIRECT_SCHEME",
            "\"${localProperties.getProperty("oauth.redirect.scheme", "com.nutrition.tracker")}\""
        )
        // Play Integrity: Google Cloud project number linked in the Play Console.
        // Long literal (note the trailing L). 0 = not configured → attestation skipped
        // (best-effort; a dev-mode backend still accepts X-Dev-Auth). Set the real number
        // via play.integrity.cloud.project.number in local.properties before prod.
        buildConfigField(
            "long",
            "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
            "${localProperties.getProperty("play.integrity.cloud.project.number", "0")}L"
        )

        // Ship only the localizations we actually provide.
        resourceConfigurations += listOf("ru", "uk", "en", "de", "es", "fr", "it", "pt")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    // Auth: EncryptedSharedPreferences (токены) + Custom Tabs (web-OAuth)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)

    // Play Integrity (Android attestation — «подлинная ли это сборка/устройство»)
    implementation(libs.play.integrity)

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation(libs.androidx.ui.tooling)
}
