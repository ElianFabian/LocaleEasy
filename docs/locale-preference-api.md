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

Activities using the custom context integration still call `localizedContext()` from `attachBaseContext()`. This change does not replace the activity recreation strategy or introduce a framework `LocaleManager` backend.

## Verification

Unit tests cover validation, data-class copying/destructuring, immutable copies, value equality, preference resolution, language-tag persistence, legacy region tags, and corrupt-storage recovery.

Manual device verification remains required for Android lifecycle behavior:

1. Select French on an English device, restart the process without clearing data, and verify the initial screen and newly opened sample activities use French.
2. Select follow-system, change system language, and verify effective languages and visible resources update. Repeat with a custom selection and verify it remains fixed.
3. Disable follow-system using the sample checkbox; verify the current languages become a custom preference and survive restart.
4. Reapply the same selection and verify no additional refresh. Check both XML and Compose screens.

The broader remaining recreation, threading, back-stack, and WebView work is tracked in [the reliability checklist](locale-reliability-checklist.md). Its original checkboxes are not a claim that these device checks have passed.
