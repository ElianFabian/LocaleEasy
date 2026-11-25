import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.elianfabian.locale_easy"
	compileSdk {
		version = release(35)
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

kotlin {
	compilerOptions {
		// Keep the AAR readable by older Kotlin compilers used by consuming apps.
		languageVersion.set(KotlinVersion.KOTLIN_1_9)
		apiVersion.set(KotlinVersion.KOTLIN_1_9)
	}
}

dependencies {
	implementation(libs.runtime.kotlin.stdlib)
	api(libs.kotlinxCoroutinesCore)
	implementation(libs.androidx.startupRuntime)
	implementation(libs.kotlinxCoroutinesAndroid)

	implementation(libs.runtime.core.ktx)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	androidTestUtil(libs.androidx.orchestrator)
}
