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

LocaleEasy currently uses **local activity recreation on every supported API**, including API 33+. It owns the persisted preference, updates application resources, and supplies localized activity contexts. Automatic framework locale recreation exposed a gap with no visible app surface in the tested configuration; [the investigation](framework-window-handoff-investigation.md) documents the evidence and a promising hybrid that has not been promoted into the normal backend. This does not mean every use of LocaleManager must flash.

On **all API levels**, participating activities wrap their base context before inflation:

```kotlin
override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleEasy.getInstance().localizedContext(newBase))
}
```

This works with plain `Activity`, `ComponentActivity`, and `AppCompatActivity`; AppCompat is not required by the library. LocaleEasy updates application resources and queues one refresh per started, wrapped activity when its selection is stale. Stopped activities refresh when started again, and finishing/destroyed activities are skipped. Rapid requests made before the queued refresh executes use the latest selection. Unwrapped third-party activities are not enrolled automatically.

Android owns recreation for system configuration events. Apps that opt out through `configChanges` remain responsible for updating their own views for those events. Use one locale owner; independently changing AppCompatDelegate or framework app locales can conflict with LocaleEasy's current context strategy. Bundle needed translations or arrange their download before selecting them in language-split app bundles.

Android's per-app language Settings do not control the current manual backend. On first installation without an existing LocaleEasy preference, or when migrating an old persisted framework-owner switch, the current framework selection is imported before its override is cleared. This also respects an OS reset when migrating from framework ownership. Saved LocaleEasy preferences take priority afterward. The stale comparison switch is removed at startup, and normal language changes do not write `LocaleManager.applicationLocales`. LocaleManager is still used on API 33+ to read the real system locales and clear a previous framework override.

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

## Temporary framework diagnostic

`LocaleEasy.useFrameworkLocaleRecreation` is retained solely as an explicit API-33+ investigation opt-in. It starts false in every process, is not persisted, and is absent from the normal sample UI. Existing diagnostic tests can enable it for that process; a restart returns to local recreation while preserving the selected language. See [sample instructions](recreation-comparison.md). This diagnostic switch does not change the preference model.
