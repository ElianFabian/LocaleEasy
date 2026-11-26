import com.android.build.api.variant.BuildConfigField
import java.util.Locale

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	id("io.github.elianfabian.localeeasy")
}

val webViewFallbacks = providers.gradleProperty("sampleWebViewFallbacks").map(String::toBooleanStrict).orElse(false)

localeEasyLocales {
	generateFallbackCatalog.set(true)
	instrumentActivities.set(true)
	instrumentWebViewLoads.set(webViewFallbacks)
	instrumentWebViewInflation.set(webViewFallbacks)
	instrumentWebViewCreation.set(true)
	webViewContentViewActivities.add("com.elianfabian.localeeasy.WebViewContentFixtureActivity")
}

android {
	namespace = "com.elianfabian.localeeasy"
	compileSdk {
		version = release(36) {
			minorApiLevel = 1
		}
	}

	defaultConfig {
		applicationId = "com.elianfabian.localeeasy"
		minSdk = 23
		targetSdk = 36
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			optimization {
				enable = false
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		compose = true
		viewBinding = true
	}
}

androidComponents.onVariants { variant ->
	requireNotNull(variant.buildConfigFields).put(
		"WEB_VIEW_FALLBACKS",
		webViewFallbacks.map { BuildConfigField("boolean", it.toString(), null) },
	)
	val localeQualifier = Regex(
		"""^values-(?:mcc\d{3}-)?(?:mnc\d{1,3}-)?(b\+[a-z0-9+]+|(?!car(?:-|$))[a-z]{2,3}(?:-r[a-z]{2})?)(?:-|$)""",
		RegexOption.IGNORE_CASE,
	)
	val tags = requireNotNull(variant.sources.res).static.map { layers ->
		val roots = layers.flatten().map { it.asFile }
		val localized = roots.flatMap { it.listFiles().orEmpty().toList() }
			.filter { it.resolve("strings.xml").isFile }
			.mapNotNull { localeQualifier.find(it.name)?.groupValues?.get(1) }
			.map { qualifier ->
				val tag = qualifier.lowercase(Locale.ROOT)
				Locale.forLanguageTag(
					if (tag.startsWith("b+")) tag.substring(2).replace('+', '-') else tag.replace("-r", "-"),
				).toLanguageTag()
			}
			.filter { it != "und" }
			.toSortedSet()
		val entries = buildList {
			if (roots.any { it.resolve("values/strings.xml").isFile }) add("BuildConfig.DEFAULT_LANGUAGE")
			localized.forEach { add("\"$it\"") }
		}
		BuildConfigField("String[]", entries.joinToString(", ", "new String[] {", "}"), null)
	}
	requireNotNull(variant.buildConfigFields).put("STRINGS_XML_LANGUAGE_TAGS", tags)
}

dependencies {
	implementation(project(":locale-easy"))
	lintChecks(project(":locale-easy-lint"))

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.activity.ktx)
	implementation(libs.androidx.fragment.ktx)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	testImplementation(libs.junit)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	debugImplementation(libs.androidx.compose.ui.test.manifest)
	debugImplementation(libs.androidx.compose.ui.tooling)
}
