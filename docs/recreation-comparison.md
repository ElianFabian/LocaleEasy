# LocaleEasy sample: local recreation and translation markers

The normal sample now uses local activity recreation on every supported API. The framework-method selector has been removed. Run the **app** module, tap **Open State & Controls**, and choose a language. The current activity and its views rebuild through the normal lifecycle; saved Compose navigation and the settings sheet are restored.

The home screen reports **Local activity recreation**, the effective locales, and the available sample screens. The library supports API 21+; the sample supports API 23+. Call `localizedContext()` from `attachBaseContext()` on every Android version, including API 33+.

## Text colors

- **Green — localizable text:** strings loaded from translatable Android resources, including screen titles, button labels, formatted dates, and locale-state descriptions. A string remains green when the current language is English or falls back to English; the color describes its source rather than proving a translation exists.
- **Muted gray — English-only text:** fixed descriptions, diagnostic labels, hardcoded markers, language-choice names, the Compose Back label, and the local HTML fixture.

The legend follows the same rule: its green label is localizable, while its gray label intentionally stays English. Native XML, Compose, dialogs, and WebView controls use the same markers, with light/dark variants. Localizable buttons use a pale/dark green surface so their green text remains readable. The HTML itself remains English and is not translated by Android locale changes.

## Why automatic framework recreation is not the normal backend

In the tested API-36 configuration, automatic LocaleManager recreation removed the old app surface before the replacement was visible. Local recreation preserved the window. Code comments link to [the surface-trace investigation](framework-window-handoff-investigation.md), which also shows a supported hybrid retaining framework preferences. That prototype requires an activity/manifest integration contract and wider verification; it is not enabled as the normal implementation.

The temporary `useFrameworkLocaleRecreation` API property remains available for investigation only. It defaults to false in **every process**, ignores/removes the obsolete persisted comparison switch, and cannot be enabled below API 33. It is not exposed by the sample UI. Changing it during an experiment can itself recreate activities. Normal locale changes do not write framework app-language preferences.

The manual backend does not integrate with Android's per-app language Settings. It retains saved LocaleEasy selections across process restarts and clears a previous framework override during installation so the two owners do not compete. The [API document](locale-preference-api.md) describes first-install migration and context integration.

## Verification

Run the sample instrumentation suite on an explicitly selected disposable device:

```powershell
$env:ANDROID_SERIAL = 'DEVICE_SERIAL'
./gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

The normal UI tests change French → Italian → Spanish in place, assert activity/root replacement within the same task, and check that framework locales remain empty on API 33+. Lifecycle checks cover XML input, stopped activities, runtime titles, Compose navigation, dialogs, and identical/rapid requests using local recreation on modern Android too. Separate framework tests explicitly opt in and return to manual mode afterward.

Run seed/verify persistence phases separately to check a true process restart. A `localeBackend=framework` seed deliberately reproduces the old persisted switch; verification must preserve French while returning to manual mode and clearing the stale switch/framework override. `framework_only` seeds an OS preference without library storage; `framework_stale` leaves older Spanish library storage while the framework preference is French. Both must import the authoritative French selection and then use local recreation.

Manual checks remain necessary for perceived transitions, marker readability on the affected device, dark mode, large text, RTL, and broader system-configuration behavior. Historical framework captures and the retired lab results remain available; they are not claims about the current default.
