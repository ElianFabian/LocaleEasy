# LocaleEasy verification report

This report records verification runs from 2026-11-05 through 2026-11-25.

## Executed checks

### WebView resource repair and page state

`WebViewLocaleTest.repairsAnActualApplicationResourceMismatch` deliberately sets the application resources to Spanish while LocaleEasy's preference and effective locales are French. It verifies the Spanish resource lookup before calling `fixWebViewLocale()`, then checks that the application resources return French again and the preference is preserved.

`WebViewLocaleTest.observesLoadedWebViewAcrossLocalRecreation` opens the sample's XML WebView dialog in a fresh process. It reads the application and activity resource languages before and after the dialog opens, waits for the local page to load, navigates to a second URL, enters text, and scrolls. It calls the native repair with the page still open and asserts that the page state and history are intact. It then changes the app language to Spanish and checks the native strings and the replacement WebView's loaded HTML. The test reports the URL, form value, scroll position and history of the replacement.

Before enabling automatic repair in the sample, first WebView initialization switched the application resources from French to the device's English (`en-US`). The wrapped activity continued to resolve French strings. The helper restored French in the application context while preserving the loaded URL, history, form text, and scroll position. Changing the app language to Spanish then recreated the activity, translated the native controls, and loaded the sample's initial HTML in a new WebView. That view started with an empty form, no back history and a scroll position of zero.

Both tests passed before enabling automatic repair on the three configurations below. The provider on API 24 was identified from the framework's `WebViewFactory` log.

| Android API | WebView provider |
| --- | --- |
| 24 | Chrome `51.0.2704.90` |
| 32 | Google WebView `120.0.6099.193` |
| 35 | Google WebView `124.0.6367.219` |

With `instrumentWebViewLoads` enabled, opening the XML dialog in a fresh process kept the application resources in French on API 24 and API 35. The Compose fixture deliberately sets the application resources to English after constructing a WebView subclass. Its tests verify that `loadUrl()` with headers, `loadData()`, and `loadDataWithBaseURL()` restore French and still load the requested page. A separate consumer compiled with either plugin option alone. Inspection of its bytecode confirmed that the WebView-only build inserts the repair before real WebView calls and leaves an unrelated class's `loadUrl()` and the activity attachment code as written.

To isolate first initialization after installing the sample and its test APK:

```powershell
adb shell am force-stop com.elianfabian.localeeasy
adb shell am instrument -w -e class 'com.elianfabian.localeeasy.WebViewLocaleTest#observesLoadedWebViewAcrossLocalRecreation' com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
```

### Context composition and Android picker

On 2026-11-25, all eight library fixture tests passed on API 21 and again on API 35. They exercise a French context retained across a return to Follow system, a lookup in an explicit language, and preservation of a host's night mode and text scale. Eight focused sample tests passed on API 35. They exercise those overrides through the generated activity integration and check that a context without an explicit night override follows a real night-mode change. The manual activity fixture also verifies translated XML and restored input after local recreation.

On API 35, the Android picker tests cover setting a populated list, configuring zero languages, removing an override to restore the APK declaration and importing an override written through Android when an activity resumes. The test for zero languages also starts with French selected in Android and observes Android clear it.

The release App Bundle was converted with bundletool and installed twice on an API 35 emulator, using APK sets extracted for `en-US` and `fr-FR`. The sample launched in both installations. The English set installed `split_config.en.apk` and the French set installed `split_config.fr.apk`. AAPT2 found the default English string in the base APK and its French translation in the French split. Both APK sets come from the same declared catalog, which demonstrates why the catalog alone cannot establish that a translation has been installed.

### Optional activity instrumentation

Both sample builds passed with Gradle's configuration cache enabled. On an Android 15 emulator, the automatic build localized and recreated a plain activity with no override and an AppCompat activity with an inherited final `attachBaseContext()`. The existing manual activity test passed on both builds and restored user input after recreation. A separate Android app with no declared default language or catalog also compiled with instrumentation enabled. Its transformed class contained the generated `attachBaseContext()` call.

The sample now enables instrumentation by default. To reproduce the automatic tests, install the sample and its test APK and run the targeted test class:

```powershell
./gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.elianfabian.localeeasy.AutomaticActivityLocalizationTest com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
```

### Sample language panel

