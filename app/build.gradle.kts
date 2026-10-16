plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.nspu.schedule"
    compileSdk = 35
    defaultConfig { applicationId = "ru.nspu.schedule"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "1.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
