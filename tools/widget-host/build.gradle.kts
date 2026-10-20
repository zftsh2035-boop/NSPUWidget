plugins { id("com.android.application") }
android {
    namespace = "ru.nspu.widgettesthost"
    compileSdk = 35
    defaultConfig { applicationId = "ru.nspu.widgettesthost"; minSdk = 26; targetSdk = 35; versionCode = 1 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
