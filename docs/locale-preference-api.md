# Locale preference API

`LocalePreference` describes the user's choice. `effectiveLocales` resolves that choice to an ordered locale list. Neither flow signals that activity recreation or view refresh has finished; Android may select a supported fallback language when loading resources.

```kotlin
import com.elianfabian.locale_easy.followSystem
import com.elianfabian.locale_easy.setAppLocale
import com.elianfabian.locale_easy.setAppLocaleList

val localeEasy = LocaleEasy.getInstance()

localeEasy.followSystem()
localeEasy.setAppLocale(Locale.FRENCH)
localeEasy.setLanguageTag("fr-CA")
localeEasy.setAppLocaleList(listOf(Locale.forLanguageTag("fr-CA"), Locale.ENGLISH))

// Equivalent explicit selection:
localeEasy.setPreference(LocalePreference.AppLocales(listOf(Locale.FRENCH)))
localeEasy.setPreference(LocalePreference.FollowSystem)

// Observe the selection in settings UI:
localeEasy.preference.collect { preference ->
    when (preference) {
        LocalePreference.FollowSystem -> Unit
        is LocalePreference.AppLocales -> println(preference.locales)
    }
}

// Read the preference and its resolution together in a settings UI:
localeEasy.state.collect { state ->
    println("${state.preference} resolves to ${state.effectiveLocales}")
}
```

`AppLocales` is a data class with value equality, `copy()`, and destructuring. It requires a nonempty ordered list whose locales round-trip as BCP 47 tags with a language. LocaleEasy copies the list when applying it and keeps the first occurrence of each locale. Use `setLanguageTag("fr-CA")` for input from a picker or a server. Use `FollowSystem` to reset the choice. Passing the same normalized preference again leaves the existing activity in place.

Startup restores the preference before exposing effective locales. Storage uses BCP 47 language tags and accepts legacy underscore region tags on read. Startup normalizes previously stored duplicates and recovers from missing or invalid overrides by following the system. A reset clears the stored override.

Existing consumer calls remain available:

| Existing API | Meaning |
| --- | --- |
| `appLocaleList` | Alias for `effectiveLocales` |
| `followSystemLocale = true` | Select `FollowSystem` |
| `followSystemLocale = false` | If following system, freeze current effective locales as `AppLocales`; otherwise keep the app selection |

Use `state` for a single coherent read of preference, system locales, and effective locales. The existing flows project individual values from this snapshot. Custom implementations of `LocaleEasy` must also expose a `StateFlow<LocaleState>`.

`followSystem()`, `setAppLocale()`, and `setAppLocaleList()` are Kotlin extensions over `setPreference()`. To check whether the app follows the system, compare `state.value.preference` with `LocalePreference.FollowSystem`.

## Android integration

AndroidX Startup now installs LocaleEasy automatically. Calling `install()` again is safe and retains the same instance and subscriptions. Applications that opt out can remove only LocaleEasy's initializer metadata and call `LocaleEasy.install(this)` from `Application.onCreate()`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup" tools:node="merge">
            <meta-data android:name="com.elianfabian.locale_easy.LocaleEasyInitializer"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

Installation, locale mutations, and `fixWebViewLocale()` must run on the main thread. A state emission represents an applied preference. View recreation and the first translated frame follow asynchronously. A request made from a collector while another change is being published is applied after that change, before the outer setter returns. Preferences are written to disk asynchronously.

### Optional initial language

With automatic initialization, an app that wants French on its first launch can make this call from `Application.onCreate()`, before its first activity opens.

```kotlin
LocaleEasy.getInstance().setInitialLocale(Locale.FRENCH)
```

The call applies and saves French if there is no saved preference. It is safe to repeat on each launch. A saved language and an explicit `followSystem()` choice both take precedence, including preferences written by earlier LocaleEasy versions. Without an initial language or a saved preference, LocaleEasy follows the system.

Custom implementations of `LocaleEasy` implement `setInitialLocale()` against their own preference storage.

### Optional Gradle language catalog

The companion plugin generates BCP 47 language tags in the app's `BuildConfig`. Apply it in the Android application module and declare the language of the unqualified `res/values` resources.

