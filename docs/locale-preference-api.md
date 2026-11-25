# Locale preference API

`LocalePreference` describes the user's choice. `effectiveLocaleList` resolves that choice to an ordered locale list. Android may select a supported fallback language when loading individual resources. Activity recreation and view refresh can finish after the locale flows emit.

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

// Observe the primary language Android has saved for this app:
localeEasy.selectedAndroidAppLocale.collect { locale ->
    println(locale?.toLanguageTag())
}
```

`AppLocales` is a data class with value equality, `copy()`, and destructuring. It requires a nonempty ordered list whose locales round-trip as BCP 47 tags with a language. LocaleEasy copies the list when applying it and keeps the first occurrence of each locale. Use `setLanguageTag("fr-CA")` for input from a picker or a server. Use `FollowSystem` to reset the choice. Passing the same normalized preference again leaves the existing activity in place.

Startup restores the preference before exposing effective locales. Storage uses BCP 47 language tags and accepts legacy underscore region tags on read. Startup normalizes previously stored duplicates and recovers from missing or invalid overrides by following the system. A reset clears the stored override.

### Read the locale properties

| Property | Value |
| --- | --- |
| `preference` | Observable choice saved by LocaleEasy |
| `systemLocaleList` | Observable device languages, in preference order |
| `effectiveLocaleList` | Observable list applied by LocaleEasy |
| `selectedAndroidAppLocale` | Observable primary app language saved by Android. Null before API 33 or when Android has no app language set. |
| `androidSupportedLocaleList` | The current languages offered in Android Settings. Null before API 33 or without a valid Android locale configuration. |
| `localeCatalog` | The optional catalog for the LocaleEasy picker and fallback |

`androidSupportedLocaleList` reads the current Android `LocaleConfig` when accessed, including an override supplied by the host app on API 34+. `selectedAndroidAppLocale` emits even if the selection saved by LocaleEasy stays the same. LocaleEasy keeps its own preference when Android changes or clears its app language. Android supplies the saved language without its origin. [Android locale configuration](https://developer.android.com/reference/android/app/LocaleConfig) · [Android app language API](https://developer.android.com/reference/android/app/LocaleManager#getApplicationLocales())

`followSystem()`, `setAppLocale()`, and `setAppLocaleList()` are Kotlin extensions over `setPreference()`. To check whether the app follows the system, compare `preference.value` with `LocalePreference.FollowSystem`.

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

Read `LocaleEasy.getInstance().initialAppLocale` to retrieve the locale that `setInitialLocale()` applied. It survives restarts and later preference changes, including a reset to Follow system. It returns `null` when no initial locale was recorded, including installs that applied an initial locale before this property existed.

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

LocaleEasy loads the catalog resource at startup. The sample picker reads `LocaleEasy.getInstance().localeCatalog.supportedLocales`. Without a generated or manually supplied catalog, the list is empty and `defaultLocale` returns null.

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

#### Manual integration

Wrap the base context in `attachBaseContext()` when LocaleEasy should manage the language of an entire activity. This runs before `onCreate()`, so the activity starts with the selected resources when it inflates layouts and reads strings. LocaleEasy also uses that wrapper to identify which activities to recreate after a language change. For an individual resource lookup, call `localizedContext()` at the point of use, as shown below.

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
}
```

This works with plain `Activity`, `ComponentActivity`, and `AppCompatActivity`; AppCompat is not required by the library. LocaleEasy updates application resources and queues one refresh per started, wrapped activity when its selection is stale. Stopped activities refresh when started again, and finishing/destroyed activities are skipped. Rapid requests made before the queued refresh executes use the latest selection. Unwrapped third-party activities are not enrolled automatically.

#### Optional Gradle integration

Apply the LocaleEasy Gradle plugin in the application module and enable `instrumentActivities`. Keep the LocaleEasy runtime dependency in that module.

```kotlin
plugins {
    id("com.android.application")
    id("com.elianfabian.localeeasy.locales")
}

localeEasyLocales {
    instrumentActivities.set(true)
}
```

