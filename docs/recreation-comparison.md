# LocaleEasy sample: local recreation and translation markers

The normal sample now uses local activity recreation on every supported API. The framework-method selector has been removed. Run the **app** module, tap **Open State & Controls**, and choose a language. The current activity and its views rebuild through the normal lifecycle; saved Compose navigation and the settings sheet are restored.

The home screen reports **Local activity recreation**, the effective locales, and the available sample screens. The library supports API 21+; the sample supports API 23+. Call `localizedContext()` from `attachBaseContext()` on every Android version, including API 33+.

## Text colors

- **Green — localizable text:** strings loaded from translatable Android resources, including screen titles, button labels, formatted dates, and locale-state descriptions. A string remains green when the current language is English or falls back to English; the color describes its source rather than proving a translation exists.
- **Muted gray — English-only text:** fixed descriptions, diagnostic labels, hardcoded markers, language-choice names, the Compose Back label, and the local HTML fixture.

The legend follows the same rule: its green label is localizable, while its gray label intentionally stays English. Native XML, Compose, dialogs, and WebView controls use the same markers, with light/dark variants. Localizable buttons use a pale/dark green surface so their green text remains readable. The HTML itself remains English and is not translated by Android locale changes.

## Why automatic framework recreation is not the normal backend

In the tested API-36 configuration, automatic LocaleManager recreation removed the old app surface before the replacement was visible. Local recreation preserved the window. Code comments link to [the surface-trace investigation](framework-window-handoff-investigation.md), which also shows a supported hybrid retaining framework preferences. That prototype requires an activity/manifest integration contract and wider verification; it is not enabled as the normal implementation.

The temporary `useFrameworkLocaleRecreation` API property was removed. Current captures exercise local recreation. The earlier framework recordings and comparisons remain available as historical evidence.

The sample exposes its language picker inside the app. Local mode retains saved LocaleEasy selections across process restarts and preserves independent platform overrides. Startup clears a platform override after importing it only when a stored marker indicates prior LocaleEasy framework ownership. [API integration guide](locale-preference-api.md)

## Activity recreation source review

The current default fits an app that owns its language setting. Each participating activity wraps its base context before inflation and builds its content in the normal creation lifecycle. LocaleEasy queues one refresh for a stale started activity and checks stopped activities when they start again. A second inflation in the outgoing activity duplicates work and leaves the host responsible for restoring bindings, fragment containers, and user input.

In the Android 16 SDK source, `Activity.recreate()` calls `ActivityThread.scheduleRelaunchActivity()`. For a started foreground activity with a window, the local request can carry the window forward. During destruction, `ActivityThread` calls `window.clearContentView()` on the preservation path. `DecorView.clearContentView()` removes the old content children and retains system-bar elements. The relaunch constructs another activity and calls its creation lifecycle. In the XML sample, that means another binding and root. API 21 uses an earlier relaunch implementation, so consumers should rely on recreated activity content and saved state, without depending on decor identity. The [window traces and framework source references](framework-window-handoff-investigation.md) document the separate system-server decision during automatic locale changes.

### Next checks, easiest first

