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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":locale-easy"))
    implementation(libs.androidx.startupRuntime)
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
}
