# LocaleEasy: ordered reliability checklist

Reviewed: 2026-11-05. Scope: `locale-easy` library and `app` sample.

For current implementation order and acceptance tests, use the [2026-11-22 library roadmap](library-roadmap.md). New API 24 probes reproduced a startup crash with persisted duplicate locales and a nested setter that left preference and resources in different languages. The original review below tracks the history of the earlier fixes.

The numbered findings below describe the initial source review. Progress and executed verification are recorded here; an implemented fix is not automatically a completed device check.

## Progress

The public model is `LocalePreference.FollowSystem` / `AppLocales`. Implementation now includes automatic/idempotent installation, main-thread enforcement, explicit context participation on every API, queued local activity refresh, and a bounded native WebView-resource repair. LocaleEasy uses local recreation on API 21+. The library removed the fragment detach/attach and title-reset paths and the legacy `ViewCreatorComponent` interface.

See [the API guide](locale-preference-api.md) for integration and [the verification report](locale-verification.md) for actual test counts, traces, and remaining checks. Historical findings below are retained to explain each task; their status lines describe the current implementation. Broader acceptance checkboxes stay open where coverage remains incomplete.

The activity/view symptom is reported by the project author: manually rebuilding views has worked in previous attempts, while calling `recreate()` has not produced the expected UI update. Its exact cause has **not** been reproduced. Do not treat an unchanged translation as proof that Android retained the same view.

## How to use this plan

Work through the numbered items in order. Order reflects combined implementation and verification difficulty, not severity. The first items also remove blockers for later device tests. Steps 11–13 require the earlier state/context fixes to make their results meaningful.

Verification labels:

- **Automated sufficient:** the stated automated assertions can close the issue; no separate visual inspection is required.
- **Manual required:** run the supplied device/emulator scenario and inspect behavior. Lifecycle assertions should also be automated where practical, but do not replace the requested visual check yet.

For device checks, record API level, device/emulator, app build, initial system languages, selected app languages, reproduction steps, and outcome. Fixes are not verified merely because the project builds.

| Step | Issue | Fix effort | Verification effort | Manual verification |
| --- | --- | --- | --- | --- |
| 1 | Sample uses API-33 locale service without a guard | Low | Low | Not required with launch smoke tests |
| 2 | Locale serialization uses incompatible formats | Low | Low | Not required |
| 3 | Empty and invalid locale inputs lack a contract | Low | Low–medium | Not required |
| 4 | Installation and initialization ownership are unclear | Low–medium | Medium | Not required with integration tests |
| 5 | Wrapper validation rejects valid integration | Low–medium | Medium | Required |
| 6 | Initial locale flow can be stale | Medium | Medium | Required |
| 7 | Preference, effective locales, and process defaults are mixed | Medium | Medium | Required |
| 8 | Locale operations lack thread and duplicate-update rules | Medium | Medium | Not required with instrumentation |
| 9 | Configuration callbacks apply stale locales and extra refreshes | Medium | Medium–high | Required |
| 10 | Recreation is combined with fragment detach/attach | Medium | High | Required |
| 11 | Activity/view recreation failure needs isolation | Investigation-dependent | High | Required |
| 12 | Locale application strategy differs across Android versions | High | High | Required |
| 13 | Back-stack refresh and optional view rebuilding need a contract | High | High | Required |
| 14 | WebView workaround has an unverified scope | Medium–high | High | Required |

## 1. Make the sample launch on its supported Android versions

**Current status:** Implemented: removed unguarded framework diagnostics and API-24 logging; removed unused Material/AppCompat/Navigation dependencies from the library (Material required API 23). API-21 fixture verification is recorded in the report.

**Confirmed issue.** `LocaleEasyApplication.onCreate()` accesses `LOCALE_SERVICE` and `LocaleManager.systemLocales` without an API-33 guard. The sample declares minSdk 23. Library lifecycle logging also reads `configuration.locales`, an API-24 property, in callbacks that run on older devices.

**Locations:** `app/.../LocaleEasyApplication.kt`; lifecycle logging in `locale-easy/.../AndroidLocaleEasy.kt`.

Fix:

1. Guard framework `LocaleManager` access with API 33 or use a compatible fallback for diagnostic output.
2. Use `ConfigurationCompat.getLocales()` for configuration logging that executes below API 24.
3. Audit remaining executable API-specific calls, including diagnostic code. The library's supported floor is API 21, while the sample's is API 23.

