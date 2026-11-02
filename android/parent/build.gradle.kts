plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.eyen.parent"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.eyen.parent"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
}