The sample now uses the plugin for its main and XML activities. On Android 15, instrumentation verified that the panel shows the sample catalog and that its Android button sends the app-language settings intent. Android Settings displayed the five declared languages. The Compose navigation and locale recreation tests passed, along with the controls opened from the XML activity, dialog and WebView. One repeated Espresso back-navigation run timed out waiting for window focus. On Android 12L, the panel showed the device and catalog languages and omitted the Android settings button. The plain and AppCompat activity instrumentation tests passed on both versions.

#### Back after a Compose locale change

The Android 15 recording reproduced the Espresso timeout after switching to French. The activity instance changed and its window decor was retained. At the point immediately before `Espresso.pressBack()`, the activity and the restored sheet were visible and neither reported window focus. About 11.4 seconds later, Espresso reported that it was waiting for the base activity window. The sheet then reported focus while the activity still reported none. The video shows the French sheet open through that wait.

Android accepted a separately injected Back key. About 0.2 seconds after injection, the sheet had disappeared from the Compose hierarchy, the same activity had regained focus, and the video showed the dismissal animation. A separate run using Android Back passed with French, Italian, and Spanish. These observations explain the reproduced test failure. They do not establish why focus was temporarily absent in every run.

The opt-in diagnostic logs activity and decor identities, window types and focus, and key delivery under `LocaleEasyBackTrace`. On a failure it captures the screen, tries system Back, and rethrows the original Espresso failure. The comparison mode sends system Back throughout. Both modes operate in the test APK.

```powershell
adb shell am instrument -w -e traceBack true -e class 'com.elianfabian.localeeasy.ComposeLocaleTest#languageControlsUseLocalRecreationOnTheSameScreen' com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e traceBack true -e backInput system -e class 'com.elianfabian.localeeasy.ComposeLocaleTest#languageControlsUseLocalRecreationOnTheSameScreen' com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s LocaleEasyBackTrace
```

The test writes PNGs under `files/compose-back-trace/` in the sample app. Use `adb shell run-as com.elianfabian.localeeasy ls files/compose-back-trace` to list the captures.

### System locales with an Android per-app language

`SystemLocaleSourcesTest` runs on API 33 and newer. It temporarily selects an Android per-app language for the sample and waits for the process to receive that configuration. It prints the language tags from `Resources.getSystem().configuration`, `LocaleManager.getSystemLocales()`, and LocaleEasy's `systemLocaleList`. The test restores the sample's previous Android language setting in `finally`. It does not change the device language.

The test passed on an Android 15 emulator (API 35). With the device set to `en-US` and the Android app language set to `fr`, the process reported:

| Source | Before | With the Android app language |
| --- | --- | --- |
| `Resources.getSystem().configuration.locales` | `en-US` | `fr,en-US` |
| `LocaleManager.getSystemLocales()` | `en-US` | `en-US` |
| LocaleEasy `systemLocaleList` | — | `en-US` |

The Android 15 tests also verified that `selectedAndroidAppLocale` reports an Android selection and its reset while LocaleEasy retains its own preference and effective locales. The Android 5 fixture checks that `selectedAndroidAppLocale` is null, `androidSupportedLocaleList` reports `Unsupported` and the missing LocaleEasy catalog has no default locale.

Run the test from Android Studio or install the sample and its test APK on the same device and run:

```powershell
./gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -r -e class com.elianfabian.localeeasy.SystemLocaleSourcesTest com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
```

### Legacy locale aliases on Android 5

The permanent regression tests now verify that the old and modern Hebrew, Indonesian, and Yiddish tags select equal preferences. They also load Hebrew, Serbian Latin, and Latin American Spanish fixture strings through `localizedContext()` on API 21. A Gradle unit test checks that the corresponding source directory names produce canonical, deduplicated catalog entries. All six API 21 instrumentation tests and the Gradle and preference unit tests passed.

An API 21 emulator probe passed old and modern Hebrew, Indonesian, and Yiddish codes through LocaleEasy. Both spellings of each language were accepted. Android 5 reported and serialized the legacy codes `iw`, `in`, and `ji`. The probe also accepted `fil-PH`, `tl-PH`, `sr-Latn-RS`, `zh-Hant-TW`, and `es-419`. It checked acceptance and effective locale state.