Verification — **Automated sufficient:** launch the sample on API 23 and API 33+, open and close a sample activity, and assert no startup/lifecycle exception. Run Android lint and inspect remaining API compatibility findings. A small API-21/22 consumer fixture is needed to verify the library's lower floor; the existing sample cannot cover it.

- [ ] Sample launches on API 23 and 33+; library fixture launches on API 21/22.

## 2. Persist locale language tags consistently

**Current status:** Implemented and unit-tested: BCP 47 writes, strict reads, ordered region/script round trips, common underscore-tag migration, and malformed-storage fallback. Duplicate entries preserve caller order; Android resource locale lists may deduplicate them.

**Confirmed issue.** `setAppLocaleListInternal()` writes `localeList.joinToString(",")`, which invokes `Locale.toString()`. `getPersistedAppLocaleList()` reads with `Locale.forLanguageTag()`. Regional/script locales do not reliably round-trip through these different representations.

Fix:

1. Serialize each locale with `toLanguageTag()` and deserialize the same format.
2. Preserve list order. Decide how duplicate locales are normalized.
3. Handle existing underscore-based stored values explicitly: migrate known legacy representations or discard invalid data with a documented fallback. Do not silently assume all old strings are BCP 47 tags.

Verification — **Automated sufficient:** round-trip `en`, `en-US`, `pt-BR`, `zh-Hant-TW`, and an ordered list such as `[fr-CA, en-US]`. Check language, script, region, and order after reload. Include legacy stored values and malformed entries.

- [x] Valid selections survive serialization without losing region, script, or ordering.

## 3. Define empty-list and invalid-data behavior

**Current status:** Implemented and tested: AppLocales rejects empty lists/root locales before mutations; invalid stored overrides recover to FollowSystem. Instrumentation checks rejected input leaves preference storage unchanged.

**Confirmed issue.** Both resource/context helpers call `localeList.first()`. `setAppLocaleList(emptyList())` can change follow-system state and persistence before throwing. Missing persisted text is parsed as an empty language tag rather than being treated as absent data.

Fix:

1. Choose and document the empty-list contract. Recommended: an empty override means follow system; alternatively reject it before any mutation.
2. Validate/normalize the entire input before writing preferences or publishing state.
3. Define recovery for absent/corrupt preferences; resolve to a valid effective locale list.

Verification — **Automated sufficient:** exercise empty input, absent storage, blank entries, malformed tags, and duplicates. Assert either the documented fallback or a deliberate argument error. Rejected input must leave mode, stored selection, flows, and resource configuration unchanged.

- [x] No accidental `first()` failure or partial state update is possible through invalid input.

## 4. Make installation idempotent and choose an initialization owner

**Current status:** Implemented and device-tested: AndroidX Startup metadata is registered; repeated install calls retain one instance. The API guide documents opting out and manual initialization.

**Confirmed issues.** Every `LocaleEasy.install()` constructs a new instance and registers callbacks. Old instances remain registered. `LocaleEasyInitializer` exists, but the library manifest does not register it with AndroidX Startup; the sample currently installs manually.

Fix:

1. Make repeated installation return/reuse the installed instance, with a defined thread-safety policy.
2. Choose manual initialization or automatic AndroidX Startup initialization and document it. If automatic initialization is intended, register its metadata in the merged manifest and document opting out.
3. If replacing instances is intentionally supported, unregister the old callbacks and define what happens to existing flow subscribers.

Verification — **Automated sufficient:** call installation twice, assert stable instance identity, then verify one callback/update path per event. For automatic initialization, inspect the merged manifest and launch a consumer without a manual install call. For manual initialization, verify the documented pre-install error and successful startup after installation.

- [x] One active implementation owns callback registration; the documented initialization path works.

## 5. Repair context wrapping and its validation

**Current status:** Implemented: localized inflater wrapper below API 33; only explicitly wrapped activities are tracked. Package-prefix checks and false debug errors are removed. API 33+ uses framework configuration without requiring wrappers. Plain and AppCompat activities are tested; broader unrelated-activity/device coverage remains open.

