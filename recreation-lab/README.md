# Recreation Lab

A separate Android app for comparing activity recreation, genuine configuration changes, and view-only rebuilding. It has its own package (`com.elianfabian.recreationlab`), platform Activity/theme, workload, results, and process. The sample app and the production library's behavior are unchanged by the experiment.

Requires Android 10 / API 29+ for frame-commit callbacks. The framework-locale case requires API 33+. Raw cases disable the AndroidX Startup provider; only the `localeeasy` case explicitly installs LocaleEasy. The library is still present in the APK, so class verification/loading is not claimed to be identical to an APK with no library dependency.

## Interactive comparison

Run the **recreation-lab** module in Android Studio, or install its debug APK and open **Recreation Lab** from the launcher.

1. Select a target language different from the primary system language, and choose 50, 200, or 500 text rows.
2. Tap **Test manual recreation** or **Test framework recreation**.
3. Optionally edit the saved-input field. Close the keyboard if you want to observe the transition without an IME animation, then tap **Apply**.
4. Watch the transition and read the result: activity/view replacement, preserved input, applied locale, time to frame commit, and PSS snapshot difference.
5. Tap **Back to tests**, then try the other path. The menu retains the latest completed result for each locale path.

Both locale paths require API 33+ for a direct comparison. On API 29–32, manual recreation and the natural rotation control remain available; framework locale recreation is disabled with an explanation. Selecting the primary system language disables locale trials to avoid measuring a no-op.

The controls stay in the main process. Each interactive workload runs in a fresh `:trial` process, resetting this lab's framework locale override before displaying the baseline. The menu survives these resets. Results remain under `files/runs/ui_*/result.json`; the controls and remembered results are local to this test app.

Interactive trials wait for your Apply action and include additional controls. Their timings are **not directly comparable** to the automated workload below. Frame commit is submission, not physical presentation; PSS differences are snapshots, not leak measurements. Manual observation is still required to judge visible blanking, animation quality, and perceived responsiveness on the device of interest.

To run the UI checks on an explicitly selected emulator:

```powershell
$env:ANDROID_SERIAL = 'emulator-5558'
./gradlew.bat :recreation-lab:connectedDebugAndroidTest --no-configuration-cache
```

The tests exercise repeated manual/framework trials with edited input on API 33+, manual/rotation on older supported APIs, and the no-op language guard. The original explicit `HarnessActivity` entry point and CLI runner remain available.

## Cases

All automated cases render the same hierarchy: a title, an EditText containing saved user input, and a configurable number of localized text rows. Default: 200 rows. The soft keyboard is kept hidden to avoid introducing an IME transition. Every automated trial starts a fresh process, waits for the initial frame to commit, then waits 500 ms before recording a baseline and triggering its operation.

| Case | Trigger | Expected result | Important difference |
| --- | --- | --- | --- |
| `recreate` | `Activity.recreate()` | New activity and content root | Locale stays unchanged; isolates local relaunch |
| `rotation` | Request landscape after a portrait baseline | System configuration change, new activity and root | Viewport/rotation animation changes too; not a like-for-like locale benchmark |
| `manual_locale` | Create a target-language base context, then call `recreate()` | New activity and root in target language | Changes the activity context; does not set a process-wide framework locale preference |
| `framework_locale` | `LocaleManager.applicationLocales` | Genuine locale configuration change, new activity and root | API 33+; framework also handles app-level resources and persistence |
| `localeeasy` | `LocaleEasy.setLocale()` | New activity/root with the new language | Uses the library's API-specific backend |
| `view_only` | Build content with a localized context and replace the root | Same activity, new root | Explicitly saves/restores view hierarchy state; activity resources stay unchanged |

The target is French unless the system's primary language is already French, in which case it is English. Framework app-locales are reset before each workload starts. The runner changes only this lab package; it force-stops it before and after trials. Use a disposable emulator for repeatable investigation.

## Measurements and artifacts

