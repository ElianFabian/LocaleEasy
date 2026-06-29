plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.elianfabian.locale_easy"
	compileSdk {
		version = release(36)
	}

	defaultConfig {
		minSdk = 21

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		testInstrumentationRunnerArguments["clearPackageData"] = "true"

		consumerProguardFiles("consumer-rules.keep")
	}
	testOptions {
		execution = "ANDROIDX_TEST_ORCHESTRATOR"
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	kotlin {
		explicitApi()
	}
}

dependencies {
	implementation(libs.androidx.startupRuntime)
	implementation(libs.kotlinxCoroutinesAndroid)

	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.navigation.fragment.ktx)
	implementation(libs.material)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
}