**Confirmed issue.** `hasLocaleEasyWrapper()` searches for `LocaleEasyContextWrapper`, but no code constructs that wrapper. `localizedContext()` returns a configuration context directly. The debug check therefore rejects the prescribed integration on API 29+. `SampleActivity` does not wrap its base context, and the package-name prefix check is only a heuristic for identifying participating activities.

Fix:

1. Either return an identifiable wrapper around the localized configuration context or replace the marker check with an explicit registration/integration mechanism.
2. Ensure the returned context exposes localized resources and that inflation uses them.
3. Integrate every participating sample activity, preferably through its shared base where appropriate.
4. Do not require unrelated third-party activities to participate based on a class-name prefix. Document that `onActivityPreCreated` is unavailable below API 29 and is too late to replace `attachBaseContext` integration.

Verification — **Manual required:** on API 29+, launch MainActivity and both levels of SampleActivity in a debug build with an override selected. They must open without the false missing-wrapper error and show localized XML/Compose text. Add integration assertions for a correctly wrapped activity, an intentionally unintegrated fixture, and a nonparticipating activity. Repeat the localization check below API 29.

- [ ] Valid integration passes validation and resource inflation; unrelated activities are unaffected.

## 6. Initialize effective state before activity contexts consume it

**Current status:** Implemented and tested across two separate processes on API 32 and API 36: persisted French is present in the initial preference/effective flow and freshly created XML view. French Compose home screens were visually inspected after restart.

**Confirmed issue.** `_appLocaleList` is populated before `init` applies persisted locales and is not updated afterward. `localizedContext()` then reads potentially stale state.

Fix:

1. Read and validate saved preference/mode first.
2. Resolve initial effective locales before exposing the singleton to consumers.
3. Ensure the initial flow value and the configuration used by `localizedContext()` represent the same selection.

Verification — **Manual required:** set the system to English, select French in the app, terminate the process without clearing app data, and relaunch. Inspect the first screen, then open another activity. Both must start in French without a second locale action. Automate assertions on the initial flow, wrapped-context resources, and activity resources.

- [x] A persisted override is correct on the first frame and in the first flow value after process restart.

## 7. Separate preference from effective locale state

**Current status:** Implemented: explicit preference drives effective state, context creation no longer mutates process defaults, disabling follow-system freezes effective languages. Public flows are individually observable, not an atomic multi-flow snapshot. Full system-language-change coverage remains open.

**Confirmed design problem.** `getAppLocaleList()` reads mutable process defaults. Context creation changes those defaults as a side effect. Enabling follow-system also writes current system locales into the stored app-language key, overwriting the previous override. Separate flows can temporarily report incompatible mode/locale values.

Fix:

1. Model preference explicitly, for example `FollowSystem` or `Override(locales)`.
2. Define whether `appLocaleList` means the requested override or effective application resource locales; document fallback behavior.
3. Derive effective state from preference plus observed system configuration. Do not infer the user's preference from `Locale.getDefault()`.
4. Make localized-context creation avoid changing process-wide defaults. Centralize any deliberate process-default update.
5. Decide whether disabling follow-system restores a remembered override or freezes current effective locales. Preserve the prior override only if that is the chosen contract.
6. Prefer one coherent internal state snapshot; derive existing public flows from it where compatibility requires them.

Verification — **Manual required:** select French, enable follow-system on an English device, then disable follow-system. Verify the documented outcome in flow values and visible resources. Automated state-transition tests should cover both modes, system locale changes, fallback lists, and context creation without unexpected global side effects.

- [ ] Preference, effective locale list, and resources follow a documented, consistent model.

## 8. Define threading, ordering, and repeated-selection behavior

**Current status:** Implemented and device-tested: main-thread checks, identical-preference no-op, pending refresh coalescing, and latest-selection checks. Tests cover worker rejection, repeated/rapid updates, and legacy mode changes with identical effective languages.

**Confirmed gap.** Public setters can reach activity/fragment operations from any caller thread. `CopyOnWriteArrayList` protects iteration, not Android UI calls. The same-locale early return is commented out.

Fix:

1. Choose main-thread-only setters with clear enforcement, or serialize requests onto the main thread and document when state changes become observable.
2. Compare normalized preference/effective state before scheduling refreshes. A mode change can still matter when its locale list happens to match.
3. Define how rapid consecutive requests are coalesced and prevent duplicate recreation requests for the same pending update.

