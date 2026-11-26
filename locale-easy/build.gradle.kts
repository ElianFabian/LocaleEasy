import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
	alias(libs.plugins.android.library)
	`maven-publish`
}

val publicationVersion = providers.environmentVariable("JITPACK")
	.filter { it == "true" }
	.flatMap { providers.environmentVariable("VERSION") }
	.orElse(libs.versions.localeEasy)

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
	publishing {
		singleVariant("release") {
			withSourcesJar()
		}
	}
}

afterEvaluate {
	publishing {
		publications {
			create<MavenPublication>("release") {
				from(components["release"])
				groupId = "com.github.ElianFabian.LocaleEasy"
				artifactId = project.name
				version = publicationVersion.get()
				pom {
					name.set("LocaleEasy")
					description.set("Reactive Android app language preferences and localized resources.")
					url.set("https://github.com/ElianFabian/LocaleEasy")
					licenses {
						license {
							name.set("MIT License")
							url.set("https://opensource.org/license/mit")
						}
					}
				}
			}
		}
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
	lintPublish(project(":locale-easy-lint"))
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
