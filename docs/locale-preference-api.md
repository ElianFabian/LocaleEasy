# Locale preference API

`LocalePreference` describes the user's choice. `effectiveLocales` resolves that choice to an ordered locale list. Neither flow signals that activity recreation or view refresh has finished; Android may select a supported fallback language when loading resources.

```kotlin
val localeEasy = LocaleEasy.getInstance()

localeEasy.followSystem()
localeEasy.setLocale(Locale.FRENCH)
localeEasy.setLocales(listOf(Locale.forLanguageTag("fr-CA"), Locale.ENGLISH))

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

// Observe languages to use for formatting or localized contexts:
localeEasy.effectiveLocales.collect { locales -> /* update dependent state */ }
```

`AppLocales` is a data class with generated value equality, `copy()`, and destructuring. It requires a nonempty ordered list whose locales have a language; validation also runs through `copy()`. Treat the supplied list as immutable: data-class copying is shallow. LocaleEasy uses its internal `immutableCopy()` helper when applying the preference so later mutations to the input list cannot change published state. Empty lists and `Locale.ROOT` are rejected before preferences or resources change. Use `FollowSystem` to reset the choice. Passing the same preference again does not request another refresh.

Startup restores the preference before exposing effective locales. Stored selections use BCP 47 language tags; common legacy underscore region tags are read for compatibility. Missing or invalid saved overrides recover to follow-system. Selecting follow-system clears the stored override; there is no remembered custom selection to restore.

Existing consumer calls remain available:

| Existing API | Meaning |
| --- | --- |
| `setAppLocale(locale)` | Alias for `setLocale(locale)` |
| `setAppLocaleList(locales)` | Alias for `setLocales(locales)`; empty lists are rejected |
| `appLocaleList` | Alias for `effectiveLocales` |
| `isFollowingSystemLocale` | Compatibility flow indicating follow-system mode |
| `followSystemLocale = true` | Select `FollowSystem` |
| `followSystemLocale = false` | If following system, freeze current effective locales as `AppLocales`; otherwise keep the app selection |

The flows represent individual observable values; reading multiple flows is not an atomic snapshot. Observe `preference` to determine the selected mode rather than inferring it from the effective list. This change adds interface members, so custom implementations of `LocaleEasy` must implement the new preference operations and effective flow.

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

Installation, locale mutations, and `fixWebViewLocale()` must run on the main thread. Calls from worker threads fail before changing state. Locale changes are synchronous requests; view recreation remains asynchronous.

By default on **API 33+**, the framework `LocaleManager` owns app-language overrides, persistence, and configuration delivery. Existing framework preferences take priority. A legacy stored override is migrated once when no framework override exists; subsequent resets in Android Settings do not revive it. Configuration callbacks and locale broadcasts reconcile the flows with external changes. Activities do not need wrapping on this path, but should call `localizedContext()` on every API to participate in the temporary manual-backend comparison described below. Consumers should declare their supported languages with `android:localeConfig` (as the sample does) to expose the app in Android's language settings.

On **API 21–32**, participating activities wrap their base context before inflation:

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
}
```

This works with plain `Activity`, `ComponentActivity`, and `AppCompatActivity`; AppCompat is not required by the library. LocaleEasy updates application resources and queues one refresh per started, wrapped activity when its selection is stale. Stopped activities refresh when started again, and finishing/destroyed activities are skipped. Rapid requests made before the queued refresh executes use the latest selection. Unwrapped third-party activities are not enrolled automatically.

Android owns recreation for system configuration events. Apps that opt out through `configChanges` remain responsible for updating their own views for those events. Use one locale owner; independently changing AppCompatDelegate locales below API 33 can conflict with LocaleEasy's context strategy. For language-split app bundles below API 33, bundle needed translations or arrange their download before selecting them.

`ViewCreatorComponent` is deprecated and is not called. LocaleEasy does not detach fragments, replace activity content, or reset runtime titles. Normal activity/fragment saved-state contracts apply. The sample gives its programmatic ComposeView a stable ID to support saved navigation state.

`fixWebViewLocale()` only reapplies native application/participating-activity resources in manual mode; it does not construct another WebView. It does not translate HTML, reload web content, or reinflate native views. It is a no-op while framework recreation is enabled. The sample loads deterministic local HTML so website preferences/network behavior do not obscure native locale behavior.

## Verification

Unit tests cover validation, data-class copying/destructuring, immutable copies, value equality, preference resolution, language-tag persistence, legacy region tags, and corrupt-storage recovery.

The executed emulator checks and remaining manual/device coverage are recorded in [the verification report](locale-verification.md). Additional device verification remains required for:

1. Select French on an English device, restart the process without clearing data, and verify the initial screen and newly opened sample activities use French.
2. Select follow-system, change system language, and verify effective languages and visible resources update. Repeat with a custom selection and verify it remains fixed.
3. Disable follow-system using the sample checkbox; verify the current languages become a custom preference and survive restart.
4. Reapply the same selection and verify no additional refresh. Check both XML and Compose screens.

The [reliability checklist](locale-reliability-checklist.md) records implementation status separately from broader device acceptance.

## Temporary recreation comparison

The sample now exposes one method selector backed by `LocaleEasy.useFrameworkLocaleRecreation`. The flag defaults to framework on API 33+ and manual below, and remembers the selection across restarts. Manual mode uses localized contexts even on API 33+; always call `localizedContext()` from your activity's `attachBaseContext()` when testing it. See [comparison instructions and limitations](recreation-comparison.md). This diagnostic switch does not change the preference model.