The option defaults to `false`. It inserts the `localizedContext()` call when the app is compiled. Existing `attachBaseContext()` methods are preserved, and subclasses inherit the integration through their normal call to `super`. An existing manual LocaleEasy wrapper can be reused. The automatic option works without a configured default language or a generated XML catalog.

Instrumentation applies to classes compiled in the application module. Activities provided directly by another module or dependency can use manual integration in their own code. If an external superclass has a final `attachBaseContext()`, the plugin skips that hierarchy with a build warning. LocaleEasy must be initialized before the first activity starts, which AndroidX Startup handles by default.

The sample enables activity instrumentation in `app/build.gradle.kts`. Its main and XML sample activities use the plugin to supply their localized contexts.

#### Configuration changes

Android owns recreation for system configuration events. Apps that opt out through `configChanges` remain responsible for updating their own views for those events. Use one locale owner; independently changing AppCompatDelegate or framework app locales can conflict with LocaleEasy's current context strategy. Bundle needed translations or arrange their download before selecting them in language-split app bundles.

The sample's **Open State & Controls** panel shows the effective locales, device locales, and the LocaleEasy catalog and its default. On API 33+, it also shows the Android app language and the options declared in the sample's `android:localeConfig`. The Android section opens the real app-language screen in Settings. The sample screens continue to use the preference chosen in the LocaleEasy picker. On earlier versions, the panel shows the LocaleEasy controls and locale information.

LocaleEasy recreates the activity through Android's lifecycle. Normal activity and fragment saved-state contracts apply. The sample gives its programmatic ComposeView a stable ID to support saved navigation state.

`fixWebViewLocale()` repairs the application resource locale in local mode if it differs from LocaleEasy's selection. Wrapped activities keep their own resource scope across relaunch and WebView manages its own web content. The sample loads deterministic local HTML for testing.

### Use `localizedContext()` outside an activity

Pass the context you already have to `localizedContext()` when building user-facing text. Obtain it at the point of use so the lookup picks up the current LocaleEasy preference.

#### Build a notification

Call this helper from a service, receiver, or worker with an existing notification channel ID.

```kotlin
import android.app.Notification
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.elianfabian.locale_easy.LocaleEasy

fun buildOrderReadyNotification(
    context: Context,
    channelId: String,
    @DrawableRes smallIcon: Int,
    orderNumber: String,
): Notification {
    val localized = LocaleEasy.getInstance().localizedContext(context)
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon)
        .setContentTitle(localized.getString(R.string.order_ready_title, orderNumber))
        .build()
}
```

#### Resolve a plural in a WorkManager worker

This example assumes the app already uses WorkManager. The worker generates a label when the job runs. The second `count` passed to `getQuantityString()` supplies the value for the number placeholder.

```kotlin
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.elianfabian.locale_easy.LocaleEasy

class UnreadMessageLabelWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val count = inputData.getInt("unread_count", 0)
        val localized = LocaleEasy.getInstance().localizedContext(applicationContext)
        val label = localized.resources.getQuantityString(R.plurals.unread_messages, count, count)
        return Result.success(workDataOf("label" to label))
    }
}
```

Define the resource IDs in the app, for example in `res/values/strings.xml`, and add translations in the corresponding language folders.

```xml
<resources>
    <string name="order_ready_title">Order %1$s is ready</string>
    <plurals name="unread_messages">
        <item quantity="one">%1$d unread message</item>
        <item quantity="other">%1$d unread messages</item>
    </plurals>
</resources>
```

A string already built keeps its original language. If you need to show it again after the user changes languages, resolve it again using a fresh localized context.

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
3. Select a language in the sample picker, then select **Follow System Locale**. Verify the preference resets to `FollowSystem` and survives restart.
4. Reapply the same selection and verify no additional refresh. Check both XML and Compose screens.

The [reliability checklist](locale-reliability-checklist.md) records implementation status separately from broader device acceptance.
