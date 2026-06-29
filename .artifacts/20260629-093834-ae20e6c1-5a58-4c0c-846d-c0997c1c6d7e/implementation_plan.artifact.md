# Implementation Plan - LocaleEasy Sample App

This plan outlines the creation of a sample app for the `LocaleEasy` library, demonstrating its capabilities in Compose, legacy Activities/Fragments, and special WebView cases across multiple languages.

## Proposed Changes

### Resources
- Create `values-es`, `values-fr`, `values-pt`, and `values-it` directories.
- Add `strings.xml` for all these languages including English (default), with strings for titles, examples, and actions.

### Build Configuration
- Update `libs.versions.toml` to include `activity-ktx` and `fragment-ktx`.
- Update `app/build.gradle.kts` to include `appcompat`, `activity-ktx`, and `fragment-ktx`.

### App Module
#### [MainActivity.kt](file:///C:/Users/PC/Documents/@GitHub/LocaleEasy/app/src/main/java/com/elianfabian/localeeasy/MainActivity.kt)
- Implement `MainViewModel` using `LocaleEasy.getInstance()`.
- Use a sealed interface `UiAction` for UI-to-ViewModel communication.
- Implement `MainActivity` inheriting from `AppCompatActivity` and implementing `ViewCreatorComponent`.
- Add legacy `SampleActivity`, `SampleFragment`, and `WebViewFragment` classes to the same file for a self-contained sample.
- Build the Compose UI in `MainScreen` to display state and control locales.

#### [AndroidManifest.xml](file:///C:/Users/PC/Documents/@GitHub/LocaleEasy/app/src/main/AndroidManifest.xml)
- Register `SampleActivity`.

### Library Module
#### [AndroidLocaleEasy.kt](file:///C:/Users/PC/Documents/@GitHub/LocaleEasy/locale-easy/src/main/java/com/elianfabian/locale_easy/AndroidLocaleEasy.kt)
- Rename private `setFollowSystemLocale` to `persistFollowSystemLocale` to avoid JVM signature clash with the property setter.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure compilation and resource integrity.

### Manual Verification
- Verify that `MainActivity.kt` has no syntax errors using `analyze_file`.
- Check that all requested languages and sample types (Compose, Activity, Fragment, WebView) are represented in the code.