| Artifact | What it provides | Limits |
| --- | --- | --- |
| `result.json` | Lifecycle events; activity, root, and decor identities; before/after language, input, and orientation; trigger-to-draw and trigger-to-frame-commit time | A frame-commit callback means submission to the swap chain, not proof of physical display presentation or absence of blank frames |
| Before/after memory objects | PSS/private-dirty KB; Java used/committed bytes; native allocated bytes; runtime-reported class count; ART runtime/GC statistics | Two snapshots, not peak PSS or a memory-leak test; garbage may remain uncollected |
| `memory_samples` | Background Java/native allocation samples, approximately every 25 ms in memory mode | Best-effort sampling can miss peaks; the sampler itself changes memory/CPU usage |
| `before.hprof`, `after.hprof` | Raw heap snapshots for Android Studio or another heap analyzer | Dumping may trigger GC and pauses; compare in a separate diagnostic pass |
| `class-inventory.json` | Before/after class-name inventories from HPROF LOAD_CLASS records; added/removed names | No chronological class-load log, per-loader identity, allocation counts, or retained sizes. Names are deduplicated across loaders |
| `methods.trace` | ART Java/Kotlin and in-process framework method trace | Does not cover every native call or system-server method; instrumentation can heavily slow execution |
| `method-analysis.json` | Observed methods/classes, entry transitions, reconstructed main-thread paths, selected framework paths, overflow indicator | Sampled traces can miss short calls; entry counts in sampling mode are not exact invocation counts |
| `system.pftrace` | Perfetto scheduling, activity/window/rendering/GC events, process/system memory counters, `Lab.*` trace sections | System-level slices and scheduling are not a complete Java/native call trace |
| `meminfo.txt`, `gfxinfo.txt` | Additional Android diagnostic dumps after completion | Frame statistics include initial startup; use the `Lab.transition.*` interval to focus a system trace |

Runtime class counts and HPROF metadata inventories have different scopes and should not be compared as equal counts. An unchanged class inventory can accompany substantial allocation and view rebuilding.

Each successful trial checks that the expected activity replacement occurred, the content root changed, saved input survived, and the requested locale/orientation reached the view. `PASS` checks those behaviors; it does not assert a performance threshold.

## Build and run

From the repository root, with Python 3.9+ and a compatible JDK (tested with JBR 21):

```powershell
./gradlew.bat :recreation-lab:assembleDebug :recreation-lab:lintDebug --no-configuration-cache
python -m unittest discover -s recreation-lab/tools -p 'test_*.py'

$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb devices

# Replace this with the explicit serial of the disposable test device.
$device = 'emulator-5556'
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --apk recreation-lab/build/outputs/apk/debug/recreation-lab-debug.apk --mode timing --iterations 10 --out build/recreation-lab/timing
```

Only the first command needs `--apk`; later commands use the installed lab. Case order is randomized with seed 42 by default. Use `--seed`, `--rows`, and `--cases` to control the experiment. Each output directory must be new, so captures cannot silently overwrite earlier results.

On API 33+, the most directly relevant locale comparison is:

```powershell
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases manual_locale framework_locale --mode timing --iterations 20 --out build/recreation-lab/locale-timing
```

The view workload/language match, but the framework operation has broader responsibilities than activity-only context wrapping. Rotation is a separate control, not an equivalent stimulus.

Run profilers separately:

```powershell
# Java/native memory timeline and before/after PSS.
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases recreate rotation --mode memory --iterations 1 --out build/recreation-lab/memory

# Lower-overhead sampled Java/framework calls.
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases manual_locale framework_locale --mode sampling --iterations 1 --out build/recreation-lab/sampling

# Instrumented entry/exit paths: use fewer rows to keep captures short.
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases manual_locale framework_locale --rows 20 --mode methods --iterations 1 --out build/recreation-lab/methods

# Class inventories and raw heaps; pauses/GC affect this pass.
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases manual_locale framework_locale --mode heap --iterations 1 --out build/recreation-lab/heaps

# Separate system tracing pass, about 10 seconds per trial.
python recreation-lab/tools/run_lab.py --adb $adb --serial $device --cases manual_locale framework_locale --perfetto --iterations 1 --out build/recreation-lab/perfetto
```

All profiler modes and `--perfetto` runs are labeled `timing_is_profiled=true`. Compare their paths, not their timing against an unprofiled run. The 200-row full method traces in the pilot slowed transitions into seconds, demonstrating why the separation matters. `buffer_overflow=true` means the method capture is incomplete. The runner preserves the raw trace and exposes that flag rather than treating its call inventory as exhaustive.

The output includes `environment.json` (fingerprint/API, APK hash, screen size/density, experiment settings), one folder per trial, `results.json`, and `summary.csv`. Unprofiled timing is still a debug-build diagnostic with recorder overhead, not a release Macrobenchmark. The CSV reports medians/ranges and snapshot deltas; it deliberately does not infer leaks or a winning strategy.

The runner reads binary artifacts through binary-safe subprocess streams on Windows. For Perfetto it supplies config through stdin, waits for the writer to exit using readable process state, and rejects empty captures. Some Android SELinux policies deny reading config in `/data/local/tmp` or probing a process with `kill -0`.

## Inspecting code paths and memory