Verification — **Automated sufficient:** call from a worker thread and assert the documented rejection or main-thread dispatch. Reapply the same selection and assert no unnecessary recreation. Submit rapid French → Spanish → Italian changes; verify the final state/resources are Italian and no stale request wins.

- [x] UI work runs on the main thread and duplicate/stale updates cannot cause extra refresh cycles.

## 9. Correct configuration-change handling

**Current status:** Manual callbacks preserve custom resources without requesting duplicate system-event recreation on every API. The explicit API-33+ framework experiment reconciles external selections, including selecting system-equivalent languages; it is not the normal backend. Actual system-language, rotation, theme, and background-event matrix remains open.

**Confirmed issue.** `onConfigurationChanged()` applies persisted locales and refreshes activities before checking follow-system mode. It also schedules this work for non-locale configuration changes.

Fix:

1. Refresh observed system locales using a version-appropriate source; on API 33+, `LocaleManager.systemLocales` explicitly ignores app overrides.
2. Resolve effective locales from the selected mode before applying anything.
3. Only request additional locale refreshes when effective locales changed and the selected integration requires them.
4. Avoid a second library recreation alongside a recreation Android already owns. Preserve overrides across unrelated configuration changes without replaying the whole update pipeline.

Verification — **Manual required:** in follow-system mode, change the system language English → Spanish, foreground and background. In override mode, select French and repeat; the app must remain French while the system-locale flow changes. Rotate and toggle dark mode in both modes. Assert no stale-language flash, recreation loop, or extra library-triggered refresh for unchanged effective locales.

- [ ] System changes and unrelated configuration changes preserve the selected mode and coherent resources/state.

## 10. Stop rebuilding fragments while their activity is being recreated

**Current status:** Implemented: fragment transactions are removed from activity refresh. DialogFragment restoration is tested with one restored dialog on API 32 and API 36. Nested/navigation/state-save stress checks remain open.

**Confirmed issue.** The library requests activity recreation and immediately detaches/attaches fragments on that outgoing activity. It retries failed transactions allowing state loss.

Fix:

1. Remove fragment detach/attach from the activity-recreation path; let the activity/FragmentManager restore its hierarchy.
2. Do not treat every `IllegalStateException` as permission to discard saved state.
3. If a separate fragment-only refresh is retained, give it explicit ownership and lifecycle constraints. Do not run both refresh strategies for the same locale update.

Verification — **Manual required:** open SampleFragment as a dialog, change language from it, and check that it remains present once with translated content. Repeat while backgrounding/resuming and with nested fragments and a navigation back stack in a test fixture. Assert no duplicate dialogs, lost destinations, transaction exceptions, or state-loss fallback. Lifecycle instrumentation should distinguish expected recreation from extra detach/attach cycles.

- [ ] Fragment state survives locale updates without competing rebuilds or state-loss transactions.

## 11. Reproduce and isolate the activity/view recreation symptom

**Framework flash investigation:** [Surface-level evidence and prototype](framework-window-handoff-investigation.md) identify an interval with no visible app surface during automatic framework recreation. A debug fixture handling the delivered locale/layout configuration and requesting local recreation preserves the decor, replaces activity/views, and retains framework preference integration. It passed repeated language/external-selection/state/reset checks on API 33 and 36; production integration and the wider device/configuration matrix remain open.

**Window follow-up, 2026-11-06:** the sample's XML toolbar/status-bar overlap was reproduced and fixed with explicit system-bar insets. A separate framework-only black flash is now captured in the actual sample and remains unresolved; [window findings](recreation-window-findings.md) distinguish this visible evidence from activity/view identity checks. The updated sample UI exposes the existing comparison more clearly without changing the recreation paths.

**Current status:** Controlled recreation now passes: fixture traces show different activity/view identities, changingConfigurations=true, restored state, and French XML; Compose navigation/translation also survives. This does not identify the original device-specific failure; reproduce on that device before closing the task.

Follow-up: the separate [Recreation Lab](../recreation-lab/README.md) captures actual ART/framework call paths, heap class inventories, memory snapshots/timelines, and system traces. See [initial findings](recreation-lab-findings.md) for the executed comparison. The original-device acceptance checkbox remains open.