1. **Distinguish activity state, content, and window on the affected screen.** Compare the old and new activity and inflated root by reference. In the replacement activity, compare the activity resource string, the inflater context's string, and the final label after state restoration. A translated resource with stale displayed text points to binding, cached text, or restoration. A stale resource at inflation points to context setup or resource fallback. Preserve typed user input in either case. The plain activity fixture already checks activity and label replacement and input restoration.
2. **Verify the configuration delta across qualifiers.** `localizedContext()` now overrides locales and the derived layout direction. It sets `fontScale = 0f` so older Android can inherit the base font scale. The Pixel experiment below verified the change for night mode. Check font-scale propagation separately. [Android context override contract](https://developer.android.com/reference/android/content/Context#createConfigurationContext(android.content.res.Configuration))
3. **Exercise an actual system configuration change.** The plain activity test calls `ActivityScenario.recreate()` after selecting French. It covers another explicit relaunch. Rotate the device or change night mode through the system and assert the new qualifier, translated content, saved input, and toolbar clearance. Check perceived continuity on the device that showed the original issue. The Pixel run below adds a system-delivered orientation change and physical-device surface measurements.
4. **Keep the sample's local picker as the language control.** The main manifest no longer advertises the Android Settings picker. The API 33+ callback prototype remains available for comparison.

## Pixel 8 Pro verification, 2026-11-22

Tested revision `e1784a3` on API 37, build `CP3A.261005.005`, using a separate application ID. The original sample stayed installed. Night mode changes were scoped to the test package. The existing instrumentation suite reported 20 cases, including three conditional skips, with zero failures.

Each row represents one light-theme transition to French in the XML sample. All three replaced the activity and inflated root and displayed the French label. The analyzer matched the Pixel's visible `VRI-` activity buffer layer. Times measure intervals between compositor snapshots.

| Relaunch path | Outer decor | Mostly black screenshots | Observed interval without an app layer |
| --- | --- | --- | --- |
| Local | Preserved | 0 of 12 | None observed |
| Automatic framework | Replaced | 1 of 12 | 66.8 ms |
| Framework callback with local relaunch | Preserved | 0 of 12 | None observed |

Two additional probes exercised the locale wrapper:

- **Framework rotation.** With French selected, the plain activity requested the opposite orientation. Android delivered the target orientation to a new activity, inflated a new French label, and restored typed input. This passed on the original and candidate builds.
- **Retained context.** Hold the result of `localizedContext(application)` with French selected and toggle `UiModeManager.setApplicationNightMode()`. The base application and replacement activity changed to light mode. The retained wrapper still returned `UI_MODE_NIGHT_YES`. An isolated build passed the same probe in both directions after supplying a locale-only override with `fontScale = 0f`. Its combined suite reported 22 cases including the same three skips, with zero failures. That override is now used in the library.

The existing toolbar-inset regression passed on the phone. A separate native-view check on the candidate confirmed the French label and English marker had the intended green and gray text colors in both themes. Perceived smoothness and readability remain visual judgments. Temporary device packages were removed after testing.

### System rotation versus an explicit recreation on the Pixel

An isolated plain activity inflated the same XML for each trial on API 37. The orientation request caused a real portrait-to-landscape system configuration change. The explicit control called `Activity.recreate()` with the phone in portrait. Each path ran twice, in the order explicit, orientation, orientation, explicit. The language service was unused in these tests.

| Observation | System orientation change | Explicit recreation |
| --- | --- | --- |
| Outgoing `isChangingConfigurations` | true | true |
| Outgoing `changingConfigurations` | `0x480` (orientation and screen size) | `0x0` |
| Activity, inflated content root, Java Window object | New instances | New instances |
| Decor view | New instance | Same instance |
| Task and typed input | Preserved | Preserved |

All four outgoing activities paused, stopped, saved state and were destroyed. All four replacements created with saved state, started, restored state and resumed. In the API 37 SDK source, `Activity.recreate()` queues a local relaunch. `handleRelaunchActivityLocally()` constructs an `ActivityRelaunchItem` using the recorded configuration, a zero change mask and the local window preservation decision. In the inspected AOSP server source, `ActivityRecord.ensureActivityConfiguration()` evaluates a real configuration diff and decides whether to relaunch and preserve the window. Its transaction supplies the merged configuration and change flags. Both routes reach the client's `handleRelaunchActivityInner()`, which destroys and launches the activity. Earlier [instrumented method traces](recreation-lab-findings.md) independently observed this convergence. Window preservation is an implementation decision.

#### Physical activity state and method traces

One further run per trigger used ART instrumented method tracing in the Pixel's app process. Both captures completed with no reported buffer overflow. The main-thread rotation trace reached `ConfigurationController.handleConfigurationChanged()` before `ActivityRelaunchItem.execute()`. The explicit trace went through `ActivityThread.handleRelaunchActivityLocally()` and executed its relaunch transaction locally. Both entered `handleRelaunchActivityInner()`, destroyed the outgoing activity and launched the replacement. In the explicit run `PhoneWindow.clearContentView()` appeared after destruction and before launch. Both runs called `PhoneWindow.setContentView()` from the new activity. The captures cover app execution; the system-server decision above is from source inspection. [Ordered entries, call stacks, trace hashes and activity observations](results/pixel-activity-relaunch-2026-11-22.json)

The same assertions passed for both triggers. The old activity was destroyed with `isFinishing == false`. A plain field started fresh in the replacement. Its saved-instance bundle contained the value written by the old activity. The object returned by `onRetainNonConfigurationInstance()` arrived by reference in the replacement's `onCreate()`. Read it there: the first probe initially checked the getter after resume, when Android had already cleared its reference. Immediately after inflation in `onCreate()` the editor was empty. After `super.onRestoreInstanceState()` it contained the old text, and its selection range was intact by resume. Restored text can therefore make a newly inflated editor look unchanged.

#### Three-way method, allocation, and frame comparison

The sample's debug runtime resolves AndroidX Core 1.17.0. LocaleEasy already calls `ActivityCompat.recreate()` in its refresh scheduler. On API 28+ this AndroidX version calls the platform method directly. On earlier versions it posts to the main thread and applies compatibility handling, including a relaunch workaround on API 26–27 and a platform fallback. The Pixel trace confirms the API 37 branch.

One fresh full ART method trace per trigger captured 189,211 events for direct recreation, 189,473 for AndroidX and 346,383 for rotation. The comparison decoded every recorded app-process thread's entries, exits and unwinds, preserved overload signatures, and compared call paths relative to each fixture lifecycle callback. The parser found no buffer overflow, unmatched exits or dropped frames while reconstructing the recorded stacks. The capture boundary cuts through active methods. Android framework Java calls executing in the app are included. Native internals, compositor execution and system-server method bodies are outside this trace.

| Observed work | Direct | AndroidX | Rotation |
| --- | ---: | ---: | ---: |
| Main-thread method entries during the trace | 92,238 | 92,241 | 139,807 |
| Entries inside fixture `onCreate()`, including descendants | 33,810 | 33,725 | 41,767 |
| `PhoneWindow.setContentView(int)` inside creation | 1 | 1 | 1 |
| `PhoneWindow.generateDecor(int)` inside creation | 0 | 0 | 1 |
| Entries inside fixture `onRestoreInstanceState()`, including descendants | 10,492 | 10,508 | 3,060 |

The explicit paths both cleared old content and reused decor. Rotation created decor. The 7,432-entry direct-versus-rotation difference during restoration fell under window hierarchy restoration, mainly descendants of text and selection restoration. Each path restored the same text and selection. Entry counts describe these three recordings and are sensitive to cache state and asynchronous work.

In a separate instrumentation invocation, three warm-ups preceded 36 untraced trials. All six order permutations ran twice. Each trial launched the same portrait fixture, seeded editor state, triggered a relaunch, and verified restored input and destination orientation. The first measurement ended in the replacement view's frame-commit callback. A second sample followed about one second later. No test requested garbage collection.

| Median from request to frame commit, 12 trials per path | Direct | AndroidX | Rotation |
| --- | ---: | ---: | ---: |
| Elapsed time (ms) | 23.5 | 24.2 | 68.3 |
| Main-thread CPU (ms) | 17.8 | 17.6 | 19.8 |
| App-process CPU (ms) | 27.0 | 27.0 | 29.5 |
| ART allocated bytes | 167,920 | 167,920 | 294,616 |

Across all 36 measured windows including the follow-up second, one GC occurred during an AndroidX trial before commit (30 ms total GC time), and one during a rotation trial after commit (22 ms). ART counted zero blocking GCs for all paths. Heap history spans trials in the same process. Collection timing alone cannot establish causation. The frame-commit callback measures submission and its delivery to the app; display presentation and perceived smoothness require separate measurements. [Method counts, trace hashes and per-trial deltas](results/pixel-three-way-relaunch-2026-11-22.json)

#### Avoiding the black flash for an app-specific night-mode change

The recording fixture kept the sample in portrait with a fixed light palette. While the settings sheet was open, tapping French updated the local preference and triggered an app-specific night-mode change. The automatic recreation control replaced the activity decor and recorded three black frames out of 44. In the isolated fix, the activity declared `android:configChanges="uiMode"` and requested `recreate()` in `onConfigurationChanged()` when the night qualifier changed. Its two captures had zero black frames out of 44 and 45. Both reused the activity decor, rebuilt the translated root and reopened the settings sheet in a new dialog window. LocaleEasy's regular language switch already uses local recreation. The `uiMode` handler was tested only in the recording fixture for this configuration change. [Android configuration change handling](https://developer.android.com/guide/topics/resources/runtime-changes)

#### Forced view creation and translation on the Pixel

A temporary wrapped AppCompat activity used the sample's XML label, an editable field, and a Compose title using `stringResource()`. The isolated build let the test choose the relaunch trigger and used local language selection throughout. Each case started in English, selected French, then Spanish. Assertions read the XML label and Compose semantics against independent expected strings. A real orientation change triggered system recreation in the rotation control. The other controls called `recreate()`. The factory used the historical `ViewCreatorComponent.createContentView()` signature and installed the returned view using `setContentView()`.

Each trigger ran in its own process with two preparations, once using normal local selection and once also reapplying native resources to tracked outgoing activities. The latter used the explicit resource-repair helper to approximate the historical update on the outgoing activity. Across these 16 isolated switches, all final XML and Compose translations passed.

| Trigger | New content roots built per switch | Typed text and selection after recreation |
| --- | ---: | --- |
| Rotation | 1 | Restored |
| Explicit recreation | 1 | Restored |
| Factory on outgoing activity, then explicit recreation | 2 | Cleared before Android saved the hierarchy |
| Explicit recreation, then factory after state restoration | 2 | Restored, then cleared by the second installation |

Reapplying the outgoing resources changed an intermediate result. With this preparation, the outgoing inflater resolved French while its existing XML label still displayed English. Forcing inflation then produced a French label immediately. Without reapplication, the outgoing inflater still resolved English and the forced root also showed English. In both isolated cases, the new activity rendered French through ordinary inflation.

Process reuse exposed a separate wrong-language result. Repeating English → French → Spanish four times in one process with plain recreation gave 8/8 correct French/Spanish transitions under normal local selection. Adding outgoing resource reapplication gave 5/8. On rounds 2–4, the English baseline showed French, then the French selection showed Spanish in both XML and Compose. The effective language was French, and the replacement inflater already returned Spanish in `onCreate()`. The factory was absent in this control. A mixed shared-process run also reproduced wrong text with both factory timings. This identifies an interaction with repeated in-place resource updates in this fixture. It does not establish the internal cache involved or that the historical app experienced this exact failure. [Per-phase translation, state and root observations](results/pixel-view-factory-translation-2026-11-22.json)

### Changing language in the existing activity

The same unwrapped fixture provided a third control. Selecting French and then Spanish published the new preference without enrolling the fixture in LocaleEasy's recreation scheduler. Dispatching the new configuration to the existing XML hierarchy left its label in English and then French, respectively. Assigning the resource text from a context scoped to the selected language immediately produced the expected French and Spanish labels. Identity checks verified the same activity, root, label and editor across both switches. Typed input and the cursor position survived.

For an in-place integration, a screen can observe `effectiveLocales` and bind its localized properties using the new scoped resources. The integration must also let that screen opt out of the automatic relaunch. Known resource IDs, formatting arguments, and user-entered values give the screen enough information to update just the translated properties. Multiple bindings can share one lifecycle-aware subscription. This control exercised native text only. Layout variants, RTL, menus, fragment views, dialogs, and Compose need their own checks before making a general API promise. No public activity API identified in the review accepts an arbitrary locale configuration and change mask for a system-server relaunch. The Android [configuration change guide](https://developer.android.com/guide/topics/resources/runtime-changes) describes the host's responsibilities when retaining an activity.

## Verification

The Pixel 8 Pro had an older sample APK whose home **Open State & Controls** button left the screen unchanged. Updating the installed app with the current debug APK preserved its data and opened the settings sheet on tap. `SettingsControlsTest` also verifies that the sheet appears from the home, Compose, XML Activity, Fragment, and WebView screens.

Run the sample instrumentation suite on an explicitly selected disposable device:

```powershell
$env:ANDROID_SERIAL = 'DEVICE_SERIAL'
./gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

The normal UI tests change French → Italian → Spanish in place, assert activity/root replacement within the same task, and check that framework locales remain empty on API 33+. Lifecycle checks cover XML input, stopped activities, runtime titles, Compose navigation, dialogs, and identical/rapid requests using local recreation.

Run seed/verify persistence phases in separate processes. The `localeBackend=framework` seed reproduces the old persisted switch. `framework_only` and `framework_stale` test marked owners with missing or older local choices. Verification must preserve French, clear the stale marker and framework override, and return to local recreation. The separate `ownershipPhase` probe verifies that an independent platform override survives a restart while a locally wrapped activity uses the LocaleEasy preference.

Manual checks remain necessary for perceived transitions, marker readability on the affected device, dark mode, large text, RTL, and broader system-configuration behavior. Historical framework captures and the retired lab results remain available; they are not claims about the current default.
