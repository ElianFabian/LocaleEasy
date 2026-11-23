plugins {
	`java-gradle-plugin`
	`maven-publish`
}

group = "com.elianfabian.localeeasy"
version = "0.1.0"

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
	compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
	testImplementation(libs.junit)
}

gradlePlugin {
	plugins {
		create("localeEasyLocales") {
			id = "com.elianfabian.localeeasy.locales"
			implementationClass = "com.elianfabian.localeeasy.gradle.LocaleCatalogPlugin"
			displayName = "LocaleEasy language catalog"
			description = "Generates BuildConfig language tags from an Android app variant's resource directories."
		}
	}
}
