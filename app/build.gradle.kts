plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "ru.nspu.schedule"
    compileSdk = 35
    defaultConfig { applicationId = "ru.nspu.schedule"; minSdk = 26; targetSdk = 35; versionCode = 15; versionName = "2.3"; buildConfigField("String", "GIT_SHA", "\"${System.getenv("GITHUB_SHA") ?: "unknown"}\"") }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    // Do not commit a signing key. Stable signing is enabled only with GitHub Secrets.
    val signingPath = System.getenv("NSPU_SIGNING_KEYSTORE")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs {
            create("production") {
                storeFile = file(signingPath)
                storePassword = System.getenv("NSPU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NSPU_KEY_ALIAS")
                keyPassword = System.getenv("NSPU_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Installable optimized debug APK until a persistent release key is configured.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!signingPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("production")
            }
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
