plugins {
	id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.elianfabian.localeeasy"
version = libs.versions.localeEasyPlugin.get()

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
	compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
	implementation("org.ow2.asm:asm:9.9")
	testImplementation(libs.junit)
}

gradlePlugin {
	website = "https://github.com/ElianFabian/LocaleEasy"
	vcsUrl = "https://github.com/ElianFabian/LocaleEasy.git"
	plugins {
		create("localeEasyLocales") {
			id = "io.github.elianfabian.localeeasy"
			implementationClass = "com.elianfabian.localeeasy.gradle.LocaleCatalogPlugin"
			displayName = "LocaleEasy language catalog"
			description = "Discovers app languages and optionally integrates LocaleEasy with activities."
			tags.set(listOf("android", "localization", "internationalization"))
		}
	}
}
