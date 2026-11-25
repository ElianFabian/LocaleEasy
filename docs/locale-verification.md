# LocaleEasy verification report

Date: 2026-11-05; sample recreation selector verification updated 2026-11-06. This report separates implementation verification from wider compatibility claims.

## Executed checks

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
6. Verify Android Settings UI selection and one-time migration from an installed legacy version; direct external LocaleManager calls and fresh-process restoration are covered, an actual OS/app upgrade is not.
7. Reproduce the old WebView issue with the affected provider version, and separately check web navigation/content state. The current test checks native locale preservation, not remote website translation.
8. Validate language availability in a production app bundle. Library lint still warns that dynamic legacy locale changes require translations to be bundled or downloaded; the library cannot choose a consumer app's delivery policy.

Other lint warnings include SDK/dependency updates and sample UI/style findings. They were not hidden with a baseline and are not reported as fixed by this work.