The legacy constructor forms `Locale("no", "NO", "NY")`, `Locale("ja", "JP", "JP")`, and `Locale("th", "TH", "TH")` failed LocaleEasy's round-trip validation on API 21. Their modern equivalents `nn-NO`, `ja-JP-u-ca-japanese`, and `th-TH-u-nu-thai` passed. Lingver's legacy-code mapping lives in its string-returning `getLanguage()` method. A similar mapping in LocaleEasy would be relevant to an API that promises a canonical language string across Android versions. [Lingver source](https://raw.githubusercontent.com/YarikSOffice/lingver/master/library/src/main/java/com/yariksoffice/lingver/Lingver.kt) · [Android Locale reference](https://developer.android.com/reference/java/util/Locale#toLanguageTag())

Localazy's tables are a reference for test cases and resource directory syntax. The tables cover API 10–29. With SDK build tools 36.0.0, AAPT2 rejected the listed `values-ji-r001` example and accepted `values-b+yi+001`. It also accepted the old and modern Hebrew, Indonesian, and Yiddish folder names tested. Check the actual resource compiler and minimum supported device when adding a qualifier. [Localazy Android locale tables](https://github.com/localazy/android-locales) · [Android resource qualifiers](https://developer.android.com/guide/topics/resources/providing-resources)

After removal of the public framework recreation switch, the sample's API 36 lifecycle and UI suite covered 24 cases with one opt-in skip. Two UI cases lost window focus during an emulator System UI error and passed on rerun. Separate process pairs passed for migration of a marked framework experiment and preservation of an independent platform app language. The unit tests, APK builds, and lint passed.

### Runtime discovery with a default language only

An API 24 probe appended the declared English default to the device-language candidates in a scoped Android configuration. It read the resulting locale order and both an app string and an AppCompat string from the same resource context. Inspection of the packaged APK confirmed that the app string has French, Spanish, Italian, and Portuguese versions. The German version of the AppCompat string is packaged alongside it.

| Candidates passed to Android | First locale after resource matching | Sample text |
| --- | --- | --- |
| Faroese, English | English | English |
| Faroese, French, English | French | French |
| German, English | German | English |
| German, French, English | German | English |

The app and AppCompat strings had the same asset cookie. AppCompat's German resources were enough to make the configuration retain German ahead of French. Reading the resulting configuration therefore cannot implement an app-only language catalog from the default locale alone. LocaleEasy kept its explicit catalog for this behavior. [Android resource matching source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/res/ResourcesImpl.java)

### Catalog and lifecycle checks

The generated fallback catalog was tested on API 24 with German as the only system language. The sample rendered English in follow-system mode, and the library-only fixture kept German with an empty catalog. Its required default getter threw while its nullable getter returned null. The fixtures also checked script-aware matching, ordered system matches, an explicit override, and a saved French initial locale alongside an English fallback across process restarts. The sample suite ran 29 cases with five API-dependent skips and zero failures. Adding French after German in Android Settings changed the running sample from English to French in the same process. Unit tests, lint, and debug and release catalog generation passed.

The companion Gradle plugin passed qualifier parsing tests and plugin validation. Both sample variants generated `en, es, fr, it, pt`. The sample's picker and settings suite passed on API 24 with one API-dependent skip across eight cases. A separate app resolved the plugin from a local Maven repository. Adding and removing a Portuguese resource changed only the corresponding flavor's catalog, including with the configuration cache enabled. The app's existing custom BuildConfig field was also preserved.

The 2026-11-23 initial-language change passed all 11 unit tests, both modules' lint, and the sample and test APK builds. On API 24 and API 36, separate process runs verified that `setInitialLocale()` applies before the first activity inflates, preserves a subsequent user choice, and respects an explicit follow-system choice even when the app already followed the system. The sample exercised automatic startup with its redundant `Application` initializer removed.

The API 24 lifecycle, Compose and settings suites passed with five API-dependent skips across 27 cases. On API 36, separate seed and verify processes also passed for a marked legacy framework override and for stored duplicate locales.

The 2026-11-22 reliability update verified the library build, sample build, instrumentation APKs and lint for both modules. All 11 preference unit tests passed. A minimal Android app assembled while reading the public flows with only the LocaleEasy project dependency.

| Environment | Result |
| --- | --- |
| API 21 library-only fixture | Both tests passed, covering localized lookup and plain-activity recreation. |
| API 24 isolated sample | 27 cases, five API-dependent skips, zero failures. Separate seed and verify processes repaired stored `fr,fr,fr-CA` and rendered French on the first launch. |
| API 36 isolated sample | 27 cases, one API-dependent skip, zero failures on rerun. A retained French context received the new night qualifier and kept its translation. The WebView initialization and repeat-switch tests ran with Google WebView 151.0.7922.199. |

Separate API 36 process pairs covered both platform ownership cases. A marked legacy experiment imported its French platform choice over stale Spanish local storage and cleared the platform override. An independent Italian platform choice survived startup with local French and also survived switching the wrapped activity to follow-system.

The API 36 local-recreation screenshot probe observed a replacement activity and root, preserved decor and French XML. The 13 sampled frames included no black app-content frame. The first and last frames had clear separation between the toolbar and the status bar. Screenshot sampling can miss short transitions.

The API 36 rerun followed a System UI ANR that stole window focus from the initial run. The retained-context regression waits for configuration delivery to both the activity and the wrapper. The repeat-switch test asserts that the outgoing activity keeps its translation while the next activity inflates in the selected language. It exercises the repair helper and does not reproduce the original provider's configuration mutation. System font-scale propagation, RTL, multi-window and physical-device transition appearance remain open.

Current manual-default/color-marker update, 2026-11-06: local recreation now starts enabled on every API/process; the sample framework selector is removed. API-36 instrumentation: 20 discovered, 17 passed, 3 intentional skips. API-32 instrumentation: 20 discovered, 14 passed, 6 intentional skips. The normal modern-Android tests now exercise local recreation for stopped activities, identical-language mode changes, native back stacks/titles, dialogs, and Compose navigation. Framework investigations explicitly opt in for that process and clean up to manual mode.

Separate seed/verify processes passed on API 36 for four cases: saved manual French, an obsolete persisted framework switch with French, an OS-only framework selection without LocaleEasy storage, and a French framework preference newer than an old Spanish library snapshot. Verification preserved the authoritative French selection, disabled framework recreation, removed the obsolete switch, and left framework app locales empty. Preference unit tests: 9 passed. Library lint: 0 errors/2 warnings; sample lint: 0 errors/20 warnings. The initial device-test install hit the temporary API-36 session's storage reserve; lowering that session's reserve allowed the suite to run.

Green localizable text and gray English-only text were reviewed in the sample's light and dark palettes. Markers describe translation origin, including English/fallback resource values, rather than asserting every string currently changes language. Recorded contrast ratios for the chosen green text/button surface were 6.37 (light) and 9.29 (dark); gray text/window surface ratios were 7.37 and 9.65. Further physical-device, RTL, and large-text checks remain open. See [current sample behavior](recreation-comparison.md).

Framework-flash investigation on 2026-11-06: synchronized SurfaceFlinger traces confirm the old app surface disappears before the replacement appears. Animation disabling does not remove this gap. A debug-only framework-preference/local-relaunch fixture retained its decor and had no observed app-surface gaps or black screenshots in three API-36 captures. API-33 sampling likewise showed no black frame. Full suites on API 33 and 36 each discovered 21 tests: 15 passed, 6 intentional skips. A strengthened focused test subsequently passed on both, including externally selected framework locales, saved-state restoration, and follow-system reset. Final sample lint passed with 0 errors and 20 warnings. See [the investigation and remaining integration checks](framework-window-handoff-investigation.md).

Window/UI follow-up on 2026-11-06: the toolbar overlap was reproduced (`y=0`, status inset 63 px) and fixed with explicit XML insets. Updated suites on API 32 and 36 discovered 20 tests each: 14 passed, 6 intentionally skipped, including the opt-in screen-capture diagnostic. A strengthened focused check also passed on API 36, asserting translated XML and both title values after each path. Sample lint passed with 0 errors and 19 warnings. The final home screen and captured native layouts were visually inspected. Frame sampling reproduced the framework black flash, which remains open; see [evidence and limits](recreation-window-findings.md).

Current sample selector and temporary backend flag, verified 2026-11-06:

| Check | Environment | Result |
| --- | --- | --- |
| Complete sample instrumentation suite | API 36 | 18 discovered: 13 passed, 5 intentional skips |
| Complete sample instrumentation suite | API 32 | 18 discovered: 13 passed, 5 intentional skips |
| Separate-process persistence, manual backend | API 36 | Seed and verify passed; flag, preference, and French XML restored |
| Separate-process persistence, framework backend | API 36 | Seed and verify passed; flag, preference, and French XML restored |
| Preference unit tests | JBR 21 | 9 passed |
| Final sample build and lint | Debug, JBR 21 | APK built; library lint 0 errors/2 warnings; sample lint 0 errors/25 warnings |
| Sample selector layout | API 36 | Visually inspected within the original home screen |

The new tests drive manual → framework → manual through the actual Compose selector and language buttons, asserting replacement of the activity and Compose root within the same task. Separate tests cover saved Compose navigation and XML input in manual mode on API 33+, an empty framework override in that mode, and disabled/rejected framework selection on API 32. See [usage and manual checks](recreation-comparison.md).

Earlier checks and retired lab history:

| Check | Environment | Result |
| --- | --- | --- |
| Preference model / persistence unit tests | JBR 21, Gradle 9.4.1 | 9 passed |
| Sample lifecycle instrumentation | Phone_API_32, Android 12L | 13 discovered: 11 passed, 2 intentionally skipped |
| Sample lifecycle instrumentation | Medium_Phone_API_36.0, Android 16 | 13 discovered: 10 passed, 3 intentionally skipped |
| Library-only minimum-SDK fixture | Phone_API_21, Android 5.0.2 | 2 passed, including startup and plain-Activity recreation |
| Separate-process persistence | API 32 | Seed and verification passed in different app processes |
| Separate-process persistence | API 36 | Seed and verification passed in different app processes |
| Visual inspection after restart | API 32 and API 36 | French Compose home screen displayed correctly |
| Library lint | Debug | Passed: 0 errors, 2 warnings |
| Sample lint | Debug | Passed: 0 errors, 25 warnings |
| Sample / Android test APK builds | Debug | Passed |
| Interactive lab UI | API 36 | 3 discovered: 2 passed, 1 API-specific skip; repeated manual → framework → manual trials use different trial PIDs and preserve edited input |
| Interactive lab UI | API 32 | 3 discovered: 2 passed, 1 API-specific skip; framework disabled, manual and real rotation trials preserve edited input |
| Lab Python analysis tools | Python unittest | 13 passed |
| Lab CLI regression after adding UI | API 36 | Manual/framework locale trials both passed with the final APK |
| Lab build and lint | Debug, JBR 21 | App/test APKs built; lint: 0 errors, 20 warnings |

The lab entries above describe the retired harness. Its Android module has since been removed; current interactive testing uses the [original sample and its single method selector](recreation-comparison.md). Historical profiling results remain archived. Behavioral tests do not assess perceived smoothness or visible blank frames; those still require watching transitions on the affected device.

The minimum-SDK fixture is in the library's debug variant, independently of the sample's minSdk 23. Removing unused Material, AppCompat, and Navigation dependencies from the library removed an unintended API-23 dependency floor. AppCompat remains a sample dependency and is tested there.

Sample instrumentation covers:

- Repeated installation preserves instance identity.
- Worker-thread mutations fail before changing preference.
- Empty selections leave preference and persisted storage unchanged.
- Plain Activity recreation replaces the activity and XML view, applies French, and restores user input.
- An additional ActivityScenario recreation retains the selected translation.
- Identical selections avoid another recreation; rapid requests settle on the final language.
- Legacy stopped activities defer refresh until started again.
- Legacy follow-system → app-locale mode changes rebuild the context even when languages match.
- AppCompat two-level back navigation preserves translations and runtime titles.
- A DialogFragment is restored once after its host's locale recreation.
- API-33+ external LocaleManager selection/reset updates resources and flows, including selecting the same languages as the system.
- WebView initialization followed by native-resource repair preserves the selected preference and native translation.
- Compose navigation remains on its selected screen with translated text after locale recreation.

The API-32 skips are the API-33+ framework test and the opt-in separate-process test. The API-36 skips are two legacy-only tests and the separate-process test. The separate-process test was subsequently executed explicitly in seed and verify phases on each API, not counted as a successful default-suite test.

## Recreation evidence

The debug `LocaleFixtureActivity` logs activity IDs, view identities, saved-state presence, translated text at creation, and `isChangingConfigurations` at destruction. The API-32 trace included:

```text
create instance=9 root=124841310 restored=false text=Localized String: Green
destroy instance=9 changing=true
create instance=10 root=2006063 restored=true text=Chaîne Localisée: Vert
```

Separate-process persistence on API 32 recorded seed process 7426 and verification process 7559, both creating French content before a second selection action. API 36 also passed both explicit phases.

This establishes that ordinary recreation can replace views and restore state with the corrected integration on tested devices. It does not prove the cause of the author's earlier device-specific failure, or that every device/provider behaves identically. That reproduction remains open.

Generated reports, screenshots, emulator logs, and traces are under `build/qa/` and module `build/` directories and are intentionally not committed. Screenshot review covered the French home screen, not every dialog or transition.

## Reproducing the checks

### Build recovery after moving the checkout

A reported `:app:mergeLibDexDebug` failure referenced a class under the old `Documents\@GitHub\LocaleEasy` path while the transform root was `Documents\GitHub\LocaleEasy`. This indicates stale incremental dexing metadata containing the previous absolute path. No library source change was needed. The initial clean also encountered a locked lint JAR; stopping the Gradle daemon released it.

Recovery, executed successfully on 2026-11-06:

```powershell
./gradlew.bat --stop
./gradlew.bat clean :app:assembleDebug --offline --no-build-cache --no-configuration-cache
```

The clean build passed. A subsequent Android Studio-style build also passed, including `bundleLibRuntimeToDirDebug` and `mergeLibDexDebug`, with configuration caching enabled and the API-24 dexing parameters from the original error:

```powershell
./gradlew.bat :app:assembleDebug --offline '-Pandroid.injected.invoked.from.ide=true' '-Pandroid.injected.build.api=24'
```

Quote dotted `-P` arguments in PowerShell. Continue using the current checkout path in Android Studio; the commands above regenerate build outputs rather than changing the locale implementation.

### Test commands

Use a compatible JDK (JBR 21 was used). Run from the repository root on Windows:

```powershell
./gradlew.bat :locale-easy:testDebugUnitTest :locale-easy:lintDebug :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-configuration-cache

# Set a specific running emulator/device before each instrumentation run.
$env:ANDROID_SERIAL = 'emulator-5556'
./gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache

# For API 21/22, use the independent library fixture.
./gradlew.bat :locale-easy:connectedDebugAndroidTest --no-configuration-cache
```

For the persistence test, install both sample and test APKs, then run the phases separately. Gradle's connected task may uninstall its APKs at the end, so install them again before these commands:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.elianfabian.localeeasy.PersistedLocaleTest -e localePhase seed com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop com.elianfabian.localeeasy
adb shell am instrument -w -e class com.elianfabian.localeeasy.PersistedLocaleTest -e localePhase verify com.elianfabian.localeeasy.test/androidx.test.runner.AndroidJUnitRunner
```

Use `adb -s DEVICE` when multiple devices are connected. Each phase must report `OK (1 test)`. This intentionally leaves French selected in the disposable test app.

Tests used headless emulators with `-read-only -no-snapshot`; saved AVD state was not overwritten. The API-36 AVD hit a low-storage installation reserve after its first suite; the temporary session's storage threshold was lowered to install the persistence fixture. No saved AVD setting was changed. Initial offline dependency-resolution failures were resolved by downloading missing build/test dependencies.

## Remaining verification

A follow-up [isolated profiling lab](../recreation-lab/README.md) now compares real rotation, local recreation, framework locale changes, LocaleEasy, and view-only rebuilding. Its [findings](recreation-lab-findings.md) record actual method paths, window identities, memory/class inventories, and Perfetto captures. That controlled workload supplements these sample tests; it does not close the original-device reproduction or the sample's full system-configuration matrix.

The broader acceptance checkboxes in [the reliability checklist](locale-reliability-checklist.md) remain open where appropriate:

1. Reproduce the originally reported failure on its actual device/API with the creation trace enabled.
2. Change actual device system languages in follow-system and app-locale modes, foreground and background; rotate and toggle dark mode. ActivityScenario recreation is not a substitute for all real configuration events.
3. Exercise API 22–31 and 33–35 boundaries as needed, especially an API-23 sample startup smoke test and API-29 callbacks. API 21, 32, and 36 are the executed coverage, not the whole supported range.
4. Test multiple tasks/windows, nested/navigation fragments, dialogs during saved-state transitions, and rapid background/foreground transitions.
5. Verify RTL layout direction and unsupported-language fallback with suitable translated resources. The sample currently has English, Spanish, French, Portuguese, and Italian only.
6. Verify language selection through the Android Settings UI. Instrumentation covers direct external LocaleManager calls and fresh-process restoration.
7. Verify any host-specific requirement to restore WebView state across activity recreation. The sample's local-page results and provider versions are recorded above.
8. Validate language availability in a production app bundle. Library lint still warns that dynamic legacy locale changes require translations to be bundled or downloaded; the library cannot choose a consumer app's delivery policy.

Other lint warnings include SDK/dependency updates and sample UI/style findings. They were not hidden with a baseline and are not reported as fixed by this work.