The [existing sample](recreation-comparison.md) now uses local recreation only in its normal UI; its framework selector has been removed. Language buttons recreate the current activity in place. The former public framework diagnostic switch has been removed. The retired lab's results and scripts stay archived. Manual visual checks remain necessary on the affected device.

The [longer headless run on 2026-11-06](headless-locale-comparison-2026-11-06.md) passed all 40 timing and 10 memory trials; manual recreation was faster to frame commit in that emulator workload. The reported manual-slower/visible-flash symptom remains unconfirmed, and no physical device was connected for that run.

**Reported behavior; cause unconfirmed.** The author observed manual view rebuilding working where `recreate()` did not update the UI. Current MainActivity calls `createContentView()` from `onCreate()`. `triggerTrueConfigChangeRecreate()` currently only calls `ActivityCompat.recreate()`; its name does not establish that a framework locale change occurred.

Investigation:

1. After earlier fixes, add temporary debug instrumentation: a monotonically increasing activity-instance ID and root-view creation ID, plus lifecycle event, resource language tags, flow language tags, saved-state presence, and `isChangingConfigurations` at destruction.
2. Log configuration at `attachBaseContext`, before/after `super.onCreate`, at XML inflation or ComposeView creation, and after restoration. Log the actual translated text shown as well as locale tags.
3. Compare four independent cases: system configuration recreation (such as rotation), locale update with ordinary recreation, manual content rebuilding, and process restart. Reset to the same starting language and navigation state for each case.
4. Classify the failure: no replacement activity, no new root, new root using stale resources, Compose not observing configuration, or saved state restoring stale presentation.
5. Fix the demonstrated failing boundary. Do not adopt hidden-field reflection or orientation toggling as a general solution.

Verification — **Manual required:** reproduce on the device/API where the failure was originally seen, then compare another API level. The passing case must demonstrate new activity/root IDs when recreation is intended, correct resource configuration at creation, and translated XML and Compose content after restoration. Verify navigation state and user-entered text are preserved according to their own contracts.

- [ ] A written reproduction and before/after trace identify the cause; correct visible content is verified independently of instance replacement.

## 12. Select and implement locale application strategies by Android version

**Current status:** Local context wrapping and activity recreation apply on all APIs, avoiding the automatic framework relaunch that showed a surface gap in the tested configuration. Android per-app language settings have independent ownership. AppCompat autoStore metadata is removed to avoid a second owner. The framework-owner plan below describes a possible future integration.

**Confirmed architectural gap.** The library currently mutates resources through deprecated `Resources.updateConfiguration()` and process defaults. It does not set the framework per-app locale preference. This is separate from a request to recreate an activity.

Fix:

1. On API 33+, use framework `LocaleManager` as the per-app override owner, including changes made outside the app. An empty framework override restores follow-system behavior.
2. Below API 33, offer an AppCompat integration where appropriate and an explicit context-wrapping integration for activities that do not use AppCompat. Preserve the project's goal of supporting non-AppCompat activities; do not silently make AppCompatActivity mandatory for every consumer.
3. Specify initialization, persistence/migration, system fallback, and configuration delivery for each strategy. Avoid simultaneous independent framework and library overrides.
4. Decide how a consumer exposes supported languages through locale configuration/system settings. Keep app-supported languages separate from the user's ordered preferences.
5. Verify framework callbacks reconcile observable state without causing update/recreation feedback loops.

Verification — **Manual required:** below API 33, test both AppCompatActivity and plain Activity/ComponentActivity fixtures. On API 33+, change language both through the app and Android's per-app language settings, then restart the process. Reset to system-following and verify subsequent system language changes. Check regional preferences, fallback order, and an RTL language with suitable translated test resources.

- [ ] Each advertised integration works on its documented API range, with one owner of locale application and persistence.

## 13. Define back-stack refresh and optional manual view rebuilding

**Current status:** Implemented: stale started activities receive queued recreation; stopped activities defer until started; no title reset or view-factory invocation. Sample back handling and ComposeView ID are corrected. Two-level AppCompat back navigation/title tests pass. Multi-window/tasks and broader navigation stress remain open.

**Confirmed issues.** `_backstackActivities` tracks all created, undestroyed activities, not just started ones or a task-aware back stack. Every entry is refreshed. `ViewCreatorComponent` promises view-only rebuilding, but both branches recreate the activity. `resetTitle()` can also replace an activity's runtime title with its manifest label.