```kotlin
plugins {
    id("com.android.application")
    id("com.elianfabian.localeeasy.locales")
}

localeEasyLocales {
    defaultLanguage.set("en")
}
```

`BuildConfig.DEFAULT_LANGUAGE` contains the declared tag. `BuildConfig.SUPPORTED_LANGUAGES` starts with that tag and adds the distinct tags found in the app variant's static `values-*` directories. The catalog describes app source resources. Translation coverage, resource filters, and installed Play language splits determine what Android can resolve on a device.

#### Use the catalog for Follow system

Set `unqualifiedResLocale=en` in `src/main/res/resources.properties` and enable XML catalog generation in the app module:

```kotlin
localeEasyLocales {
    generateFallbackCatalog.set(true)
}
```

LocaleEasy loads the generated resource at startup. The sample picker reads `LocaleEasy.getInstance().localeCatalog.supportedLocales`. The catalog is a read-only property separate from `state`. Without generated XML, the list is empty and `defaultLocaleOrNull` returns null. Reading `defaultLocale` then throws an `IllegalStateException`.

While following the system, LocaleEasy keeps the system locales that match the catalog by language and script, in system preference order. It uses `defaultLocale` when none match. The stored preference stays `FollowSystem`, and subsequent system changes can select another language. Explicit app choices still apply. For example, an app with English fallback resources can call `setInitialLocale(Locale.FRENCH)` to save French on its first launch. A later explicit `followSystem()` uses English whenever the device has no supported language.

The repository includes the plugin build through `pluginManagement.includeBuild("locale-easy-gradle")`. To test it in another project, publish it locally with `./gradlew -p locale-easy-gradle publishToMavenLocal`. Add `mavenLocal()` to the consumer's `pluginManagement.repositories` and use plugin version `0.1.0`. The plugin is verified with the project's AGP version.

An app can also supply the catalog directly as `src/main/res/xml/locale_easy_catalog.xml`. The default locale is included in the supported list automatically. List the other languages the app wants to offer.

```xml
<locale-catalog defaultLocale="en">
    <locale tag="fr" />
    <locale tag="it" />
</locale-catalog>
```

### Activity integration

LocaleEasy currently uses **local activity recreation on every supported API**, including API 33+. It owns the persisted preference, updates application resources, and supplies localized activity contexts. Automatic framework locale recreation exposed a gap with no visible app surface in the tested configuration; [the investigation](framework-window-handoff-investigation.md) documents the evidence and a promising hybrid that has not been promoted into the normal backend. This does not mean every use of LocaleManager must flash.

