# Recreation Lab: initial findings

The Android lab module was retired on 2026-11-06. These findings describe the earlier isolated harness; use the [sample selector](recreation-comparison.md) for current interactive comparison. Historical source is available in commit `89194fc`.

Date: 2026-11-05. The [isolated lab](../recreation-lab/README.md) is implemented and executable. These are harness-validation observations from debug builds on disposable emulators, not a production performance ranking or a reproduction of the original device-specific failure.

Follow-up: the [2026-11-06 headless comparison](headless-locale-comparison-2026-11-06.md) completed 20 timing trials and five memory trials per locale path on one API-36 emulator, plus fresh method captures.

## What was verified

- Final APK build and lint passed; lint reports 0 errors and 11 warnings. Parser unit tests: 3 passed.
- Final timing matrix: 10 successful trials on API 32 and 12 on API 36, two per supported case with the same 200-row workload. The API-33+ framework locale case was explicitly skipped on API 32.
- Earlier diagnostic passes produced five complete instrumented method traces across API 32/36, one sampled method trace, four pairs of heap snapshots across the two APIs, background memory timelines, and two finalized API-36 Perfetto captures. Instrumented method traces reported no buffer overflow.
- The rebuilt background sampler captured 5 samples during direct recreation and 14 during rotation in its API-32 validation run, plus samples after commit. It continues independently while the UI thread is rebuilding.
- Both finalized Perfetto files contain `Lab.transition` markers and were nonempty (approximately 6.2 MB and 7.1 MB). The runner now waits for writer termination before pulling files; an earlier fixed-delay approach could pull an unfinished file.
- Each successful trial verified expected activity/root replacement, saved input, and applicable target locale/orientation.

The final timing metadata and compact results are committed in [smoke-2026-11-05.json](../recreation-lab/results/smoke-2026-11-05.json), including the installed APK hash and display configuration. Full raw files remain under `build/recreation-lab/`; the failed/earlier experiments are preserved separately and are not counted as successful captures. The diagnostic passes preceded the final keyboard-control/metadata polish; compare their paths and inventories, not their timings with the final matrix.

## Observed execution paths

Actual instrumented traces on API 36 showed these simplified main-thread paths. Intermediate transaction/Looper frames are omitted here but retained in `method-analysis.json` under `framework_paths`.

| Manual locale context + recreate | Framework app-locale change |
| --- | --- |
| `Activity.recreate()` requests a local relaunch | `LocaleManager.setApplicationLocales()` requests a configuration change |
| `ActivityThread.handleRelaunchActivityLocally()` | A parcelled `ActivityRelaunchItem` arrives from the framework |
| `ActivityRelaunchItem.execute()` | `ActivityRelaunchItem.execute()` |
| `ActivityThread.handleRelaunchActivity()` | `ActivityThread.handleRelaunchActivity()` |
| `ActivityThread.handleRelaunchActivityInner()` | `ActivityThread.handleRelaunchActivityInner()` |
| `ActivityThread.handleLaunchActivity()` | `ActivityThread.handleLaunchActivity()` |
| `ActivityThread.performLaunchActivity()` | `ActivityThread.performLaunchActivity()` |

The API-32 rotation trace likewise reached the shared relaunch path without an `Activity.recreate()` entry. Direct recreation and manual locale recreation entered through the local relaunch path. This is execution evidence from these Android builds, rather than an assumption based solely on source inspection. The trace does not expose every native or system-server method.

## View/window identities

In the final trials:

- Direct `recreate()` and manual locale-context recreation preserved the decor identity while replacing activity and content-root identities on both APIs.
- Rotation replaced the decor, activity, and content root on both APIs.
- Framework locale changes replaced all three on API 36. LocaleEasy followed that framework behavior on API 36 and the local behavior on API 32.
- View-only rebuilding kept the activity and decor, replacing its content root and explicitly restoring the fixture's view state.

Window/decor preservation and content-view preservation are different. The observations do not support assuming that every natural configuration recreation preserves more of the old window hierarchy than a manual recreation. None of these identity checks establishes how many blank frames a human would see.

## Memory and classes

The lab records memory with several distinct scopes: total PSS/private dirty, runtime Java heap usage, native allocated bytes, ART counters, and optional background samples. The snapshots occur before the trigger and roughly 200 ms after frame commit, without forced GC in timing/memory modes. They are not retained-heap measurements or leak assertions.

The API-36 heap diagnostic found 29,948 unique class names in LOAD_CLASS metadata both before and after each locale path, with no added names. View/activity recreation still occurred. That indicates class membership alone does not explain the work done in this fixture; it does not mean there were no allocations, collections, or object retention. HPROF inventory counts and `Debug.getLoadedClassCount()` do not have identical scope.

The analyzer reports exact names present in each dump and their set differences, and separately reports classes with observed method entries. It does not provide chronological load/unload events or per-loader identity. Raw HPROF files remain available for object/reference and retained-size analysis in a heap profiler.

## How to use these results

Start with `manual_locale` versus `framework_locale` on API 33+: they render the same language and hierarchy, while exercising the two different locale paths. The framework operation also has broader app-level configuration responsibilities. Keep rotation as a separate system-recreation control because it changes geometry and can involve rotation animation.

Use the unprofiled mode for timing distributions; use instrumented traces for path evidence. In the earlier API-32 200-row captures, full method instrumentation stretched transitions into approximately 11–19 seconds. Treating that as application latency would be misleading. Smaller 20-row diagnostic captures on API 36 recorded the relevant framework calls without overflow.

Two emulator repetitions per case validate the harness, not statistical performance. Some validation used two headless emulators on the same host; host load, AVD setup, caches, and debug instrumentation vary. Repeat with more iterations on the originally affected physical device before selecting an optimization.

## Next investigation

1. Run a larger unprofiled distribution for both locale cases on the affected device, keeping workload, display, animations, and thermal conditions fixed.
2. Inspect the `Lab.transition.*` intervals in Perfetto for main-thread blocking, GC, scheduling delays, and window/render work.
3. Compare `framework_paths` and the CPU call tree to identify expensive work shared by both paths versus work unique to one.
4. Inspect raw heaps if memory continues growing across repeated transitions; use a separate multi-transition/leak experiment rather than interpreting a single fresh-process PSS delta as a leak.
5. Reproduce any visible flash with the original app or a matching workload. Frame commit is submission to the swap chain; it is not proof of physical presentation or an automatic blank-frame detector.

The lab is a reproducible place to test a workaround. It does not change LocaleEasy's recreation strategy or establish that private framework fields would improve it.