Fix:

1. Define behavior for resumed, started, stopped, finishing, and already-recreating activities, including multiple tasks/windows and nonparticipating third-party activities.
2. For custom integrations, consider recording the locale generation applied to each activity and refreshing stale activities when appropriate, rather than immediately rebuilding every tracked instance. Coordinate this with framework-owned configuration changes.
3. Use the normal activity lifecycle for view creation. The library removed `ViewCreatorComponent`.
4. If retained, define state saving/restoration, old-view cleanup, bindings/listeners, toolbars, fragment containers, and Compose disposal. Creating a root view alone does not manage these responsibilities.
5. Let activity-owned runtime titles be recomputed by the activity; do not blindly overwrite them from manifest metadata.
6. Make the sample's navigation usable during verification: MainActivity currently installs an enabled, empty back callback that consumes back presses.

Verification — **Manual required:** navigate Main → Sample level 1 → Sample level 2, switch locale at level 2, then return through every screen. Repeat with the app backgrounded during a change, rapid locale changes, a dialog open, and multiple windows/tasks where supported. Check correct translations, runtime titles, destination, retained ViewModels, saved user input, and absence of extra activities or flicker. If view-only rebuilding remains, repeat with bindings, Compose, and fragment containers and check old views are released.

- [ ] Refresh behavior is predictable across the stack; optional view rebuilding has an explicit state/lifecycle contract.

## 14. Verify and constrain the WebView workaround

**Current status:** Implemented a narrower contract: repair native resources in manual mode without creating another WebView; no-op during explicit framework experiments; local HTML sample and WebView cleanup. Native-locale preservation is tested on API 32 and API 36, including manual mode on modern Android. Original affected provider/version behavior and web-state preservation remain open.

**Unverified workaround.** `fixWebViewLocale()` creates/destroys a new WebView and reapplies application resources. It has a TODO to test it. This does not establish that existing activity resources, existing WebViews, or remote website language preferences are repaired. The sample loads a remote website, which is not a deterministic locale test.

Fix:

1. Establish whether WebView initialization actually changes resource configuration on supported devices/provider versions, and when it happens.
2. Use deterministic local HTML/test content and separately check Android resource strings, date formatting, and web content language.
3. If a workaround is still necessary, run it on the main thread and restore the intended state after the demonstrated mutation. Avoid creating extra WebViews without evidence that doing so is necessary.
4. Document its exact scope. Website language can depend on server behavior, account preferences, cookies, or request headers independently of Android resources.

Verification — **Manual required:** record Android API and WebView provider version. Capture locale state and native translations before first WebView creation, immediately afterward, after changing locale with a WebView open, and after reopening/restarting. Check navigation and web content state are preserved where promised. Repeat on a provider/device combination that originally exhibited the problem if available.

- [ ] The workaround is either supported by a reproducible regression test and device check, or removed with its limitations documented.

## Final regression gate

After the applicable fixes and focused tests pass:

1. Run `./gradlew.bat :locale-easy:testDebugUnitTest :locale-easy:lintDebug :app:assembleDebug` from the repository root. Add/run the relevant module's instrumentation tasks on configured devices; use `connectedDebugAndroidTest` only after test fixtures and device setup exist.
2. Cover API boundaries: API 21/22 library fixture, API 23 sample, API 24+ locale lists, API 29 pre-create callbacks, API 32 legacy behavior, API 33 framework locales, and API 36 (the current project target). This is a coverage plan, not a claim these runs have happened.
3. Complete the manual scenarios for cold start, locale change, follow-system, rotation/theme, activity back navigation, fragments/dialogs, and WebView. Include the originally failing device/API.
4. Record failures and evidence next to the relevant checkbox. Do not claim broad reliability from a successful compile or one translated screen.

## Android references

- [LocaleManager](https://developer.android.com/reference/android/app/LocaleManager): API-33 per-app locale overrides, persistence, system locales, and configuration changes.
- [Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages): framework and AppCompat integration, storage, and supported-language configuration.
- [Configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes): recreation, configuration handling, and state responsibilities.
- [Android 16 QPR2 ActivityThread source](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java): local relaunch and shared activity relaunch handling. This describes one framework implementation, not a guarantee about every Android release/device.
