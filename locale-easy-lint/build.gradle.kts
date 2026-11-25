import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("org.jetbrains.kotlin.jvm")
}

kotlin {
	compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
	compileOnly(kotlin("stdlib"))
	compileOnly("com.android.tools.lint:lint-api:31.8.0")
}
