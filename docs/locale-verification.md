# LocaleEasy verification report

Date: 2026-11-05; sample recreation selector verification updated 2026-11-06. This report separates implementation verification from wider compatibility claims.

## Executed checks

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