On **all API levels**, participating activities wrap their base context before inflation:

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
}
```

This works with plain `Activity`, `ComponentActivity`, and `AppCompatActivity`; AppCompat is not required by the library. LocaleEasy updates application resources and queues one refresh per started, wrapped activity when its selection is stale. Stopped activities refresh when started again, and finishing/destroyed activities are skipped. Rapid requests made before the queued refresh executes use the latest selection. Unwrapped third-party activities are not enrolled automatically.

Android owns recreation for system configuration events. Apps that opt out through `configChanges` remain responsible for updating their own views for those events. Use one locale owner; independently changing AppCompatDelegate or framework app locales can conflict with LocaleEasy's current context strategy. Bundle needed translations or arrange their download before selecting them in language-split app bundles.

Local mode owns its selection inside the app. The sample exposes its language choices in **Open State & Controls**. On API 33+, local mode preserves independently configured Android app locales and reads the system's actual locale list. If a stored marker identifies an earlier LocaleEasy framework experiment, startup imports that experiment's platform choice and clears the override and marker.

LocaleEasy recreates the activity through Android's lifecycle. Normal activity and fragment saved-state contracts apply. The sample gives its programmatic ComposeView a stable ID to support saved navigation state.

`fixWebViewLocale()` repairs the application resource locale in local mode if it differs from LocaleEasy's selection. Wrapped activities keep their own resource scope across relaunch and WebView manages its own web content. The sample loads deterministic local HTML for testing.

## API and implementation review

The [library roadmap](library-roadmap.md) updates implementation priority with the API 24 input, cold-start and re-entrant collector results. It includes acceptance checks for the API behavior described below.

Reviewed on 2026-11-22 against the checked-out library and the upstream APIs below. LocaleEasy's core use case is an in-app language picker that exposes the selected preference as state and refreshes participating Android activities. Its ordered preferences and lifecycle-aware refresh already cover more of that workflow than a resource-only locale setter.

| Reference | What it adds to the comparison |
| --- | --- |
| [Lingver source](https://raw.githubusercontent.com/YarikSOffice/lingver/master/library/src/main/java/com/yariksoffice/lingver/Lingver.kt) | Accepts a first-launch locale and a custom `LocaleStore`. Its public setters use a single locale and the caller refreshes already-fetched locale-dependent content. LocaleEasy has ordered preferences, reactive state, and activity refresh. |
| [Akexorcist Localization](https://github.com/akexorcist/Localization) | The archived library offers a first-launch language, locale-change hooks, and examples for services and broadcast receivers. Its activity delegate also defers back-stack refresh until the activity returns. LocaleEasy's equivalent resource lookup already uses `localizedContext()`. |
| [AndroidX AppCompat and Android per-app languages](https://developer.android.com/guide/topics/resources/app-languages) | Integrates the system language picker with app preferences on API 33+ and supports Android's language splits and backup integration. [AppCompat's legacy setter](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat)) applies through the delegate on older Android releases. LocaleEasy gives a plain activity the same explicit integration as an AppCompat activity and keeps local recreation as its default. |
| [Localazy SDK](https://localazy.com/docs/android/localazy-android-library) | Exposes a catalog of locales and a resolved locale for its translation dataset. It also supports dynamically delivered translations. These are useful reference points for a language picker, while translation delivery can stay with the application or provider. |

### Priorities

1. **Verify the published dependency metadata.** A minimal Android consumer assembled with `implementation(project(":locale-easy"))` once the library exported `kotlinx-coroutines-core` with `api`. Repeat the check against the published artifact when publication is configured. [Gradle dependency visibility](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation)
2. **Keep a host-owned language catalog.** Distinguish supported choices, installed translations, and the preferred ordered locale list. On a Play language-split build, arrange delivery before selecting a language whose resources are absent. On API 21–23, LocaleEasy stores the full preference list and applies the first locale to Android resources. [Android language delivery](https://developer.android.com/guide/playcore/feature-delivery/on-demand#lang_resources)
3. **Keep resource lookup close to where text is built.** When creating a notification or a worker result, use `LocaleEasy.getInstance().localizedContext(context).getString(R.string.description)`. A custom wrapper keeps the selection it was created with. Obtain a fresh wrapper after a later selection. The preference store and flows are scoped to one app process.
4. **Wait for concrete consumers before expanding persistence or refresh.** Profile-backed storage would need an initialization contract across AndroidX Startup and synchronous `attachBaseContext()`. Validate any in-place refresh on a screen that owns all its localized content and state before exposing a library API. The Pixel factory experiment built content twice and lost editor state. [View-factory comparison](recreation-comparison.md)

## Verification

Unit tests cover validation, data-class copying/destructuring, immutable copies, value equality, preference resolution, language-tag persistence, legacy region tags, and corrupt-storage recovery.

The executed emulator checks and remaining manual/device coverage are recorded in [the verification report](locale-verification.md). Additional device verification remains required for:

1. Select French on an English device, restart the process without clearing data, and verify the initial screen and newly opened sample activities use French.
2. Select follow-system, change system language, and verify effective languages and visible resources update. Repeat with a custom selection and verify it remains fixed.
3. Disable follow-system using the sample checkbox; verify the current languages become a custom preference and survive restart.
4. Reapply the same selection and verify no additional refresh. Check both XML and Compose screens.

The [reliability checklist](locale-reliability-checklist.md) records implementation status separately from broader device acceptance.

## Framework experiment migration

The temporary `LocaleEasy.useFrameworkLocaleRecreation` switch was removed. LocaleEasy applies language changes through local activity recreation. Existing installs with a stored framework experiment marker still migrate their saved choice at startup. [Recreation comparison](recreation-comparison.md)
