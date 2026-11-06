# Compare recreation in the sample

Run the existing **app** module. There is no separate lab app or trial navigation.

1. On the home screen, use **Recreation method** to choose **Manual — Activity.recreate()** or **Framework — app language change**.
2. Tap **Open State & Controls** to open the existing settings sheet and choose a different language. The same activity is recreated; the settings sheet and current Compose screen are restored.
3. Repeat with another language, then switch methods and repeat. You can also choose a method on the home screen before opening the Compose, XML Activity, Fragment, or WebView samples. The choice applies throughout LocaleEasy.

Switching methods can itself rebuild an activity to remove/install its context override. Wait for that change to settle, then compare the subsequent **language-button** transitions. Selecting the already active locale is intentionally a no-op. Both methods use the actual sample content and normal navigation; no fresh trial process is launched.

Framework mode is available on API 33+. Below that, the selector disables it and the library defaults to manual recreation. The sample supports API 23+, while the library still supports API 21+.

## Temporary library flag

```kotlin
val localeEasy = LocaleEasy.getInstance()
localeEasy.useFrameworkLocaleRecreation = false // Localized contexts + Activity.recreate()
localeEasy.setLocale(Locale.FRENCH)

localeEasy.useFrameworkLocaleRecreation = true // API 33+: LocaleManager owns the change
localeEasy.setLocale(Locale.ITALIAN)
```

Set the flag on the main thread. It defaults to framework mode on API 33+ and manual mode below it, and is persisted with the library preferences so a process restart keeps the chosen test path. Enabling framework mode below API 33 throws before modifying the flag.

Manual mode clears this package's framework app-language override and uses the saved LocaleEasy preference instead. Android Settings does not control LocaleEasy's selection in manual mode. Returning to framework mode applies the current LocaleEasy selection through LocaleManager. Backend changes may deliver additional framework configuration events; they are setup operations, not a performance comparison. Keep calling `localizedContext()` from `attachBaseContext()` on every API when testing manual mode, including API 33+.

This is an investigation flag, not a recommendation to replace the normal API-specific backend in production. Framework app languages persist and integrate with Android Settings; the platform documents their configuration changes in [LocaleManager](https://developer.android.com/reference/android/app/LocaleManager).

## Verification

Automated checks cover actual replacement of the sample activity and Compose root within the same task, selector-driven language changes in both modes, preserved Compose navigation, XML input restoration, an empty framework override in manual mode, and rejection/disabled UI below API 33. Run the sample instrumentation suite on the intended device:

```powershell
$env:ANDROID_SERIAL = 'DEVICE_SERIAL'
./gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

**Manual verification remains required** for flicker, visible blanking, transitions, and perceived speed. Compare the same sample screen and starting language on the affected device; verify text, input, back navigation, and dialogs after the change. Timing findings from the retired isolated workload do not establish performance of this sample.

The old lab Android module has been removed. Its compact results and analysis scripts remain under `recreation-lab/` as historical investigation artifacts; [archived instructions](../recreation-lab/README.md) explain how to recover its original source if necessary.
