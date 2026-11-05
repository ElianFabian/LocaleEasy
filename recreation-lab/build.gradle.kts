plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.elianfabian.recreationlab"
    compileSdk { version = release(36) }
    defaultConfig {
        applicationId = "com.elianfabian.recreationlab"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":locale-easy"))
    implementation(libs.androidx.startupRuntime)
}