1. Open `method-analysis.json` and start with `framework_evidence` and `framework_paths`. The latter retains the observed stack at selected framework entries, rather than only reporting a method-name list.
2. Import `methods.trace` into Android Studio's CPU trace viewer for its call tree/timeline. Use instrumented traces for entry/exit evidence, sampled traces for approximate stacks.
3. Open `system.pftrace` in [Perfetto](https://ui.perfetto.dev/) and locate the lab process and `Lab.transition.CASE` interval. Inspect scheduling, GC, window, and rendering slices around it. This local capture does not require uploading artifacts to a server.
4. Read `class-inventory.json` for class membership changes. Open the raw HPROF files in Android Studio for object counts, references, and retained-heap investigation; this script does not compute retained memory.
5. Compare `memory_samples` and ART counters to determine whether allocations/GC coincide with the transition. PSS deltas alone are not evidence of a leak.

Artifacts can also be reanalyzed independently:

```powershell
python recreation-lab/tools/analyze_artifacts.py --trace PATH/methods.trace --out PATH/method-analysis.json
python recreation-lab/tools/analyze_artifacts.py --before PATH/before.hprof --after PATH/after.hprof --out PATH/class-inventory.json
```

For exact chronological class loading/unloading, or a complete native/system-server call graph, this lab would need an additional JVMTI/native profiling setup or an instrumented AOSP build. It does not infer those events from heap membership or a loaded-class counter.

### Summarizing separate passes

The comparison tool validates matching APK hashes, fingerprint, display, workload, and locale stimulus for timing/memory passes, and keeps profiled timings out of the primary latency distribution:

```powershell
python recreation-lab/tools/summarize_comparison.py --timing-dir build/recreation-lab/locale-timing --memory-dir build/recreation-lab/memory --methods-dir build/recreation-lab/methods --out build/recreation-lab/comparison.json
```

Supply directories that contain both locale cases; the earlier memory command uses recreation/rotation, so use `--cases manual_locale framework_locale` for this comparison. The method pass may use fewer rows; it is described separately. `p95` uses linear interpolation at `(n - 1) * .95`, not a confidence interval. Phase medians describe request → replacement `onCreate`, `onCreate` → `onStart`, and `onStart` → frame commit. They are wall-clock intervals, not pure CPU stages, and their medians do not sum to the overall median.

Method analysis now also reports `main_thread_timings` in microseconds using ART v1–v3 CPU/wall clock fields, plus `timing_coverage`. Inclusive timings require matching entry/exit records; self timings additionally require complete child intervals. Open or unmatched frames are reported rather than assigned invented durations. An overflow-free file can still contain open frames at recording boundaries. Summed inclusive times double-count nesting, and all these method timings include profiling overhead. Short-call CPU/wall totals can also be distorted by clock precision and recorder overhead. See the [ART writer format](https://android.googlesource.com/platform/art/+/5c4f3e5e8b1031ab112e273620e2b2cb89432d57/runtime/trace.cc) for the record layout.

The [longer headless comparison](../docs/headless-locale-comparison-2026-11-06.md) records 20 timing trials and five memory trials per path on one emulator.

The saved graphics dumps can also be analyzed without a device or screen access:

```powershell
python recreation-lab/tools/analyze_rendering.py --input-dir build/recreation-lab/locale-timing --out build/recreation-lab/rendering.json
```

This extracts the first completed workload frame starting inside the replacement `onCreate` → frame-commit interval. It rejects unaligned Uptime/Realtime clock headers and frames within a 1 ms ambiguity band at the boundaries. It reports raw frame-work, vsync lateness, traversal/draw/sync intervals, deadline overrun, flags, and presentation-timestamp availability. Initial startup frames and harness windows are excluded. The deadline-overrun counts include new-window first draws and must not be presented as ordinary animation jank rates. Frame completion is not display presentation, and the parser does not classify pixel content.

## Experimental controls

- Use one device/API and the same workload/mode/settings for a comparison. Keep system language, display geometry, power/thermal state, animation settings, and background work consistent.
- The runner uses a fresh process per trial; OS caches, dex optimization, and host load can still persist. Initial class loading and library installation happen before the transition baseline.
- Use more repetitions and inspect distributions on physical hardware before choosing an optimization. Two emulator trials per case only establish that the harness works.
- The view-only fixture has no fragments, toolbar bindings, retained controllers, or Compose composition. Its speed cannot establish that root replacement is a safe generic replacement for recreation.
- This workload is deliberately separate from the sample app. It does not reproduce the original device-specific visual failure by itself, or automatically count blank displayed frames. Use the sample/original app for final visual validation.
- Use short instrumented captures. Slow method tracing can exceed the 30-second in-app completion watchdog; reduce `--rows` rather than comparing that result with normal timing.

See [the initial findings](../docs/recreation-lab-findings.md) for executed validation and observed framework paths.

## References

- [Android Debug API](https://developer.android.com/reference/android/os/Debug): runtime counters, method tracing, memory info, and HPROF dumps.
- [Record Java/Kotlin methods](https://developer.android.com/studio/profile/record-java-kotlin-methods): entry/exit instrumentation and its overhead.
- [System tracing](https://developer.android.com/topic/performance/tracing/on-device): Perfetto traces and system-event inspection.
- [Frame commit callback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)): submission is not a guarantee of presentation on screen.
